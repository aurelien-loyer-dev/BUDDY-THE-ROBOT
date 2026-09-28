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
- **Scan de marqueurs ArUco** : `BuddySDK.Vision.getListOfArucos(ArucoDictionary.DICT_4X4_50)` en polling (400ms), confirmation sur 3 détections consécutives pour éviter les faux positifs, annonce vocale du numéro détecté. Voir les commentaires dans `MainActivity.kt` pour l'historique du diagnostic (dictionnaire, permission `VISION`, tracker de personnes concurrent).
- **Écran de diffusion en direct** (`display/`) : un mini-serveur HTTP + WebSocket embarqué (`StateServer.kt`, NanoHTTPD) sert une page React qui affiche en direct ce que Buddy entend/répond, pour un écran annexe au stand du salon. Voir la section dédiée plus bas.

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

## Écran de diffusion (salon)

Un écran annexe peut afficher en direct l'état de la conversation (écoute / réflexion / réponse, dernier marqueur scanné) — utile pour un public non-technique dans un salon bruyant qui ne sait pas toujours si Buddy a compris.

**Architecture** : tout est servi par le robot lui-même, aucune autre machine n'a besoin de tourner pendant le salon.

```
[Android App]  --state changes-->  [StateServer.kt : NanoHTTPD]
                                     ├─ sert la page React (assets/web/)
                                     └─ diffuse l'état en WebSocket (/ws)
                                              |
                        http://<ip_buddy>:8080/  <-- navigateur de l'écran
```

**Build de l'écran (`display/`, React + Vite + TypeScript)** :

```bash
cd display
npm install
npm run build
rm -rf ../app/src/main/assets/web && mkdir -p ../app/src/main/assets/web
cp -r dist/* ../app/src/main/assets/web/
```

Puis rebuild/redéploie l'app Android normalement — la page est servie automatiquement.

**Dev local** (itérer sur l'UI sans rebuild Android à chaque fois) :

```bash
cd display
VITE_WS_HOST=<ip_buddy> npm run dev   # se connecte au StateServer du robot en direct
```

**Utilisation le jour du salon** : ouvrir un navigateur sur l'écran et naviguer vers `http://<ip_buddy>:8080/`. Reconnexion automatique si l'app ou le robot redémarre.

## Limitations connues

Le scan de marqueurs a d'abord été tenté en QR code générique (`getListOfQRCodes`) puis en dictionnaire ArUco `DICT_6X6_250` — les deux ont échoué en test réel. Le diagnostic final (2026-07-13/14) a identifié plusieurs causes cumulées, toutes corrigées :
- Les marqueurs imprimés pour le salon sont en `DICT_4X4_50`, pas `6X6_250` (identifié par scan multi-dictionnaires en parallèle).
- Permission `com.bfr.buddy.resource.VISION` manquante dans le Manifest.
- Le tracker de personnes natif de BuddyCore (`_VISION Person tracker`) tournait en concurrence sur la caméra — coupé via `BuddySDK.Vision.stopTracking()`.
- **BuddyCore peut se retrouver dans un état bloqué** (planner Companion coincé sur `id_infiniteWait`, plus aucun `onSDKReady`) après beaucoup de cycles rapides install/force-stop/relaunch pendant le dev — **un reboot du robot** (`adb reboot`) résout ça systématiquement. Réflexe à avoir si l'app se comporte anormalement sans raison apparente.

La tâche Companion native `realign` existe bien (confirmé par test réel, basée sur de la détection AprilTag interne — `BuddySDK.Companion.createTask("realign", TaskParameters(mapOf("markerId" to "0")))`), mais fait physiquement tourner le robot pour s'aligner et ne renvoie pas l'ID détecté de façon exploitable — non utilisée ici.

## Documentation du code

Le code est commenté au format KDoc. Pour générer la doc HTML avec [Dokka](https://github.com/Kotlin/dokka) (v2) :

```bash
./gradlew dokkaGenerateHtml   # sortie dans app/build/dokka/html/index.html
```
