# BUDDY-THE-ROBOT

App Android pour le robot Buddy (Blue Frog Robotics), sous BuddyOS (Android 9).

## Setup

1. Copier `local.properties.example` vers `local.properties` (déjà ignoré par git) et renseigner :
   - `sdk.dir` vers ton Android SDK
   - `buddy.repo.username` / `buddy.repo.password` : identifiants du repo Maven privé BFR (Artifactory)
   - `groq.api.key` : clé API Groq (Q&A), injectée dans `BuildConfig.GROQ_API_KEY`
2. Ouvrir le projet dans Android Studio et laisser Gradle sync (le wrapper `./gradlew` est déjà fourni, Gradle 9.4.1 / JDK 17+).
3. `applicationId` par défaut : `com.example.buddyug` — à renommer dans `app/build.gradle.kts` et le package Kotlin si besoin.

## Fonctionnalités

Buddy reste immobile. `MainActivity` fait tourner un robot conversationnel :

- **Q&A vocal** : boucle STT (Google, français) → [Groq](https://groq.com) (`llama-3.3-70b-versatile`) → TTS, avec historique de conversation.

## Connexion au robot (ADB WiFi)

```bash
adb connect <ip_buddy>:5555
adb devices
```

## Config app sur le robot (une fois par device, pour éviter que BuddyCore ne coupe l'app)

```bash
adb -s <ip>:5555 shell "echo '{
  \"AskToQuit\": false,
  \"Autostart\": true,
  \"CloseWidgetVisibility\": \"NEVER\",
  \"MenuWidgetVisibility\": \"NEVER\",
  \"Name\": \"<NomApp>\",
  \"Package\": \"<applicationId>\",
  \"ShowInMenu\": true,
  \"DevOnly\": false
}' > /sdcard/Configs/Applications/<applicationId>/config.json"
adb -s <ip>:5555 reboot
```

Le champ `companionMode: autonomous` évoqué dans certains guides BFR n'existe pas dans le SDK réel (`CompanionMode` n'a que `NORMAL`/`EMPTY`, confirmé par décompilation) et aucune autre appli du device ne l'utilise — retiré ici. Le hotword "Ok Buddy" (moteur Cerence/VoCon) qui coupait l'app est géré côté code via `BuddySDK.Speech.unloadVoCon()` dans `onSDKReady`.

## Limitations connues

Le scan de marqueurs (QR codes puis marqueurs ArUco via `BuddySDK.Vision.getListOfArucos()`) a été tenté et abandonné après une session de test approfondie : détection instable même avec un dictionnaire robuste (`DICT_6X6_250`) et confirmation multi-frames. Piste la plus probable : `BuddyCore` fait tourner en permanence son propre tracker de personnes (`_VISION Person tracker`) sur la même caméra, en concurrence avec toute détection custom. La tâche Companion native `realign` (basée sur de la vraie détection AprilTag interne) existe et fonctionne, mais fait physiquement tourner le robot pour s'aligner — incompatible avec un robot immobile — et ne renvoie pas l'ID détecté de façon exploitable.

## Documentation du code

Le code est commenté au format KDoc. Pour générer la doc HTML avec [Dokka](https://github.com/Kotlin/dokka) (v2) :

```bash
./gradlew dokkaGenerateHtml   # sortie dans app/build/dokka/html/index.html
```
