# BUDDY-THE-ROBOT

App Android pour le robot Buddy (Blue Frog Robotics), sous BuddyOS (Android 9), utilisée comme mascotte du stand Epitech La Réunion au **Salon de l'Étudiant**.

## Setup

1. Copier `local.properties.example` vers `local.properties` (déjà ignoré par git) et renseigner :
   - `sdk.dir` vers ton Android SDK
   - `buddy.repo.username` / `buddy.repo.password` : identifiants du repo Maven privé BFR (Artifactory)
   - `groq.api.key` : clé API Groq (Q&A), injectée dans `BuildConfig.GROQ_API_KEY`
2. Ouvrir le projet dans Android Studio et laisser Gradle sync (le wrapper `./gradlew` est déjà fourni, Gradle 9.4.1 / JDK 17+).
3. `applicationId` par défaut : `com.example.buddyug` — à renommer dans `app/build.gradle.kts` et le package Kotlin si besoin.

## Lancer / relancer Buddy (`./buddy`)

Une fois `buddy.ip=<ip_du_robot>` renseigné dans `local.properties` (ou via `BUDDY_IP=<ip> ./buddy ...`) :

| Commande | Effet |
|---|---|
| `./buddy deploy` | build de l'écran Next.js + APK, installation, relance de l'app, attente qu'elle réponde, ouverture de l'écran |
| `./buddy restart` | relance l'app sans redémarrer le robot (~5 s) |
| `./buddy reboot` | redémarre tout le robot, pour les cas bloqués (~1 min, **sur secteur**) |
| `./buddy status` | robot connecté ? app lancée ? écran joignable ? |
| `./buddy logs` | logs de l'app en direct (Ctrl+C pour quitter) |
| `./buddy screen` | ouvre `http://<ip>:8080/` dans le navigateur |

Comment `restart` relance l'app : BuddyCore refuse un `adb shell am start` direct de `MainActivity` (log « The activity will be restarted by companion », puis déconnexion du SDK). Au boot, le Companion ne lance pas l'app directement : il passe par son activité relais exportée `com.bfr.buddy.companion/com.bfr.buddy.utils.ActivityLauncher`, avec les extras `ActivityResultTracker_packageName_to_launch`, `ActivityResultTracker_activityName_to_launch` et `ActivityResultTracker_should_launch_with_isLockTask`. `restart` fait pareil après un `force-stop` : le Companion crée sa mission, donne le focus à l'app, et elle démarre normalement.

`reboot` est à éviter sur batterie : le 2026-11-11, après un `adb reboot` sur batterie, Buddy est resté éteint (écran noir, plus sur le réseau) jusqu'à un rallumage manuel. Pendant le salon, garde-le branché.
## Fonctionnalités

Buddy reste immobile. `MainActivity` fait tourner un robot conversationnel :

- **Q&A vocal** : boucle STT (Google, français) → [Groq](https://groq.com) (`qwen/qwen3.8-27b`, constante `GROQ_MODEL` ; `llama-3.3-70b-versatile` a été retiré de Groq en 2026) → TTS, avec historique de conversation. Le prompt système (`askGroq` dans `MainActivity.kt`) cible les lycéens, les étudiants en réorientation et leurs parents : présentation d'Epitech et du Hub, débouchés, ton tutoyant et motivant, réponse dans la langue du visiteur (français, anglais, créole…), 2-3 phrases maximum. **Il interdit d'inventer des chiffres** (tarifs, dates, places, salaires) : pour tout ce qui est administratif, Buddy renvoie vers l'équipe du stand ou epitech.eu. C'est le premier endroit à modifier pour un autre événement.
- **Jeux à marqueurs** : Profil tech et Quiz Vrai ou Faux, joués avec des cartes ArUco (voir la section dédiée plus bas).
- **Scan de marqueurs ArUco** : `BuddySDK.Vision.getListOfArucos(ArucoDictionary.DICT_4X4_50)` en polling (400ms), confirmation sur 3 détections consécutives pour éviter les faux positifs, le numéro détecté est diffusé à l'écran (l'annonce vocale est actuellement vide, donc désactivée). Voir les commentaires dans `MainActivity.kt` pour l'historique du diagnostic (dictionnaire, permission `VISION`, tracker de personnes concurrent).
- **Écran de diffusion en direct** (`display/`) : un mini-serveur HTTP + WebSocket embarqué (`StateServer.kt`, NanoHTTPD) sert une page Next.js qui affiche en direct ce que Buddy entend/répond, pour un écran annexe au stand du salon. Voir la section dédiée plus bas.

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

## Prononciation

Buddy prononce parfois mal certains mots (noms propres, sigles). Le dictionnaire `PRONUNCIATIONS` dans `MainActivity.kt` remplace ces mots par une écriture phonétique, **uniquement dans le texte envoyé à la voix** : l'écran garde l'orthographe correcte. Pour corriger un mot, ajouter une ligne `"mot" to "écriture phonétique"` puis `./buddy deploy`. Les remplacements ignorent la casse, ne touchent que les mots entiers, et les entrées les plus longues passent en premier (`Pré-MSc` avant `MSc`).

