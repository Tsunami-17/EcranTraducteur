package ci.ahmed.ecrantraducteur

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.IntentCompat
import androidx.core.widget.TextViewCompat
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.languageid.LanguageIdentifier
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.hypot
import kotlin.math.max

class OverlayService : Service() {

    companion object {
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        const val ACTION_STOP = "ci.ahmed.ecrantraducteur.STOP"
        const val PREFS = "reglages"
        const val KEY_SCRIPT = "script"
        private const val CHANNEL = "traduction"
        private const val NOTIF_ID = 1
        private const val AUTO_INTERVAL_MS = 4000L

        @Volatile
        var running = false
    }

    private lateinit var wm: WindowManager
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // Capture d'écran
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private val captureThread = HandlerThread("capture").apply { start() }
    private val captureHandler = Handler(captureThread.looper)
    private val imageLock = Any()
    private var lastImage: Image? = null
    @Volatile
    private var frameCount = 0L
    private var screenW = 0
    private var screenH = 0

    // Fenêtres flottantes
    private var controls: View? = null
    private var controlsParams: WindowManager.LayoutParams? = null
    private var bubble: TextView? = null
    private var autoBtn: TextView? = null
    private var overlay: View? = null

    // ML Kit
    private val recognizers = HashMap<String, TextRecognizer>()
    private lateinit var langId: LanguageIdentifier
    private val translators = HashMap<String, Translator>()
    private val readyModels = HashSet<String>()

    private var busy = false
    private var autoJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        langId = LanguageIdentification.getClient()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        // Obligatoire avant getMediaProjection() sur Android 14+
        startInForeground()

