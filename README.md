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

Buddy reste immobile. Deux fonctionnalités tournent en parallèle dans `MainActivity` :

- **Q&A vocal** : boucle STT (Google, français) → [Groq](https://groq.com) (`llama-3.3-70b-versatile`) → TTS, avec historique de conversation.
- **Scan de marqueurs ArUco** : `BuddySDK.Vision` (module natif du SDK, caméra pilotée par BuddyCore via AIDL — pas d'accès direct à l'API Camera Android, donc pas de permission `CAMERA` à demander). `startCamera()` puis polling de `getListOfArucos(ArucoDictionary.DICT_4X4_50)` toutes les 400ms ; Buddy annonce à voix haute le numéro du marqueur détecté (cooldown de 5s pour éviter de répéter le même en boucle tant qu'il reste dans le champ de la caméra).

  On est passés de QR codes génériques à des marqueurs ArUco (dictionnaire `DICT_4X4_50`) car la détection QR générique (OpenCV/WeChat, via `getListOfQRCodes()`) ne détectait rien de fiable en test réel, et les marqueurs ArUco sont conçus pour la robotique (détection plus robuste) et retournent directement un numéro — exactement ce qu'on veut annoncer. Le dictionnaire `ArucoDictionary` supporte aussi des variantes AprilTag (`DICT_APRILTAG_16h5/25h9/36h10/36h11`) via la même API si besoin un jour.

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
  \"DevOnly\": false,
  \"companionMode\": \"autonomous\"
}' > /sdcard/Configs/Applications/<applicationId>/config.json"
adb -s <ip>:5555 reboot
```

## Documentation du code

Le code est commenté au format KDoc. Pour générer la doc HTML avec [Dokka](https://github.com/Kotlin/dokka) (v2) :

```bash
./gradlew dokkaGenerateHtml   # sortie dans app/build/dokka/html/index.html
```