## Écran de diffusion (Salon de l'Étudiant)

Un écran annexe affiche en direct l'état de la conversation (écoute / réflexion / réponse, historique des derniers échanges, dernier marqueur scanné) — utile pour un public non-technique dans un salon bruyant qui ne sait pas toujours si Buddy a compris.

**Architecture** : tout est servi par le robot lui-même, aucune autre machine n'a besoin de tourner pendant le salon.

```
[Android App]  --state changes-->  [StateServer.kt : NanoHTTPD]
                                     ├─ sert la page Next.js (assets/web/)
                                     └─ diffuse l'état en WebSocket (/ws)
                                              |
                        http://<ip_buddy>:8080/  <-- navigateur de l'écran
```

**Message WebSocket** (un par changement d'état, le dernier est renvoyé à chaque nouvelle connexion) :

```json
{ "state": "idle|listening|thinking|speaking|marker", "heard": "texte entendu ou null",
  "response": "réponse de Buddy ou null", "marker": 5, "timestamp": 1794222094050 }
```

**Ce qu'affiche l'écran** (`display/app/page.tsx`, charte graphique Epitech) :
- logo officiel Epitech (`display/public/epitech-logo.svg`, version blanche, à ne pas modifier selon la charte) et indicateur de connexion ;
- l'état de Buddy, avec une couleur par état : vert Tech = écoute, orange Together = réflexion, rose Tomorrow = réponse ;
- la question entendue et la réponse en grand ; pendant la réflexion, la nouvelle question remplace l'échange précédent, qui passe dans l'historique ;
- un historique des derniers échanges, gardé côté navigateur et dédoublonné par `timestamp` (un rechargement de la page le vide) ;
- le numéro du dernier marqueur scanné, affiché 6 s.

Charte appliquée : bleu `#013afb`, triptyque `#00ff97` / `#ff5f3a` / `#ff1ef7`, polices Anton (titres), IBM Plex Sans (texte) et Space Mono (éléments tech), grille blueprint, pixels blancs à 50 %, underscore en fin de titre, balises `< … />`. Les polices sont embarquées dans l'export : l'écran n'a pas besoin d'Internet. Mise en page vérifiée en 1920×1080, en 1366×768 et sur mobile.

**Build de l'écran (`display/`, Next.js en export statique + TypeScript, charte graphique Epitech)** :

```bash
cd display
npm install
npm run android   # next build + copie de out/ vers app/src/main/assets/web/
```

`app/build.gradle.kts` surcharge `ignoreAssetsPattern` : par défaut AAPT ignore les dossiers commençant par `_`, donc le `_next/` de Next.js ne serait pas embarqué.

Puis rebuild/redéploie l'app Android normalement — la page est servie automatiquement.

**Dev local** (itérer sur l'UI sans rebuild Android à chaque fois) :

```bash
cd display
NEXT_PUBLIC_WS_HOST=<ip_buddy> npm run dev   # se connecte au StateServer du robot en direct
```

**Utilisation le jour du salon** : ouvrir un navigateur sur l'écran et naviguer vers `http://<ip_buddy>:8080/`. Reconnexion automatique si l'app ou le robot redémarre.

## Jeux à marqueurs

Deux jeux, lancés en montrant une carte à Buddy. Les cartes sont dans `cards/cartes-buddy.pdf` (A4, charte Epitech), à imprimer et si possible à plastifier. On les régénère avec `python3 cards/generate_cards.py` (OpenCV `cv2.aruco`, pymupdf, fontTools + brotli pour les polices).

| ID ArUco (`DICT_4X4_50`) | Carte |
|---|---|
| 20 | JOUER : Profil tech |
| 21 | JOUER : Quiz Vrai ou Faux |
| 0, 1, 2, 3 | A, B, C, D (réponses du Profil tech) |
| 10, 11 | VRAI, FAUX (réponses du Quiz) |

- **Profil tech** : 4 questions, réponses avec A/B/C/D. Le résultat est l'un de 4 profils (Cybersécurité, IA et Data, Dev et Création, Robotique et IoT), chacun relié aux formations Epitech correspondantes.
- **Quiz Vrai ou Faux** : 5 affirmations tirées au hasard parmi 39 (Epitech et culture tech), avec toujours au moins une vraie et une fausse, avec la bonne réponse et une explication après chaque carte. Le score final est comparé au record du jour, qui est gardé en mémoire et remis à zéro quand l'app redémarre.
- **Pendant une partie** : la conversation vocale est en pause, et l'écran affiche la question, les cartes possibles et le résultat (champ `game` du message WebSocket).
- **Fin de partie** : sans réponse au bout de 25 s, Buddy abandonne la partie et reprend la conversation.
- **Une carte levée ne compte qu'une fois** : il faut la retirer environ 1 s avant de pouvoir la rejouer. Sinon, une carte restée levée répondrait aussi à la question suivante.

Le contenu des jeux (questions, profils, affirmations du quiz) est dans `MarkerGames.kt`. Les infos Epitech qu'il contient reprennent celles, vérifiées, du prompt : n'y ajouter que des faits vérifiés sur epitech.eu.

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
