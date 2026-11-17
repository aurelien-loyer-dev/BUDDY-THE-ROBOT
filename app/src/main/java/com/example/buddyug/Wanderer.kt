package com.example.buddyug

import android.util.Log
import com.bfr.buddy.usb.shared.IUsbCommadRsp
import com.bfr.buddy.vision.shared.IVisionRsp
import com.bfr.buddy.vision.shared.enums.TrackingMode
import com.bfr.buddysdk.BuddySDK
import com.bfr.buddysdk.services.ModuleUSB
import com.bfr.buddysdk.services.companion.FollowMeMode
import com.bfr.buddysdk.services.companion.Task
import com.bfr.buddysdk.services.companion.TaskCallback
import java.text.Normalizer
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.random.Random

/**
 * Mode balade : Buddy se promène comme un petit humain, on peut lui parler et
 * lui demander des figures.
 *
 * - BALADE     : avance et tourne au hasard (code maison), s'arrête à OBSTACLE_MM
 *                des obstacles, évite les personnes vues de près à la caméra ;
 * - SUIVI      : « suis-moi » (code maison) : se tourne vers la personne vue à la
 *                caméra et la suit, jusqu'à ~1 m d'elle ;
 * - DISCUSSION : tâche Companion « watchMe » de BuddyOS : se tourne vers la
 *                personne qui parle (rotation seule) ;
 * - ARRET      : immobile.
 *
 * Pourquoi pas les tâches Companion « randomStroll » / « followMe » pour avancer :
 * sur notre Buddy, le capteur ToF avant droit est défectueux (faux obstacle à
 * 20-40 mm en permanence, même nettoyé). L'évitement d'obstacles de BuddyOS le
 * croit et tourne sans jamais avancer. Notre code, lui, ignore ce faux écho.
 *
 * Les figures (tour, gauche, droite, demi-tour, avance, recule, danse, oui, non)
 * sont exécutées sur les moteurs, puis Buddy reprend ce qu'il faisait.
 * Commande de secours : toucher le haut de la tête = marche / arrêt. Démarre à l'ARRÊT.
 * Unités SDK : moveBuddy(vitesse m/s, distance m), rotateBuddy(vitesse °/s, angle °), capteurs en mm.
 */
class Wanderer(private val onModeChanged: (Mode) -> Unit = {}) {

    enum class Mode { ARRET, BALADE, DISCUSSION, SUIVI }

    /** Tout ce que Buddy sait faire à la voix. Le nom sert de balise pour l'IA : [TOUR], [DANSE]… */
    enum class Action { SUIS_MOI, STOP, BALADE, REGARDE_MOI, TOUR, GAUCHE, DROITE, DEMI_TOUR, AVANCE, RECULE, DANSE, OUI, NON }

    @Volatile var mode = Mode.ARRET
        private set
    @Volatile private var running = false
    @Volatile private var lastHeardAt = 0L
    @Volatile private var lastPersonAt = 0L
    @Volatile private var busyWithFigure = false
    /** « Stop » pendant une figure : ses mouvements restants sont annulés. */
    @Volatile private var figureCancelled = false
    @Volatile private var paused = false
    /** « Stop » à la voix : pause courte, puis la balade reprend toute seule (0 = pas de reprise). */
    @Volatile private var resumeBaladeAt = 0L

    /** Balade ou suivi qui peut rouler maintenant. */
    private fun canRoll() = !paused && !busyWithFigure && (mode == Mode.BALADE || mode == Mode.SUIVI)

    fun start() {
        if (running) return
        running = true
        BuddySDK.Vision.startCamera(object : IVisionRsp.Stub() {
            override fun onSuccess(p0: String?) { try { BuddySDK.Vision.stopTracking() } catch (e: Exception) {} }
            override fun onFailed(p0: String?) { Log.e(TAG, "startCamera (balade) : $p0") }
        })
        Thread({ straightenHead() }, "wander-head").start()
        Thread({ headTouchLoop() }, "wander-touch").start()
        Thread({ motorLoop() }, "wander").start()
        Thread({ watchdogLoop() }, "wander-watchdog").start()
        Thread({ followVisionLoop() }, "wander-vision").start()
    }

    fun stop() {
        running = false
        setMode(Mode.ARRET)
    }

    /**
     * App en pause (BuddyOS passe devant quelques secondes, ce qui arrive souvent) :
     * moteurs coupés, mais le mode est gardé et repris par [resume].
     */
    fun pause() {
        if (paused) return
        paused = true
        Log.i(TAG, "App en pause : moteurs coupés, mode $mode gardé")
        driving = false
        emergencyStop()
        tasks.execute { applyTask() }
    }

    fun resume() {
        if (!paused) return
        paused = false
        Log.i(TAG, "App au premier plan : reprise du mode $mode")
        onSdkReady()
        tasks.execute { applyTask() }
    }

    /** À rappeler à chaque onSDKReady : le Companion peut éteindre le module capteurs à la reprise. */
    fun onSdkReady() {
        try { BuddySDK.USB.enableSensorModule(true, rsp("enableSensorModule")) } catch (e: Exception) { Log.w(TAG, "enableSensorModule", e) }
    }

    /** Change de mode. Ne bloque jamais l'appelant (fil de conversation, capteur de tête…). */
    private fun setMode(m: Mode) {
        synchronized(modeLock) {
            if (mode == m) return
            mode = m
        }
        Log.i(TAG, "Balade : mode $m")
        if (m == Mode.SUIVI) {
            lastPersonAt = System.currentTimeMillis(); target = null; seen = null
            lastFollowLinear = 0f; lastFollowAngular = 0f; offsetTrend = 0f; lastTrendSample = null
        }
        // watchMe (DISCUSSION) coupe parfois le module capteurs : on le rallume avant de rouler.
        if (m == Mode.BALADE || m == Mode.SUIVI) {
            onSdkReady()
            // Tête droite pour rouler (watchMe l'a souvent tournée vers quelqu'un) ;
            // on attend que watchMe soit arrêté pour qu'il ne la rebouge pas.
            if (headMayBeOff) Thread({
                Thread.sleep(800)
                if (mode == m && !busyWithFigure && headMayBeOff) straightenHead()
            }, "wander-head").start()
        }
        if (m == Mode.ARRET) figureCancelled = true // « stop » interrompt aussi une figure en cours (la danse…)
        if (m != Mode.BALADE && m != Mode.SUIVI) emergencyStop()
        tasks.execute { applyTask() }
        onModeChanged(m)
    }

    // --- Tâche Companion « watchMe » (mode DISCUSSION) ---
    //
    // Tout passe par ce fil unique, sans verrou tenu pendant un appel au Companion
    // (stop() peut attendre un rappel : un verrou partagé provoquait un interblocage).

    private val modeLock = Any()
    private val tasks = Executors.newSingleThreadExecutor { r -> Thread(r, "wander-tasks") }
    @Volatile private var watchTask: Task? = null

    private fun applyTask() {
        val wanted = mode == Mode.DISCUSSION && !busyWithFigure && !paused
        if (wanted == (watchTask != null)) return
        if (!wanted) { stopWatchTask(); return }
        val task = try { BuddySDK.Companion.createFollowMeTask(FollowMeMode.WATCH_ME) } catch (e: Exception) {
            Log.e(TAG, "Création de watchMe impossible", e); return
        }
        watchTask = task
        headMayBeOff = true // watchMe tourne la tête vers la personne
        Log.i(TAG, "Tâche Companion lancée : watchMe")
        try {
            task.start(object : TaskCallback {
                override fun onStarted() {}
                override fun onSuccess(p0: String) = watchEnded(task, "terminée")
                override fun onCancel() = watchEnded(task, "annulée")
                override fun onError(p0: String) = watchEnded(task, "erreur : $p0")
            })
        } catch (e: Exception) {
            Log.e(TAG, "Démarrage de watchMe impossible", e)
            watchTask = null
        }
    }

