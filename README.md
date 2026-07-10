# BUDDY-THE-ROBOT

App Android pour le robot Buddy (Blue Frog Robotics), sous BuddyOS (Android 9).

## Setup

1. Copier `local.properties.example` vers `local.properties` (déjà ignoré par git) et renseigner :
   - `sdk.dir` vers ton Android SDK
   - `buddy.repo.username` / `buddy.repo.password` : identifiants du repo Maven privé BFR (Artifactory)
2. Ouvrir le projet dans Android Studio et laisser Gradle sync (le wrapper `./gradlew` est déjà fourni, Gradle 9.4.1 / JDK 17+).
3. `applicationId` par défaut : `com.example.buddyug` — à renommer dans `app/build.gradle.kts` et le package Kotlin si besoin.

Les chemins d'import du SDK (`com.bfr.buddysdk.*`) dans `MainApplication.kt` / `MainActivity.kt` sont à vérifier/ajuster une fois le Gradle sync effectué avec les vraies dépendances BuddySDK — non vérifiables sans accès au SDK.

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
