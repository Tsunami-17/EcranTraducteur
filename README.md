# Écran Traducteur

Appli Android qui lit le texte affiché à l'écran (OCR) et pose la traduction française par-dessus, bulle par bulle. Pensée pour les mangas, manhwas et webtoons.

Tout tourne sur le téléphone (Google ML Kit). Seul le premier usage d'une langue télécharge un modèle de traduction d'environ 30 Mo.

## Obtenir l'APK sans ordinateur (GitHub)

1. Crée un dépôt vide sur github.com.
2. Envoie tous les fichiers de ce dossier dedans (bouton "Add file" puis "Upload files", en gardant les dossiers).
3. Onglet **Actions** : le build "Construire l'APK" démarre tout seul (environ 5 min).
4. Ouvre le build terminé, télécharge **EcranTraducteur-apk**, dézippe, installe `app-debug.apk` (autorise "sources inconnues").

## Avec Android Studio

Ouvre le dossier, laisse Gradle se synchroniser, puis Run.

## Utilisation

1. Choisis l'écriture source : latin (anglais...), coréen, japonais ou chinois.
2. **Démarrer**, autorise l'affichage par-dessus les autres applis, puis accepte la capture en choisissant **Écran entier**.
3. Une bulle **FR** apparaît :
   - toucher : traduire l'écran
   - appui long : effacer les traductions
   - glisser : déplacer la bulle
   - **AUTO** : retraduit toutes les 4 secondes pendant que tu fais défiler
4. Pour arrêter : bouton "Arrêter" dans l'appli ou dans la notification.

## Limites connues

- Pense à effacer (appui long) ou à activer AUTO quand tu fais défiler, sinon les traductions restent à leur ancienne place.
- Sur Android 12 et plus, les cadres de traduction sont légèrement transparents (80 %) : c'est une règle du système pour que le défilement reste possible sous la surcouche.
- Pensé pour le mode portrait.
- Android 8.0 minimum.