    private fun watchEnded(task: Task, how: String) {
        if (watchTask !== task) return
        Log.i(TAG, "watchMe $how")
        watchTask = null
    }

    private fun stopWatchTask() {
        val t = watchTask ?: return
        watchTask = null
        Thread({
            try { t.stop() } catch (e: Exception) { Log.w(TAG, "Arrêt de watchMe", e) }
            // L'arrêt de watchMe laisse souvent le module capteurs coupé (65535 / -1),
            // et BuddyOS recharge son mot d'éveil « Ok Buddy ».
            Thread.sleep(500)
            onSdkReady()
            unloadWakeWord()
        }, "wander-stop").start()
        emergencyStop()
    }

    // --- Boucle moteurs ---

    private fun motorLoop() {
        while (running) {
            try {
                val now = System.currentTimeMillis()
                if (paused) { Thread.sleep(200); continue }
                if (canRoll() && cliffConfirmed()) { recoverFromCliff(); continue }
                if (mode != Mode.ARRET && batteryLow()) {
                    Log.w(TAG, "Batterie faible : arrêt de la balade")
                    resumeBaladeAt = 0L
                    setMode(Mode.ARRET)
                }
                if (mode == Mode.DISCUSSION && now - lastHeardAt > TALK_HOLD_MS) setMode(Mode.BALADE)
                if (mode == Mode.ARRET && resumeBaladeAt in 1..now) {
                    resumeBaladeAt = 0L
                    if (batteryLow()) Log.w(TAG, "Batterie faible : la balade ne reprend pas")
                    else { Log.i(TAG, "Fin de la pause demandée à la voix : la balade reprend"); setMode(Mode.BALADE) }
                }
                when {
                    busyWithFigure -> Thread.sleep(200)
                    mode == Mode.BALADE -> wanderStep()
                    mode == Mode.SUIVI -> followStep()
                    else -> {
                        if (driving) halt("mode $mode")
                        frontReadings()
                        // Même à l'arrêt : capteurs toujours prêts pour « avance » ou la reprise.
                        if (!sensorsAlive()) healSensors()
                        // À l'arrêt, tête droite aussi (au plus un essai toutes les 5 s).
                        if (mode == Mode.ARRET && headMayBeOff && now - lastStraightenAt > 5_000) straightenHead()
                        Thread.sleep(200)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Erreur balade", e)
                emergencyStop()
                Thread.sleep(500)
            }
        }
        emergencyStop()
        tasks.execute { stopWatchTask() }
    }

    // --- Pilotage continu en vitesse ---
    //
    // setBuddySpeed(m/s, rad/s) reste actif jusqu'à la commande suivante : la boucle
    // le renvoie toutes les CONTROL_MS, et un chien de garde coupe les moteurs si
    // elle cesse de le faire (fil bloqué, exception…).

    @Volatile private var lastDriveAt = 0L
    @Volatile private var driving = false

    private fun drive(linear: Float, angularRad: Float) {
        if (!driving) enableWheels(true)
        driving = linear != 0f || angularRad != 0f
        lastDriveAt = System.currentTimeMillis()
        BuddySDK.USB.setBuddySpeed(linear, angularRad, ModuleUSB.BuddyAccelerations.NORMAL, driveRsp)
    }

    /** Refus consécutifs de setBuddySpeed (roues bloquées ou moteurs occupés). */
    @Volatile private var driveRefusals = 0
    private val driveRsp = object : IUsbCommadRsp.Stub() {
        override fun onSuccess(p0: String?) { driveRefusals = 0 }
        override fun onFailed(p0: String?) {
            if (p0?.contains("Cancelled") == true) return
            if (++driveRefusals == 1) Log.w(TAG, "setBuddySpeed refusé : $p0")
        }
    }

    /** Roues qui refusent d'avancer depuis ~0,5 s : on arrête, on les réactive, et on repart ailleurs. */
    private fun wheelsStuck(): Boolean {
        if (driveRefusals < 5) return false
        Log.w(TAG, "Roues bloquées ($driveRefusals refus de suite) : arrêt et réactivation")
        driveRefusals = 0
        halt("roues bloquées")
        enableWheels(true)
        Thread.sleep(500)
        return true
    }

    private fun halt(reason: String) {
        if (driving) Log.i(TAG, "Arrêt : $reason")
        driving = false
        emergencyStop()
    }

    /**
     * Décharge le mot d'éveil « Ok Buddy » de BuddyOS. S'il se déclenche (souvent à tort,
     * sur une phrase contenant « Buddy »), BuddyOS coupe notre app et lance sa propre mission
     * « écoute + regarde la personne » : tête tournée, balade interrompue (constaté le 2026-11-17).
     * BuddyOS le recharge après ses tâches : on le décharge après chaque watchMe et toutes les 20 s.
     */
    private fun unloadWakeWord() {
        try { BuddySDK.Speech.unloadVoCon() } catch (e: Exception) { Log.w(TAG, "unloadVoCon", e) }
    }

    private fun watchdogLoop() {
        var lastUnload = 0L
        while (running) {
            if (System.currentTimeMillis() - lastUnload > WAKE_WORD_UNLOAD_MS) {
                lastUnload = System.currentTimeMillis()
                unloadWakeWord()
            }
            if (driving && System.currentTimeMillis() - lastDriveAt > WATCHDOG_MS) {
                Log.w(TAG, "Chien de garde : plus de commande depuis ${WATCHDOG_MS} ms, arrêt des moteurs")
                driving = false
                emergencyStop()
            }
            Thread.sleep(100)
        }
    }

    // --- Balade : longues lignes droites pour parcourir les couloirs ---

    private fun wanderStep() {
        frontReadings()
        if (!sensorsAlive()) {
            halt("module capteurs coupé")
            healSensors()
            Thread.sleep(500)
            return
        }
        if (!pathClear()) {
            halt("obstacle")
            turnAway()
            return
        }
        if (headMayBeOff) straightenHead()
        // Une ligne droite de WANDER_MIN_M à WANDER_MAX_M, interrompue au moindre danger.
        val length = WANDER_MIN_M + Random.nextFloat() * (WANDER_MAX_M - WANDER_MIN_M)
        val until = System.currentTimeMillis() + (length / WANDER_SPEED_MS * 1000).toLong()
        Log.i(TAG, "Balade : ligne droite de ${"%.1f".format(length)} m")
        var lastPersonCheck = 0L
        while (mode == Mode.BALADE && canRoll() && System.currentTimeMillis() < until) {
            val reason = dangerAhead()
            // Vide : la boucle moteurs s'en charge (recul puis demi-tour).
            if (reason != null) { halt(reason); if (reason != "vide") turnAway(); return }
            if (wheelsStuck()) { turnAway(); return }
            // Personne proche juste devant (les capteurs voient mal les vêtements) : on l'évite.
            if (System.currentTimeMillis() - lastPersonCheck > PERSON_CHECK_MS) {
                lastPersonCheck = System.currentTimeMillis()
                val p = person(lockOn = false)
                if (p != null && p.width >= PERSON_NEAR_WIDTH && abs(p.offset) < 0.25f) {
                    halt("personne devant"); turnAway(); return
                }
            }
            // Ralentit à l'approche d'un obstacle : il s'arrête net au lieu de glisser dedans.
            drive(if (nearestFront() < SLOW_MM) SLOW_SPEED_MS else WANDER_SPEED_MS, 0f)
            Thread.sleep(CONTROL_MS)
        }
        halt("fin de ligne droite")
        // De temps en temps, un petit changement de cap pour varier le trajet.
        if (mode == Mode.BALADE && Random.nextFloat() < 0.5f) rotate((Random.nextFloat() * 40f + 20f) * randomSign()) { mode == Mode.BALADE && canRoll() }
    }

    /**
     * Vide détecté (bord de marche, ou robot soulevé) : on ne s'arrête pas pour de bon.
     * Recul de 20 cm, attente que le sol soit revenu sous les roues, demi-tour, et la balade continue.
     */
    private fun recoverFromCliff() {
        halt("vide")
        Log.w(TAG, "Vide détecté (capteurs ${cliffMask()}) : recul${if (mode == Mode.SUIVI) ", puis le suivi reprend" else " puis demi-tour"}")
        if (backClear()) move(-CLIFF_BACK_M, checkCliff = false) { canRoll() }
        var clearSince = 0L
        while (running && canRoll()) {
            val now = System.currentTimeMillis()
            if (cliffAlarm()) clearSince = 0L else if (clearSince == 0L) clearSince = now
            if (clearSince != 0L && now - clearSince > CLIFF_CLEAR_MS) break
            Thread.sleep(200)
        }
        if (!canRoll()) return
        // En suivi, on continue de suivre (la personne est passée par là) : avant, un faux
        // « vide » le renvoyait en balade en plein suivi (constaté le 2026-11-17).
        if (mode == Mode.SUIVI) { lastPersonAt = System.currentTimeMillis(); return }
        rotate((Random.nextFloat() * 40f + 140f) * randomSign()) { canRoll() }
    }

    /**
     * Obstacle devant : petit recul s'il est collé, puis virage progressif du côté le
     * plus dégagé, par pas de TURN_STEP_DEG, jusqu'à ce que la voie soit libre, plus
     * une marge pour ne pas frôler l'obstacle. Résultat : des virages de 45 à 90° en
     * général, un demi-tour seulement s'il est coincé (un angle fixe de 100 à 170° lui
     * faisait faire des allers-retours).
     */
    private fun turnAway() {
        val r = frontReadings()
        // Uniquement en balade : un « suis-moi » pendant le virage l'interrompt aussitôt
        // (sinon il finissait de tourner et tournait le dos à la personne).
        val keepGoing = { mode == Mode.BALADE && canRoll() }
        if (nearestFront() < BACK_OFF_IF_MM && backClear()) move(-BACK_OFF_M, keepGoing = keepGoing)
        // Côté gauche = ToF gauche + US gauche ; côté droit = US droit (ToF droit peu fiable).
        val left = listOf(r[0], r[3]).filter { it > 0 }.minOrNull() ?: Int.MAX_VALUE
        val right = listOf(r[2], r[4]).filter { it > 0 }.minOrNull() ?: Int.MAX_VALUE
        val sign = when { left > right -> 1f; right > left -> -1f; else -> randomSign() }
        var turned = 0f
        var clear = false
        while (turned < 330f && keepGoing()) {
            rotate(TURN_STEP_DEG * sign, keepGoing)
            turned += TURN_STEP_DEG
            forgetSticky() // l'obstacle mémorisé n'est plus devant après le virage
            Thread.sleep(TURN_SETTLE_MS)
            frontReadings()
            // Idéal : 60 cm de libre. Dans un coin étroit, au-delà de 90° on se contente de 30 cm
            // (sinon il tournait sur lui-même, jusqu'à 375°).
            clear = pathClear() && (nearestFront() >= SLOW_MM || turned >= 90f)
            if (clear) break
        }
        if (!keepGoing()) { Log.i(TAG, "Virage interrompu (mode $mode)"); return }
        if (clear) rotate(TURN_MARGIN_DEG * sign, keepGoing)
        Log.i(TAG, "Obstacle (${r.joinToString("/")} mm) : virage de ${((turned + if (clear) TURN_MARGIN_DEG else 0f) * sign).toInt()}°${if (clear) "" else " (coincé : il réessaiera)"}")
    }

    /** Obstacle connu le plus proche devant (mm), ou Int.MAX_VALUE si rien à portée. */
    private fun nearestFront(): Int = lastFront.filter { it > 0 }.minOrNull() ?: Int.MAX_VALUE

    /** Raison de s'arrêter immédiatement, ou null si la voie est libre. */
    private fun dangerAhead(): String? {
        val r = frontReadings()
        val known = r.filter { it > 0 }
        return when {
            !sensorsAlive() -> { healSensors(); "module capteurs coupé" }
            cliffAlarm() -> "vide"
            known.isNotEmpty() && known.min() < OBSTACLE_MM -> "obstacle à ${known.min()} mm"
            else -> null
        }
    }

    // --- Suivi : verrouillage sur une personne, pilotage continu ---

    @Volatile private var target: Person? = null

    // La caméra tourne dans son propre fil : une détection lente ne fige plus la boucle
    // de pilotage (avant : 8 s sans commande, puis « personne perdue » et retour à la balade).
    @Volatile private var seen: Person? = null
    @Volatile private var seenAt = 0L
    @Volatile private var faceTooSlow = false
    @Volatile private var offsetTrend = 0f
    @Volatile private var lastTrendSample: Person? = null
    @Volatile private var lastFollowLinear = 0f
    @Volatile private var lastFollowAngular = 0f
    @Volatile private var faceErrorLogged = false

    /**
     * Vision du suivi, deux sources qui s'alternent (jamais en même temps : pendant que le
     * pisteur tourne, la détection ne trouve rien — constaté le 2026-11-17) :
     * - PISTEUR (startTracking/getTracking) : suit le VISAGE à ~20 images/s, très stable tant
     *   que la personne regarde Buddy. Il décroche quand elle se retourne pour marcher.
     * - DÉTECTION du corps (detectPerson, ~5 images/s) : voit aussi les gens de dos.
     * On commence au pisteur (la personne vient de parler à Buddy, elle lui fait face) ;
     * pisteur décroché 1 s → détection ; détection sans résultat depuis 2 s (et essayée au
     * moins 3 s) → retour au pisteur.
     */
    private fun followVisionLoop() {
        var frames = 0; var tracked = 0; var detected = 0; var slowest = 0L; var statsSince = System.currentTimeMillis()
        var useTracker = true; var tracking = false; var sourceSince = 0L; var trackLostSince = 0L; var lastDetectHit = 0L
        var rawLogged = false; var trackUnusable = false
        while (running) {
            if (mode != Mode.SUIVI || paused || busyWithFigure) {
                if (tracking) { stopVisionTracking(); tracking = false }
                if (mode != Mode.SUIVI) { seen = null; useTracker = true }
                frames = 0; tracked = 0; detected = 0; slowest = 0L; sourceSince = 0L
                Thread.sleep(150); continue
            }
            val t0 = System.currentTimeMillis()
            if (sourceSince == 0L) sourceSince = t0
            var p: Person? = null
            if (useTracker && !trackUnusable) {
                if (!tracking) {
                    tracking = try {
                        BuddySDK.Vision.startTracking(TRACK_THRESHOLD, TrackingMode.FAST)
                        trackLostSince = 0L; true
                    } catch (e: Exception) { cameraFailed(e); Log.w(TAG, "Pisteur d'image indisponible : ${e.message}"); false }
                }
                val tr = if (tracking) try { BuddySDK.Vision.getTracking() } catch (e: Exception) { null } else null
                if (tr != null && tr.isTrackingSuccessfull) {
                    if (!rawLogged) { rawLogged = true; Log.i(TAG, "Pisteur : gauche=${tr.leftPos} droite=${tr.rightPos} haut=${tr.topPos} bas=${tr.bottomPos}") }
                    if (tr.rightPos > tr.leftPos && tr.rightPos <= 1.01f) {
                        // Le pisteur suit un visage : distance estimée à l'échelle d'un visage.
                        p = Person(-((tr.leftPos + tr.rightPos) / 2f - 0.5f), tr.rightPos - tr.leftPos, face = true)
                        target = p; trackLostSince = 0L; tracked++
                    } else {
                        trackUnusable = true
                        Log.w(TAG, "Pisteur : coordonnées inattendues, suivi par détection seulement")
                    }
                } else {
                    if (trackLostSince == 0L) trackLostSince = t0
                    if (t0 - trackLostSince > TRACK_GIVE_UP_MS || trackUnusable || !tracking) {
                        // Visage perdu (la personne s'est sûrement retournée) : détection du corps.
                        if (tracking) stopVisionTracking()
                        tracking = false; useTracker = false; sourceSince = t0; lastDetectHit = t0
                        Log.i(TAG, "Suivi : visage perdu, je cherche le corps")
                    }
                }
            } else {
                p = try { person(lockOn = true) } catch (e: Exception) { Log.w(TAG, "Vision suivi", e); null }
                if (p != null) { detected++; lastDetectHit = t0 }
                // Plus rien depuis 2 s (et la détection a eu au moins 3 s) : retour au pisteur.
                if (!trackUnusable && t0 - sourceSince > DETECT_MIN_MS && t0 - lastDetectHit > DETECT_GIVE_UP_MS) {
                    useTracker = true; sourceSince = t0
                    Log.i(TAG, "Suivi : corps perdu, je cherche le visage")
                }
            }
            val now = System.currentTimeMillis()
            frames++; slowest = maxOf(slowest, now - t0)
            if (p != null) { seen = p; seenAt = now }
            // Bilan toutes les 2 s : source, cadence, et la dernière taille vue (pour régler la distance).
            if (now - statsSince > 2_000) {
                val last = seen?.let { " ; dernier ${if (it.face) "visage" else "corps"} décalage=${"%.2f".format(it.offset)} largeur=${"%.2f".format(it.width)}" } ?: ""
                Log.i(TAG, "Vision suivi : $frames image(s) : $tracked par le pisteur, $detected par détection, plus lente ${slowest} ms$last")
                frames = 0; tracked = 0; detected = 0; slowest = 0L; statsSince = now
            }
            Thread.sleep(if (useTracker) 50 else 30)
        }
    }

    private fun stopVisionTracking() {
        try { BuddySDK.Vision.stopTracking() } catch (e: Exception) {}
    }

    private fun followStep() {
        val now = System.currentTimeMillis()
        // Dernière détection, si elle est fraîche (la détection prend ~200 ms par image).
        val p = seen?.takeIf { now - seenAt < FOLLOW_FRESH_MS }
        if (p == null) {
            val lostFor = now - lastPersonAt
            // « Suis-moi » ne s'arrête jamais tout seul : seuls « stop », « va te promener »
            // ou le toucher de tête le font sortir du suivi. Perdue trop longtemps : on lâche
            // le verrouillage (la première personne revue sera suivie) et on cherche sans fin.
            if (target != null && lostFor > FOLLOW_LOST_MS) {
                Log.i(TAG, "Suivi : personne perdue depuis ${lostFor / 1000} s, je la cherche (sans limite)")
                target = null
            }
            when {
                // Personne en vue (jamais vue, ou perdue depuis longtemps) : cycle sans fin d'un
                // tour lent sur lui-même (~14 s) puis d'une pause face à la dernière direction (~6 s).
                target == null -> when {
                    lostFor < FOLLOW_STOP_MS -> drive(0f, 0f)
                    cliffAlarm() -> halt("vide")
                    (lostFor / 1000) % SEARCH_CYCLE_S < SEARCH_TURN_S -> drive(0f, FIRST_LOOK_TURN_RAD)
                    else -> drive(0f, 0f)
                }
                lostFor >= FOLLOW_STOP_MS -> {
                    // Perdue de vue : on la cherche tout de suite du côté où elle partait (position
                    // + tendance du mouvement), ~100°, puis de l'autre côté. De près, un pas de
                    // côté suffit à sortir de l'image (constaté le 2026-11-17).
                    val guess = (target?.offset ?: 0f) + offsetTrend * 4f
                    val side = if (guess >= 0f) 1f else -1f
                    val dir = if (lostFor - FOLLOW_STOP_MS < SEARCH_FIRST_SIDE_MS) side else -side
                    if (cliffAlarm()) halt("vide") else drive(0f, dir * SEARCH_TURN_RAD)
                }
                // Quelques images ratées (fréquent quand la personne est loin) : on garde l'élan,
                // en ralentissant, tant que la voie est libre.
                else -> drive(if (dangerAhead() == null) lastFollowLinear * 0.7f else 0f, lastFollowAngular * 0.5f)
            }
            Thread.sleep(CONTROL_MS)
            return
        }
        lastPersonAt = now
        // Tendance du décalage (vers où la personne se déplace dans l'image), lissée.
        if (p !== lastTrendSample) {
            offsetTrend = 0.6f * offsetTrend + 0.4f * (p.offset - (lastTrendSample?.offset ?: p.offset))
            lastTrendSample = p
        }
        // Rotation proportionnelle au décalage : fluide, sans à-coups.
        val angular = (p.offset * FOLLOW_TURN_GAIN).coerceIn(-FOLLOW_MAX_TURN_RAD, FOLLOW_MAX_TURN_RAD)
        // Avance tant que la personne paraît loin (petite dans l'image), ralentit en approchant,
        // et d'autant moins vite qu'il faut tourner.
        var linear = if (p.face) ((FACE_CLOSE_WIDTH - p.width) * FACE_SPEED_GAIN).coerceIn(0f, FOLLOW_MAX_SPEED_MS)
                     else ((PERSON_CLOSE_WIDTH - p.width) * FOLLOW_SPEED_GAIN).coerceIn(0f, FOLLOW_MAX_SPEED_MS)
        linear *= (1f - (abs(p.offset) * 1.5f).coerceAtMost(1f))
        val danger = dangerAhead()
        if (danger != null) {
            if (danger == "vide") { halt("vide"); return } // la boucle moteurs gère le recul
            linear = 0f // obstacle : on peut encore tourner, pas avancer
        } else if (nearestFront() < SLOW_MM) {
            linear = linear.coerceAtMost(SLOW_SPEED_MS)
        }
        lastFollowLinear = linear
        lastFollowAngular = angular
        drive(linear, angular)
        Thread.sleep(CONTROL_MS)
    }

    private fun rotate(angleDeg: Float, keepGoing: () -> Boolean = { !figureCancelled }) {
        if (!keepGoing()) return
        enableWheels(true)
        val done = Waitable()
        BuddySDK.USB.rotateBuddy(TURN_SPEED_DEG, angleDeg, done.rsp)
        val deadline = System.currentTimeMillis() + ((abs(angleDeg) / TURN_SPEED_DEG) * 1000).toLong() + 3000
        while (!done.finished && System.currentTimeMillis() < deadline) {
            if (!keepGoing()) { emergencyStop(); return }
            Thread.sleep(POLL_MS)
        }
        if (done.failed) Thread.sleep(1000)
    }

    // --- Voix ---

    /** Buddy vient de finir de répondre : la discussion compte à partir de maintenant. */
    fun onSpoke() { if (mode == Mode.DISCUSSION) lastHeardAt = System.currentTimeMillis() }

    /** Quelqu'un parle à Buddy : il arrête de se promener et se tourne vers la personne. */
    fun onHeard() {
        lastHeardAt = System.currentTimeMillis()
        if (mode == Mode.BALADE) setMode(Mode.DISCUSSION)
    }

    /**
     * Reconnaît une commande évidente dans une phrase COURTE. Pour le reste, l'IA
     * décide et ajoute une balise [ACTION] à sa réponse (voir [extractTag]).
     */
    fun command(heard: String): Action? {
        val t = normalize(heard)
        if (t.split(" ").size > MAX_COMMAND_WORDS) return null
        // Phrase sans ses politesses de tête : « Buddy, tu peux tourner ? » → « tourner ».
        var core = t
        do {
            val before = core
            FILLERS.forEach { f -> if (core == f) core = "" else if (core.startsWith("$f ")) core = core.removePrefix("$f ") }
        } while (core != before)
        val padded = " $t "
        fun has(vararg w: String) = w.any { padded.contains(" $it ") }          // n'importe où
        fun starts(vararg w: String) = w.any { core == it || core.startsWith("$it ") } // en tête (impératif)
        return when {
            has("stop", "arrete toi", "ne bouge plus", "reste la", "arrete de me suivre") || starts("arrete", "pause", "stop") -> Action.STOP
            has("suis moi", "suit moi", "suivez moi", "suis nous", "viens avec moi", "follow me") || starts("suivre moi", "me suivre") -> Action.SUIS_MOI
            has("regarde moi", "tourne toi vers moi", "regarde par ici") -> Action.REGARDE_MOI
            has("demi tour", "retourne toi") -> Action.DEMI_TOUR
            has("gauche") && starts("tourne", "tourner", "va", "aller", "pivote", "pivoter", "a gauche", "gauche") -> Action.GAUCHE
            has("droite") && starts("tourne", "tourner", "va", "aller", "pivote", "pivoter", "a droite", "droite") -> Action.DROITE
            has("fais une danse", "une petite danse", "fais nous une danse") || starts("danse", "danser") -> Action.DANSE
            has("tourne sur toi", "tourner sur toi", "pirouette", "toupie") || starts("tourne", "tourner", "fais un tour", "faire un tour sur") -> Action.TOUR
            has("en arriere", "pousse toi") || starts("recule", "reculer") -> Action.RECULE
            has("viens ici", "viens vers moi", "approche toi", "rapproche toi", "en avant") || starts("avance", "avancer", "viens") -> Action.AVANCE
            has("fais oui", "hoche la tete") -> Action.OUI
            has("fais non", "secoue la tete") -> Action.NON
            has("va te promener", "promene toi", "balade toi", "va faire un tour", "va te balader") || starts("balade", "promenade") -> Action.BALADE
            else -> null
        }
    }

    /** Exécute une action ; renvoie une courte phrase à dire. */
    fun handle(a: Action): String = when (a) {
        // De trop près, la caméra ne voit pas la personne en entier : on lui demande de s'éloigner un peu.
        Action.SUIS_MOI -> if (batteryLow()) LOW_BATTERY_PHRASE else { setMode(Mode.SUIVI); "D'accord, je te suis ! Mets-toi à deux pas devant moi, que je te voie bien en entier." }
        Action.STOP -> {
            figureCancelled = true // même déjà à l'arrêt : une figure en cours s'interrompt
            setMode(Mode.ARRET)
            resumeBaladeAt = System.currentTimeMillis() + VOICE_STOP_MS
            "OK, je reste là une petite minute, puis je repars me promener."
        }
        Action.BALADE -> if (batteryLow()) LOW_BATTERY_PHRASE else { setMode(Mode.BALADE); "C'est parti, je vais faire un tour !" }
        Action.AVANCE -> when {
            !sensorsReady() -> "Mes capteurs se réveillent, redis-moi « avance » dans deux secondes !"
            !pathClear() -> "Je ne peux pas avancer, il y a quelque chose juste devant moi !"
            else -> { figure(a); "J'avance !" }
        }
        Action.RECULE -> when {
            !sensorsReady() -> "Mes capteurs se réveillent, redis-moi « recule » dans deux secondes !"
            !backClear() -> "Je ne peux pas reculer, il y a quelque chose derrière moi !"
            else -> { figure(a); "Je recule !" }
        }
        Action.REGARDE_MOI -> { lastHeardAt = System.currentTimeMillis(); setMode(Mode.DISCUSSION); "Je te regarde !" }
        // Il chante pendant qu'il danse : la phrase est dite pendant que la figure s'exécute.
        Action.DANSE -> { figure(a); DANCE_SONG }
        else -> { figure(a); listOf("Regarde ça !", "Et hop !", "C'est parti !", "Attention, spectacle !")[a.ordinal % 4] }
    }

    /** Si l'IA a mis une balise [ACTION] dans sa réponse : l'action, et le texte sans la balise. */
    fun extractTag(response: String): Pair<Action?, String> {
        val m = Regex("""\[\s*([A-Z_]+)\s*]""").find(response) ?: return null to response
        val action = Action.values().firstOrNull { it.name == m.groupValues[1] }
        return action to response.removeRange(m.range).trim()
    }

    /** Une figure : on suspend ce qui tourne, on l'exécute, puis on reprend. */
    private fun figure(a: Action) {
        Thread({
            busyWithFigure = true
            figureCancelled = false
            tasks.submit { stopWatchTask() }.get()
            // Laisse la boucle moteurs lâcher la main : son arrêt annulait le début de la figure.
            Thread.sleep(300)
            emergencyStop()
            onSdkReady()
            Thread.sleep(300)
            frontReadings()
            try {
                Log.i(TAG, "Figure : $a")
                when (a) {
                    Action.TOUR -> rotate(360f)
                    Action.GAUCHE -> rotate(90f)
                    Action.DROITE -> rotate(-90f)
                    Action.DEMI_TOUR -> rotate(180f)
                    Action.AVANCE -> if (pathClear()) move(FIGURE_FORWARD_M) else Log.i(TAG, "Avance refusée : obstacle devant")
                    Action.RECULE -> if (backClear()) move(-FIGURE_BACK_M) else Log.i(TAG, "Recule refusé : obstacle derrière")
                    Action.OUI -> head(yes = true)
                    Action.NON -> head(yes = false)
                    // ~12 s, le temps de la chanson (DANCE_SONG, dite en même temps par MainActivity).
                    Action.DANSE -> {
                        rotate(30f); rotate(-60f); rotate(30f)
                        head(yes = true)
                        rotate(-30f); rotate(60f); rotate(-30f)
                        rotate(360f)
                        head(yes = false)
                    }
                    else -> {}
                }
            } catch (e: Exception) {
                Log.e(TAG, "Figure $a", e)
            } finally {
                emergencyStop()
                straightenHead()
                // La figure ne compte pas comme « personne perdue » : le suivi reprend normalement.
                if (mode == Mode.SUIVI) lastPersonAt = System.currentTimeMillis()
                busyWithFigure = false
                // Après une figure, il reste face aux gens un moment avant de repartir.
                if (mode == Mode.BALADE) { lastHeardAt = System.currentTimeMillis(); setMode(Mode.DISCUSSION) }
                tasks.execute { applyTask() }
            }
        }, "wander-figure").start()
    }

    /**
     * Déplacement en ligne droite : distance > 0 en avant, < 0 en arrière (le SDK veut
     * une vitesse strictement positive, c'est la distance qui donne le sens).
     * Interrompu si un obstacle apparaît du côté où il roule.
     */
    private fun move(distance: Float, checkCliff: Boolean = true, keepGoing: () -> Boolean = { !figureCancelled }) {
        enableWheels(true)
        val done = Waitable()
        BuddySDK.USB.moveBuddy(FIGURE_SPEED_MS, distance, done.rsp)
        val deadline = System.currentTimeMillis() + ((abs(distance) / FIGURE_SPEED_MS) * 1000).toLong() + 3000
        while (!done.finished && System.currentTimeMillis() < deadline) {
            if (!keepGoing()) { emergencyStop(); return }
            if ((distance > 0 && !pathClear()) || (distance < 0 && !backClear()) || (checkCliff && cliffAlarm())) {
                Log.i(TAG, "Déplacement interrompu : obstacle")
                emergencyStop()
                return
            }
            Thread.sleep(POLL_MS)
        }
    }

    /**
     * Hoche (oui) ou secoue (non) la tête, puis la remet droite. buddySayYes/No vont à
     * un angle absolu : appelés une seule fois, ils laissaient la tête penchée.
     */
    private fun head(yes: Boolean) {
        if (yes) BuddySDK.USB.enableYesMove(true, rsp("enableYesMove")) else BuddySDK.USB.enableNoMove(true, rsp("enableNoMove"))
        repeat(2) {
            if (figureCancelled) return@repeat
            // La tête descend moins qu'elle ne monte : -10° vers le bas pour « oui ».
            for (angle in if (yes) floatArrayOf(HEAD_ANGLE_DEG, -HEAD_ANGLE_DEG / 2) else floatArrayOf(HEAD_ANGLE_DEG, -HEAD_ANGLE_DEG)) {
                val done = Waitable()
                if (yes) BuddySDK.USB.buddySayYes(HEAD_SPEED_DEG, angle, done.rsp)
                else BuddySDK.USB.buddySayNo(HEAD_SPEED_DEG, angle, done.rsp)
                done.await(3000)
            }
        }
        straightenHead()
    }

    /** Remet la tête droite et face à l'avant (après watchMe, une figure…). */
    /** Un seul redressement à la fois : deux en parallèle s'annulaient (« Cancelled »). */
    @Synchronized
    private fun straightenHead() {
        lastStraightenAt = System.currentTimeMillis()
        try {
            // Angle 0 explicite sur chaque axe, l'un après l'autre (envoyés ensemble, le second
            // annulait le premier). Pas buddySayNoStraight/YesStraight : sur notre Buddy, la tête
            // finissait tournée vers la droite après chaque appel (constaté le 2026-11-17).
            BuddySDK.USB.enableNoMove(true, rsp("enableNoMove"))
            val no = Waitable()
            BuddySDK.USB.buddySayNo(HEAD_SPEED_DEG, 0f, no.rsp)
            no.await(3000)
            BuddySDK.USB.enableYesMove(true, rsp("enableYesMove"))
            val yes = Waitable()
            BuddySDK.USB.buddySayYes(HEAD_SPEED_DEG, 0f, yes.rsp)
            yes.await(3000)
            val ok = no.finished && !no.failed && yes.finished && !yes.failed
            if (ok) headMayBeOff = false
            Log.i(TAG, "Tête redressée : ${if (ok) "OK" else "côté ${no.result ?: "sans réponse"}, haut-bas ${yes.result ?: "sans réponse"}"}")
        } catch (e: Exception) {
            Log.w(TAG, "Redressement de la tête", e)
        }
    }

    /** La tête a pu bouger (watchMe, figure, redressement raté) : à redresser avant de rouler. */
    @Volatile private var headMayBeOff = true
    @Volatile private var lastStraightenAt = 0L

    // --- Commande de secours : toucher le haut de la tête ---

    private fun headTouchLoop() {
        var wasTouched = false
        while (running) {
            val touched = try { BuddySDK.Sensors.HeadTouchSensors().Top().isTouched } catch (e: Exception) { false }
            // Arrêt d'urgence : celui-là est définitif, jusqu'au prochain toucher ou « va te promener ».
            if (touched && !wasTouched) { resumeBaladeAt = 0L; setMode(if (mode == Mode.ARRET) Mode.BALADE else Mode.ARRET) }
            wasTouched = touched
            Thread.sleep(100)
        }
    }

    // --- Vision ---

    /**
     * Personne vue : [offset] de -0.5 à +0.5 (signe = sens de rotation), [width] = largeur
     * dans l'image (0..1), [face] = vue par son visage (de près, le corps ne tient pas dans l'image).
     */
    private data class Person(val offset: Float, val width: Float, val face: Boolean = false)

    /**
     * Personne la plus probable dans l'image, ou null. Coordonnées normalisées 0..1.
     * Largeur ~0.28 vers 1,5 m, ~0.40 vers 1 m ; personne à droite de l'image →
     * angle négatif = tourner à droite (mesuré sur le robot le 2026-11-16).
     */
    private fun person(lockOn: Boolean): Person? {
        // Le corps entier d'abord ; de près (< 1,5 m environ) il ne tient plus dans
        // l'image : en suivi, on se rabat sur le visage.
        var face = false
        // Personne déjà verrouillée : seuil plus bas, pour ne pas la perdre quand elle est loin
        // (petite dans l'image, score plus faible ; le verrouillage écarte les fausses détections).
        val threshold = if (lockOn && target != null) FOLLOW_PERSON_THRESHOLD else PERSON_THRESHOLD
        var d = try { BuddySDK.Vision.detectPerson(threshold) } catch (e: Exception) { cameraFailed(e); null }
        if ((d == null || d.numOfDetections <= 0) && lockOn && !faceTooSlow) {
            val t0 = System.currentTimeMillis()
            d = try { BuddySDK.Vision.detectFace(FACE_THRESHOLD) } catch (e: Exception) {
                if (!cameraFailed(e) && !faceErrorLogged) { faceErrorLogged = true; Log.w(TAG, "detectFace indisponible : ${e.message}") }
                null
            }
            val took = System.currentTimeMillis() - t0
            // Mesuré le 2026-11-17 : jusqu'à 8 s sans réponse. Trop lent pour suivre : on s'en passe.
            if (took > FACE_MAX_MS) { faceTooSlow = true; Log.w(TAG, "detectFace trop lent ($took ms) : suivi au corps seulement") }
            face = true
        }
        if (d == null || d.numOfDetections <= 0) return null
        val found = d
        val people = (0 until found.numOfDetections).map { i ->
            Person(-((found.leftPos[i] + found.rightPos[i]) / 2f - 0.5f), found.rightPos[i] - found.leftPos[i], face)
        }
        val locked = target
        val chosen = if (lockOn && locked != null) {
            // Verrouillage : la personne la plus proche de la précédente position, si plausible
            // (la largeur ne se compare qu'entre deux détections du même type).
            people.minByOrNull { abs(it.offset - locked.offset) + if (it.face == locked.face) abs(it.width - locked.width) else 0f }
                ?.takeIf { abs(it.offset - locked.offset) < LOCK_MAX_JUMP } ?: return null
        } else {
            people.withIndex().maxByOrNull { found.score[it.index] }?.value ?: return null
        }
        if (lockOn) target = chosen
        Log.i(TAG, "${if (face) "Visage" else "Personne"} vu(e) : décalage=${"%.2f".format(chosen.offset)} largeur=${"%.2f".format(chosen.width)} (${people.size} vue(s))")
        return chosen
    }

    // --- Capteurs ---

    @Volatile private var lastAliveAt = 0L
    @Volatile private var lastRawLog = 0L
    @Volatile private var lastHealAt = 0L
    @Volatile private var lastFront = IntArray(5)

    /** Capteurs actifs ; sinon on les réveille et on laisse une seconde au module. */
    private fun sensorsReady(): Boolean {
        frontReadings()
        if (sensorsAlive()) return true
        lastHealAt = 0L
        healSensors()
        repeat(10) {
            Thread.sleep(150)
            frontReadings()
            if (sensorsAlive()) return true
        }
        return false
    }

    @Volatile private var cameraRestartAt = 0L

    /**
     * Caméra arrêtée (par une ancienne activité, BuddyOS…) : on la relance, au plus une
     * fois toutes les 3 s. Renvoie true si l'erreur venait bien de la caméra.
     */
    private fun cameraFailed(e: Exception): Boolean {
        if (e.message?.contains("CAMERA", ignoreCase = true) != true) return false
        val now = System.currentTimeMillis()
        if (now - cameraRestartAt < 3_000) return true
        cameraRestartAt = now
        Log.w(TAG, "Caméra arrêtée (${e.message}) : relance")
        try {
            BuddySDK.Vision.startCamera(object : IVisionRsp.Stub() {
                override fun onSuccess(p0: String?) { Log.i(TAG, "Caméra relancée"); try { BuddySDK.Vision.stopTracking() } catch (x: Exception) {} }
                override fun onFailed(p0: String?) { Log.w(TAG, "Relance caméra refusée : $p0") }
            })
        } catch (x: Exception) { Log.w(TAG, "Relance caméra", x) }
        return true
    }

    /** Oublie les mesures proches mémorisées (après un virage, elles ne sont plus devant). */
    private fun forgetSticky() {
        lastValid.fill(0)
        lastValidAt.fill(0L)
    }

    /** Module capteurs coupé par BuddyOS : on le rallume (au plus une fois par seconde). */
    private fun healSensors() {
        val now = System.currentTimeMillis()
        if (now - lastHealAt < 1000) return
        lastHealAt = now
        Log.w(TAG, "Module capteurs coupé : réveil du module")
        onSdkReady()
    }
    private val lastValid = IntArray(5)
    private val lastValidAt = LongArray(5)

    /**
     * Distances devant (mm) : ToF gauche, centre, droite, US gauche, droite. 0 = rien à portée.
     * - ToF droit : faux écho permanent de 20 à 160 mm (capteur défectueux) → ignoré sous 300 mm.
     * - Un 0 juste après une mesure proche (< STICKY_MM) = objet trop près pour être
     *   mesuré : on garde la dernière mesure pendant STICKY_HOLD_MS.
     */
    private fun frontReadings(): IntArray {
        val tof = BuddySDK.Sensors.TofSensors()
        val us = BuddySDK.Sensors.USSensors()
        val raw = intArrayOf(
            tof.FrontLeft().let { if (it.error) -1 else it.distance },
            tof.FrontMiddle().let { if (it.error) -1 else it.distance },
            tof.FrontRight().let { if (it.error) -1 else it.distance },
            us.LeftUS().let { if (it.error) -1 else it.distance },
            us.RightUS().let { if (it.error) -1 else it.distance },
        )
        val now = System.currentTimeMillis()
        // Capteurs vivants = au moins une valeur plausible (0 = rien à portée, c'est normal).
        // Module coupé par BuddyOS = toutes les valeurs en erreur (65535, -1).
        if (raw.any { it in 0..MAX_VALID_MM }) lastAliveAt = now
        if (now - lastRawLog > SENSOR_LOG_MS) {
            lastRawLog = now
            Log.i(TAG, "Capteurs bruts : ToF ${raw[0]}/${raw[1]}/${raw[2]} US ${raw[3]}/${raw[4]} vide ${cliffMask()} | ${if (now - lastAliveAt < SENSORS_ALIVE_MS) "actifs" else "MODULE COUPÉ"}")
        }
        val r = IntArray(5) { i ->
            val v = raw[i]
            val min = if (i == 2) MIN_VALID_TOF_RIGHT_MM else MIN_VALID_MM
            if (v in min..MAX_VALID_MM) {
                lastValid[i] = v; lastValidAt[i] = now; v
            } else if (v == 0 && lastValid[i] in 1 until STICKY_MM && now - lastValidAt[i] < STICKY_HOLD_MS) {
                lastValid[i]
            } else 0
        }
        lastFront = r
        return r
    }

    /**
     * Aucun obstacle connu à moins de OBSTACLE_MM devant, et module capteurs actif
     * (s'il est coupé par BuddyOS, toutes les valeurs sont en erreur : on n'avance pas).
     */
    private fun pathClear(): Boolean {
        val r = frontReadings()
        return sensorsAlive() && r.filter { it > 0 }.all { it >= OBSTACLE_MM }
    }

    private fun sensorsAlive() = System.currentTimeMillis() - lastAliveAt < SENSORS_ALIVE_MS

    /** Rien derrière à moins de OBSTACLE_MM. Capteur en erreur (module coupé) = on ne recule pas. */
    private fun backClear(): Boolean = try {
        val b = BuddySDK.Sensors.TofSensors().Back()
        !b.error && b.distance in 0..MAX_VALID_MM && b.distance !in MIN_VALID_MM until OBSTACLE_MM
    } catch (e: Exception) { false }

    /** Au moins 2 capteurs de vide en alarme : bord d'escalier ou robot soulevé. */
    private fun cliffAlarm(): Boolean = try {
        val c = BuddySDK.Sensors.CliffSensors()
        listOf(c.Cliff1(), c.Cliff2(), c.Cliff3(), c.Cliff4(), c.Cliff5(), c.Cliff6(), c.Cliff7()).count { it.alarm } >= 2
    } catch (e: Exception) { false }

    /**
     * Vide confirmé sur deux lectures à 80 ms d'écart : une secousse (accélération, joint
     * de carrelage) ne déclenche plus le recul. À 0,5 m/s, ça ne coûte que 4 cm.
     */
    private fun cliffConfirmed(): Boolean {
        if (!cliffAlarm()) return false
        Thread.sleep(80)
        return cliffAlarm()
    }

    /** Capteurs de vide en alarme, ex. « 1·3 » (pour comprendre les faux vides). */
    private fun cliffMask(): String = try {
        val c = BuddySDK.Sensors.CliffSensors()
        listOf(c.Cliff1(), c.Cliff2(), c.Cliff3(), c.Cliff4(), c.Cliff5(), c.Cliff6(), c.Cliff7())
            .mapIndexedNotNull { i, s -> if (s.alarm) "${i + 1}" else null }.joinToString("·").ifEmpty { "aucun" }
    } catch (e: Exception) { "?" }

    private fun batteryLow(): Boolean = try {
        BuddySDK.Sensors.Battery().let { !it.isCharging && it.batteryLevel in 1 until MIN_BATTERY_PERCENT }
    } catch (e: Exception) { false }

    // --- Moteurs ---

    private fun emergencyStop() {
        try { BuddySDK.USB.emergencyStopMotors(rsp("emergencyStop")) } catch (e: Exception) { Log.w(TAG, "emergencyStopMotors", e) }
    }

    private fun enableWheels(on: Boolean) {
        try { BuddySDK.USB.enableWheels(on, rsp("enableWheels($on)")) } catch (e: Exception) { Log.w(TAG, "enableWheels", e) }
    }

    private fun randomSign() = if (Random.nextBoolean()) 1f else -1f

    private class Waitable {
        @Volatile var finished = false
        @Volatile var failed = false
        @Volatile var result: String? = null
        val rsp = object : IUsbCommadRsp.Stub() {
            override fun onSuccess(p0: String?) { result = p0; if (p0?.contains("FINISHED", ignoreCase = true) == true) finished = true }
            override fun onFailed(p0: String?) { result = p0; Log.w(TAG, "Mouvement refusé : $p0"); failed = true; finished = true }
        }
        fun await(timeoutMs: Long) {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (!finished && System.currentTimeMillis() < deadline) Thread.sleep(50)
        }
    }

    private fun rsp(what: String) = object : IUsbCommadRsp.Stub() {
        override fun onSuccess(p0: String?) {}
        // « Cancelled » = remplacée par la commande suivante : normal en pilotage continu.
        override fun onFailed(p0: String?) { if (p0?.contains("Cancelled") != true) Log.w(TAG, "$what a échoué : $p0") }
    }

    companion object {
        private const val TAG = "BuddyUG"
        /**
         * Seuil d'arrêt. Mesuré le 2026-11-16 : avec 200 mm, il détectait le mur à 12-19 cm
         * et le touchait en freinant. À 300 mm + ralentissement, il s'arrête vers 20 cm.
         */
        private const val OBSTACLE_MM = 300
        /** Sous cette distance, il roule au pas (SLOW_SPEED_MS). */
        private const val SLOW_MM = 600
        private const val SLOW_SPEED_MS = 0.1f
        /** Collé à l'obstacle (< BACK_OFF_IF_MM) : recule de BACK_OFF_M avant de tourner. */
        private const val BACK_OFF_IF_MM = 250
        private const val BACK_OFF_M = 0.15f
        /** Virage d'évitement : pas de 30° jusqu'à voie libre, puis 15° de marge (45° au minimum). */
        private const val TURN_STEP_DEG = 30f
        private const val TURN_MARGIN_DEG = 15f
        private const val TURN_SETTLE_MS = 250L
        /** Vide : recul, puis sol retrouvé depuis ce délai avant de repartir. */
        private const val CLIFF_BACK_M = 0.2f
        private const val CLIFF_CLEAR_MS = 2_000L
        /** « Stop » à la voix : immobile pendant ce délai, puis la balade reprend. */
        private const val VOICE_STOP_MS = 60_000L
        private const val STICKY_MM = 400
        private const val STICKY_HOLD_MS = 3_000L
        private const val MIN_VALID_MM = 100
        /** ToF avant droit défectueux sur notre Buddy : faux écho de 20 à 160 mm, même nettoyé. */
        private const val MIN_VALID_TOF_RIGHT_MM = 300
        private const val MAX_VALID_MM = 4000
        /** Sans vraie mesure depuis ce délai, les capteurs sont considérés muets : pas d'avance. */
        private const val SENSORS_ALIVE_MS = 5_000L
        private const val SENSOR_LOG_MS = 2_000L
        private const val WANDER_SPEED_MS = 0.2f
        private const val WANDER_MIN_M = 2f      // lignes droites de 2 à 5 m : il parcourt les couloirs
        private const val WANDER_MAX_M = 5f
        private const val CONTROL_MS = 100L      // période de la boucle de pilotage
        private const val WATCHDOG_MS = 400L     // sans commande depuis ce délai : moteurs coupés
        private const val PERSON_CHECK_MS = 700L
        private const val FOLLOW_MAX_SPEED_MS = 0.5f
        private const val FOLLOW_SPEED_GAIN = 4f     // m/s par unité de largeur manquante (distance plus grande : plus de gain)
        private const val FOLLOW_TURN_GAIN = 2.5f    // rad/s par unité de décalage
        private const val FOLLOW_MAX_TURN_RAD = 1.2f
        private const val SEARCH_TURN_RAD = 0.7f     // ~40°/s : recherche après une perte
        private const val FOLLOW_STOP_MS = 1_200L    // plus vue depuis 1,2 s : on ne roule plus (avant : élan)
        private const val FOLLOW_PERSON_THRESHOLD = 0.35f
        private const val TRACK_THRESHOLD = 0.5f
        private const val TRACK_GIVE_UP_MS = 1_000L   // pisteur décroché depuis 1 s : on passe à la détection du corps
        private const val DETECT_MIN_MS = 3_000L      // la détection a au moins 3 s pour retrouver la personne
        private const val DETECT_GIVE_UP_MS = 2_000L  // puis 2 s sans rien : retour au pisteur
        private const val LOCK_MAX_JUMP = 0.3f       // au-delà, c'est une autre personne
        private const val FIGURE_SPEED_MS = 0.2f
        private const val FIGURE_FORWARD_M = 1.0f   // « avance »
        private const val FIGURE_BACK_M = 0.5f      // « recule » : un seul capteur derrière, on reste prudent
        private const val TURN_SPEED_DEG = 60f
        private const val PERSON_THRESHOLD = 0.5f
        private const val PERSON_CLOSE_WIDTH = 0.32f // suivi : ~1,3 m de la personne, on n'avance plus (0,40 = trop près, elle sortait de l'image)
        private const val PERSON_NEAR_WIDTH = 0.30f  // balade : personne à ~1,5 m devant, on l'évite
        private const val FOLLOW_LOST_MS = 8_000L
        /** Perdue de vue : il la cherche en tournant jusqu'à ce délai, puis attend sur place. */
        private const val SEARCH_FIRST_SIDE_MS = 2_500L // ~100° du côté probable, puis l'autre côté
        /** Suivi sans personne en vue : tour lent (0,45 rad/s ≈ 26°/s, un tour en ~14 s) puis pause, sans fin. */
        private const val FIRST_LOOK_TURN_RAD = 0.45f
        private const val SEARCH_TURN_S = 14L
        private const val SEARCH_CYCLE_S = 20L
        /** Visage (suivi de près) : largeur ~0,14 vers 1 m → on n'avance plus (estimation, à affiner). */
        private const val FACE_THRESHOLD = 0.5f
        private const val FACE_MAX_MS = 1_500L
        private const val WAKE_WORD_UNLOAD_MS = 20_000L
        private const val FOLLOW_FRESH_MS = 500L
        private const val FACE_CLOSE_WIDTH = 0.11f
        private const val FACE_SPEED_GAIN = 8f   // visage 0,07 (~2 m) → 0,32 m/s ; 0,05 (~3 m) → 0,48 m/s
        private const val TALK_HOLD_MS = 20_000L
        private const val HEAD_SPEED_DEG = 40f
        private const val HEAD_ANGLE_DEG = 20f
        private const val MIN_BATTERY_PERCENT = 15
        /** Petite chanson dite pendant la danse (~12 s à voix haute, comme la figure). */
        private const val DANCE_SONG =
            "Bip bip bou, je danse comme un robot ! " +
            "Je tourne à gauche, je tourne à droite, et je fais oui de la tête ! " +
            "Tchou tchou wa, tchou tchou wa, Epitech c'est le top ! " +
            "Un petit tour sur moi-même, et je fais non non non ! Ta-da !"
        private const val LOW_BATTERY_PHRASE = "Ma batterie est presque vide, je ne peux plus rouler. Branche-moi s'il te plaît !"
        private const val POLL_MS = 50L
        private const val MAX_COMMAND_WORDS = 8
        /** Mots de politesse ou d'appel ignorés en tête de commande. */
        private val FILLERS = listOf(
            "buddy", "hey", "he", "eh", "oh", "allez", "vas y", "dis", "bon", "ok", "okay", "alors", "maintenant", "stp",
            "s il te plait", "s il vous plait", "est ce que tu peux", "est ce que tu pourrais", "tu peux", "tu pourrais",
            "peux tu", "pourrais tu", "tu veux bien", "je veux que tu", "j aimerais que tu",
        )

        private fun normalize(s: String) = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .replace(Regex("[^a-z ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }
}
