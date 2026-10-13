package ci.ahmed.ecrantraducteur

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat

class MainActivity : Activity() {

    private val reqCapture = 42
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dp = resources.displayMetrics.density
        val pad = (20 * dp).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        root.addView(TextView(this).apply {
            text = "Traduction de l'écran en français"
            textSize = 22f
            setTypeface(typeface, Typeface.BOLD)
        })

        root.addView(TextView(this).apply {
            text = "1. Choisis l'écriture du texte à traduire.\n" +
                "2. Touche Démarrer et accepte l'enregistrement de l'écran (choisis \"Écran entier\").\n" +
                "3. Une bulle FR apparaît. Ouvre ton manga/webtoon et touche la bulle.\n\n" +
                "Bulle FR : traduire l'écran\n" +
                "Appui long sur FR : effacer les traductions\n" +
                "Bouton AUTO : traduit tout seul toutes les 4 secondes\n" +
                "Glisser la bulle : la déplacer\n\n" +
                "La 1re traduction télécharge un modèle (environ 30 Mo, Wi-Fi conseillé). Ensuite tout marche hors ligne."
            textSize = 15f
            setPadding(0, pad / 2, 0, pad)
        })

        root.addView(TextView(this).apply {
            text = "Écriture du texte source"
            setTypeface(typeface, Typeface.BOLD)
        })

        val prefs = getSharedPreferences(OverlayService.PREFS, MODE_PRIVATE)
        val options = listOf(
            "latin" to "Latin (anglais, espagnol, portugais...)",
            "ko" to "Coréen (manhwa)",
            "ja" to "Japonais (manga)",
            "zh" to "Chinois (manhua)"
        )
        val current = prefs.getString(OverlayService.KEY_SCRIPT, "latin")
        val group = RadioGroup(this)
        options.forEachIndexed { i, (key, label) ->
            val rb = RadioButton(this).apply {
                id = 100 + i
                text = label
                isChecked = key == current
            }
            group.addView(rb)
        }
        group.setOnCheckedChangeListener { _, checkedId ->
            val key = options[checkedId - 100].first
            prefs.edit().putString(OverlayService.KEY_SCRIPT, key).apply()
        }
        root.addView(group)

        status = TextView(this).apply {
            textSize = 15f
            setPadding(0, pad, 0, pad / 2)
        }
        root.addView(status)

        root.addView(Button(this).apply {
            text = "Démarrer"
            setOnClickListener { start() }
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        root.addView(Button(this).apply {
            text = "Arrêter"
            setOnClickListener {
                stopService(Intent(this@MainActivity, OverlayService::class.java))
                status.postDelayed({ refreshStatus() }, 300)
            }
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        setContentView(ScrollView(this).apply { addView(root) })

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun refreshStatus() {
        if (OverlayService.running) {
            status.text = "Statut : actif"
            status.setTextColor(Color.rgb(20, 140, 60))
        } else {
            status.text = "Statut : arrêté"
            status.setTextColor(Color.GRAY)
        }
    }

    private fun start() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Autorise \"Afficher par-dessus les autres applis\", puis reviens ici", Toast.LENGTH_LONG).show()
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
            return
        }
        if (OverlayService.running) {
            Toast.makeText(this, "Déjà actif", Toast.LENGTH_SHORT).show()
            moveTaskToBack(true)
            return
        }
        val mpm = getSystemService(MediaProjectionManager::class.java)
        @Suppress("DEPRECATION")
        startActivityForResult(mpm.createScreenCaptureIntent(), reqCapture)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != reqCapture) return
        if (resultCode != RESULT_OK || data == null) {
            Toast.makeText(this, "Capture d'écran refusée", Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(this, OverlayService::class.java)
            .putExtra(OverlayService.EXTRA_CODE, resultCode)
            .putExtra(OverlayService.EXTRA_DATA, data)
        ContextCompat.startForegroundService(this, intent)
        moveTaskToBack(true)
    }
}