        if (projection == null) {
            val code = intent?.getIntExtra(EXTRA_CODE, 0) ?: 0
            val data = intent?.let { IntentCompat.getParcelableExtra(it, EXTRA_DATA, Intent::class.java) }
            if (data == null) {
                stopSelf()
                return START_NOT_STICKY
            }
            val mpm = getSystemService(MediaProjectionManager::class.java)
            val p = mpm.getMediaProjection(code, data)
            if (p == null) {
                stopSelf()
                return START_NOT_STICKY
            }
            p.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    stopSelf()
                }
            }, main)
            projection = p
            setupCapture()
            showControls()
            running = true
        }
        return START_NOT_STICKY
    }

    // ---------------------------------------------------------------- notification

    private fun startInForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Traduction d'écran", NotificationManager.IMPORTANCE_LOW)
        )
        val stopIntent = PendingIntent.getService(
            this, 0,
            Intent(this, OverlayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notif = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("Traducteur actif")
            .setContentText("Touche la bulle FR pour traduire l'écran")
            .addAction(0, "Arrêter", stopIntent)
            .setOngoing(true)
            .build()
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION else 0
        ServiceCompat.startForeground(this, NOTIF_ID, notif, type)
    }

    // ---------------------------------------------------------------- capture

    private fun readScreenSize() {
        if (Build.VERSION.SDK_INT >= 30) {
            val b = wm.maximumWindowMetrics.bounds
            screenW = b.width()
            screenH = b.height()
        } else {
            val m = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(m)
            screenW = m.widthPixels
            screenH = m.heightPixels
        }
    }

    @SuppressLint("WrongConstant")
    private fun setupCapture() {
        readScreenSize()
        val r = ImageReader.newInstance(screenW, screenH, PixelFormat.RGBA_8888, 3)
        r.setOnImageAvailableListener({ rd ->
            val img = try {
                rd.acquireLatestImage()
            } catch (e: Exception) {
                null
            } ?: return@setOnImageAvailableListener
            synchronized(imageLock) {
                lastImage?.close()
                lastImage = img
            }
            frameCount++
        }, captureHandler)
        reader = r
        virtualDisplay = projection?.createVirtualDisplay(
            "ecran-traducteur", screenW, screenH, resources.displayMetrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, captureHandler
        )
    }

    private fun grabBitmap(): Bitmap? = synchronized(imageLock) {
        val img = lastImage ?: return null
        val plane = img.planes[0]
        val buffer = plane.buffer.apply { rewind() }
        val w = img.width
        val h = img.height
        val full = Bitmap.createBitmap(plane.rowStride / plane.pixelStride, h, Bitmap.Config.ARGB_8888)
        full.copyPixelsFromBuffer(buffer)
        if (full.width == w) full else Bitmap.createBitmap(full, 0, 0, w, h).also { full.recycle() }
    }

    // ---------------------------------------------------------------- traduction

    private fun currentScript(): String =
        getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_SCRIPT, "latin") ?: "latin"

    private fun recognizerFor(script: String): TextRecognizer = recognizers.getOrPut(script) {
        when (script) {
            "ko" -> TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
            "ja" -> TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
            "zh" -> TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
            else -> TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        }
    }

    private fun defaultLanguage(script: String): String = when (script) {
        "ko" -> TranslateLanguage.KOREAN
        "ja" -> TranslateLanguage.JAPANESE
        "zh" -> TranslateLanguage.CHINESE
        else -> TranslateLanguage.ENGLISH
    }

    private fun translateScreen() {
        if (busy) return
        busy = true
        scope.launch {
            try {
                // 1. Cacher bulle + anciennes traductions, attendre une image propre
                clearOverlay()
                setControlsVisible(false)
                val before = frameCount
                withTimeoutOrNull(800) { while (frameCount == before) delay(30) }
                delay(120)
                val bmp = withContext(Dispatchers.Default) { grabBitmap() }
                setControlsVisible(true)
                if (bmp == null) {
                    toast("Capture pas encore prête, réessaie")
                    return@launch
                }
                bubble?.text = "..."

                // 2. OCR
                val script = currentScript()
                val blocks = recognizerFor(script).process(InputImage.fromBitmap(bmp, 0)).await()
                    .textBlocks
                    .filter { it.boundingBox != null && it.text.any(Char::isLetter) }
                if (blocks.isEmpty()) {
                    toast("Aucun texte détecté")
                    return@launch
                }
                val sep = if (script == "ja" || script == "zh") "" else " "
                val texts = blocks.map { b -> clean(b.lines.joinToString(sep) { it.text }, script) }

                // 3. Langue source (sur tout le texte de la page, plus fiable)
                val src = detectLanguage(texts.joinToString(" "), script)
                if (src == TranslateLanguage.FRENCH) {
                    toast("Le texte est déjà en français")
                    return@launch
                }

                // 4. Traduction hors ligne
                val translator = translatorFor(src)
                val out = texts.map { t -> runCatching { translator.translate(t).await() }.getOrDefault(t) }
                showOverlay(blocks.map { it.boundingBox!! }.zip(out))
            } catch (e: Exception) {
                toast("Erreur : ${e.message}")
            } finally {
                busy = false
                setControlsVisible(true)
                bubble?.text = "FR"
            }
        }
    }

    /** Recolle les lignes et remet en minuscules le texte écrit tout en MAJUSCULES (typique des webtoons). */
    private fun clean(raw: String, script: String): String {
        var t = raw.replace(Regex("\\s+"), " ").trim()
        if (script != "latin") return t
        t = t.replace(Regex("(\\p{L})- (\\p{L})"), "$1$2")
        val letters = t.filter { it.isLetter() }
        if (letters.isNotEmpty() && letters.count { it.isUpperCase() } > letters.length * 0.8) {
            t = t.lowercase()
                .replace(Regex("(^|[.!?]\\s+)(\\p{L})")) { it.groupValues[1] + it.groupValues[2].uppercase() }
                .replace(Regex("\\bi\\b"), "I")
        }
        return t
    }

    private suspend fun detectLanguage(text: String, script: String): String {
        val tag = runCatching { langId.identifyLanguage(text).await() }.getOrDefault("und")
        if (tag == "und" || tag.endsWith("-Latn")) return defaultLanguage(script)
        return TranslateLanguage.fromLanguageTag(tag.substringBefore('-')) ?: defaultLanguage(script)
    }

    private suspend fun translatorFor(src: String): Translator {
        val t = translators.getOrPut(src) {
            Translation.getClient(
                TranslatorOptions.Builder()
                    .setSourceLanguage(src)
                    .setTargetLanguage(TranslateLanguage.FRENCH)
                    .build()
            )
        }
        if (src !in readyModels) {
            toast("Préparation du modèle $src → fr (1re fois seulement)...")
            t.downloadModelIfNeeded().await()
            readyModels += src
        }
        return t
    }

    // ---------------------------------------------------------------- affichage

    private fun overlayParams(w: Int, h: Int, flags: Int) = WindowManager.LayoutParams(
        w, h,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        flags,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        if (Build.VERSION.SDK_INT >= 28) {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
    }

    private fun showOverlay(items: List<Pair<Rect, String>>) {
        clearOverlay()
        val dp = resources.displayMetrics.density
        val root = FrameLayout(this)
        val extra = (5 * dp).toInt()
        for ((r, txt) in items) {
            val tv = TextView(this).apply {
                text = txt
                setTextColor(Color.BLACK)
                gravity = Gravity.CENTER
                typeface = Typeface.DEFAULT_BOLD
                val p = (3 * dp).toInt()
                setPadding(p, p, p, p)
                background = GradientDrawable().apply {
                    setColor(Color.WHITE)
                    cornerRadius = 6 * dp
                    setStroke(max(1, dp.toInt()), Color.rgb(170, 170, 170))
                }
            }
            TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(
                tv, 7, 24, 1, TypedValue.COMPLEX_UNIT_SP
            )
            val w = r.width() + extra * 2
            val h = max(r.height() + extra * 2, (30 * dp).toInt())
            root.addView(tv, FrameLayout.LayoutParams(w, h).apply {
                leftMargin = max(0, r.left - extra)
                topMargin = max(0, r.top - extra)
            })
        }

        val params = overlayParams(
            screenW, screenH,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        ).apply {
            // Android 12+ bloque le défilement sous une surcouche plus opaque que 0.8
            if (Build.VERSION.SDK_INT >= 31) alpha = 0.8f
        }
        wm.addView(root, params)
        overlay = root

        // Remettre la bulle au-dessus des traductions
        val c = controls
        val cp = controlsParams
        if (c != null && cp != null) {
            runCatching { wm.removeView(c) }
            wm.addView(c, cp)
        }
    }

    private fun clearOverlay() {
        overlay?.let { runCatching { wm.removeView(it) } }
        overlay = null
    }

    private fun setControlsVisible(visible: Boolean) {
        controls?.visibility = if (visible) View.VISIBLE else View.INVISIBLE
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun showControls() {
        val dp = resources.displayMetrics.density
        val size = (54 * dp).toInt()

        val b = TextView(this).apply {
            text = "FR"
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.rgb(30, 91, 216))
                setStroke((2 * dp).toInt(), Color.WHITE)
            }
            elevation = 6 * dp
        }
        val a = TextView(this).apply {
            text = "AUTO"
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }
        styleAuto(a, false)

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(b, LinearLayout.LayoutParams(size, size))
            addView(a, LinearLayout.LayoutParams(size, (24 * dp).toInt()).apply {
                topMargin = (6 * dp).toInt()
            })
        }

        val params = overlayParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        ).apply {
            x = screenW - size - (10 * dp).toInt()
            y = screenH / 3
        }

        // Glisser = déplacer, toucher = traduire, appui long = effacer
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var moved = false
        var longFired = false
        val longPress = Runnable {
            longFired = true
            clearOverlay()
            toast("Traductions effacées")
        }
        b.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY
                    startX = params.x; startY = params.y
                    moved = false; longFired = false
                    main.postDelayed(longPress, 600)
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (!moved && hypot(dx, dy) > 12 * dp) {
                        moved = true
                        main.removeCallbacks(longPress)
                    }
                    if (moved) {
                        params.x = startX + dx.toInt()
                        params.y = startY + dy.toInt()
                        runCatching { wm.updateViewLayout(box, params) }
                    }
                }
                MotionEvent.ACTION_UP -> {
                    main.removeCallbacks(longPress)
                    if (!moved && !longFired) translateScreen()
                }
                MotionEvent.ACTION_CANCEL -> main.removeCallbacks(longPress)
            }
            true
        }

        a.setOnClickListener {
            if (autoJob?.isActive == true) {
                autoJob?.cancel()
                autoJob = null
                styleAuto(a, false)
                toast("Mode auto désactivé")
            } else {
                styleAuto(a, true)
                toast("Mode auto : traduction toutes les 4 s")
                autoJob = scope.launch {
                    while (isActive) {
                        translateScreen()
                        delay(AUTO_INTERVAL_MS)
                    }
                }
            }
        }

        wm.addView(box, params)
        controls = box
        controlsParams = params
        bubble = b
        autoBtn = a
    }

    private fun styleAuto(v: TextView, on: Boolean) {
        val dp = resources.displayMetrics.density
        v.setTextColor(if (on) Color.WHITE else Color.rgb(30, 91, 216))
        v.background = GradientDrawable().apply {
            cornerRadius = 12 * dp
            setColor(if (on) Color.rgb(232, 52, 78) else Color.WHITE)
            setStroke(max(1, dp.toInt()), Color.rgb(30, 91, 216))
        }
    }

    private fun toast(msg: String) {
        main.post { Toast.makeText(applicationContext, msg, Toast.LENGTH_SHORT).show() }
    }

    // ---------------------------------------------------------------- nettoyage

    override fun onDestroy() {
        running = false
        autoJob?.cancel()
        scope.cancel()
        clearOverlay()
        controls?.let { runCatching { wm.removeView(it) } }
        controls = null

        virtualDisplay?.release()
        virtualDisplay = null
        synchronized(imageLock) {
            lastImage?.close()
            lastImage = null
        }
        reader?.close()
        reader = null
        val p = projection
        projection = null
        runCatching { p?.stop() }

        translators.values.forEach { it.close() }
        recognizers.values.forEach { it.close() }
        langId.close()
        captureThread.quitSafely()
        super.onDestroy()
    }
}
