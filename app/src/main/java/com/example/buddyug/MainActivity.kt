package com.example.buddyug

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.bfr.buddy.speech.shared.ISTTCallback
import com.bfr.buddy.speech.shared.ITTSCallback
import com.bfr.buddy.speech.shared.STTResultsData
import com.bfr.buddy.ui.shared.FacialExpression
import com.bfr.buddy.ui.shared.LabialExpression
import com.bfr.buddy.vision.shared.IVisionRsp
import com.bfr.buddy.vision.shared.enums.ArucoDictionary
import com.bfr.buddysdk.BuddyActivity
import com.bfr.buddysdk.BuddySDK
import com.bfr.buddysdk.services.speech.STTTask
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Activité principale de l'application Buddy pour le Salon de l'Étudiant (stand Epitech).
 *
 * Buddy reste immobile : boucle de questions/réponses vocales
 * (STT → Groq → TTS).
 *
 * Hérite de BuddyActivity (jamais AppCompatActivity) pour recevoir le
 * cycle de vie du SDK. Tout appel à BuddySDK doit se faire dans
 * onSDKReady(), jamais dans onCreate() — le SDK n'est pas encore prêt.
 */
class MainActivity : BuddyActivity() {

    private val httpClient = OkHttpClient()
    private val conversationHistory = JSONArray()
    private val isListening = AtomicBoolean(false)
    private val isSpeaking = AtomicBoolean(false)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var isPaused = false
    @Volatile private var currentSttTask: STTTask? = null
    @Volatile private var sdkInitialized = false

    private var arucoScanningActive = false
    private var lastArucoId: Int? = null
    private var lastArucoAnnouncedAt = 0L
    private val pendingArucoId = AtomicReference<Int?>(null)

    // Jeux à marqueurs (contenu : MarkerGames.kt)
    @Volatile private var gameActive = false
    private val gameAnswers = LinkedBlockingQueue<Int>()
    private var bestQuizScore = 0

    private var stateServer: StateServer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
    }

    override fun onResume() {
        super.onResume()
        isPaused = false
    }

    override fun onPause() {
        super.onPause()
        isPaused = true
    }

    override fun onDestroy() {
        arucoScanningActive = false
        // Au lancement, le Companion détruit et recrée l'activité avant que le
        // SDK soit connecté : stopCamera() lève alors une NPE qui tue le process
        // et fait échouer la relance. On ne coupe la caméra que si le SDK est prêt.
        if (sdkInitialized) {
            try {
                BuddySDK.Vision.stopCamera(object : IVisionRsp.Stub() {
                    override fun onSuccess(p0: String?) {}
                    override fun onFailed(p0: String?) {}
                })
            } catch (e: Exception) {
                Log.w(TAG, "stopCamera à la fermeture a échoué", e)
            }
        }
        stateServer?.stop()
        super.onDestroy()
    }

    override fun onSDKReady() {
        BuddySDK.UI.setViewAsFace(findViewById(R.id.view_face))
        BuddySDK.UI.setMood(FacialExpression.NEUTRAL, 0.5, null)

        // onSDKReady peut être rappelé plusieurs fois sur la vie de l'activité
        // (reprise par Companion, reconnexion SDK...). Sans cette garde, on
        // relancerait à chaque fois une nouvelle boucle Q&A en parallèle des
        // précédentes (jamais arrêtées), plusieurs threads concurrents se
        // battant pour les mêmes ressources micro/haut-parleur.
        // Le hotword "Ok Buddy" (moteur Cerence/VoCon) déclenche une mission
        // Companion WAKE_UP (« Punctual_listen_and_Eye_contact ») qui prend la
        // main : Buddy bouge la tête et se réaligne avant de nous rendre la main.
        // Le Companion fait « Reset services » à chaque reprise de l'app, ce qui
        // peut recharger VoCon : on le décharge donc à CHAQUE onSDKReady.
        try {
            BuddySDK.Speech.unloadVoCon()
            Log.i(TAG, "VoCon (hotword « Ok Buddy ») déchargé")
        } catch (e: Exception) {
            Log.w(TAG, "unloadVoCon a échoué", e)
        }

        if (sdkInitialized) {
            Log.i(TAG, "onSDKReady rappelé, initialisation déjà faite — ignoré")
            return
        }
        sdkInitialized = true

        // Écran de diffusion du salon : http://<ip_buddy>:8080/
        try {
            stateServer = StateServer(this, STATE_SERVER_PORT).also { it.start() }
            Log.i(TAG, "StateServer démarré sur le port $STATE_SERVER_PORT")
        } catch (e: Exception) {
            Log.e(TAG, "Échec démarrage StateServer", e)
        }

        startArucoScanner()
        Thread { startConversationLoop() }.start()
    }

    private fun broadcastState(
        state: String, heard: String? = null, response: String? = null,
        marker: Int? = null, game: JSONObject? = null,
    ) {
        val server = stateServer ?: return
        server.broadcast(JSONObject().apply {
            put("state", state)
            put("heard", heard ?: JSONObject.NULL)
            put("response", response ?: JSONObject.NULL)
            put("marker", marker ?: JSONObject.NULL)
            put("game", game ?: JSONObject.NULL)
            put("timestamp", System.currentTimeMillis())
        })
    }

    // --- Scan de marqueurs ArUco (BuddySDK.Vision) ---

    private fun startArucoScanner() {
        BuddySDK.Vision.startCamera(object : IVisionRsp.Stub() {
            override fun onSuccess(p0: String?) {
                Log.i(TAG, "startCamera onSuccess ($p0) — lancement du polling ArUco")

                // BuddyCore lance de lui-même un tracking de personne
                // (ViTTracker, événements onFace) qui monopolise le pipeline
                // CV du service Vision — absent vendredi quand la détection
                // marchait, présent aujourd'hui où elle ne trouve plus rien.
                // On le coupe explicitement avant de scanner.
                try {
                    BuddySDK.Vision.stopTracking()
                    Log.i(TAG, "stopTracking OK")
                } catch (e: Exception) {
                    Log.e(TAG, "stopTracking a échoué", e)
                }

                arucoScanningActive = true
                Thread { arucoPollingLoop() }.start()
            }

            override fun onFailed(p0: String?) {
                Log.e(TAG, "startCamera onFailed: $p0")
            }
        })
    }

    /**
     * DICT_4X4_50 : dictionnaire des marqueurs imprimés pour le salon,
     * identifié empiriquement (scan multi-dictionnaires du 2026-07-13 :
     * les marqueurs 5 et 6 imprimés décodent en 4x4_50 ; le marqueur collé
     * au mur du fond ne décode qu'en ARUCO_ORIGINAL et disparaît donc du
     * paysage en verrouillant sur 4x4_50).
     *
     * On exige que le même ID soit vu ARUCO_CONFIRM_STREAK fois de suite
     * avant de l'accepter : les fausses lectures (ex: "17") n'apparaissent
     * que sur des frames floues pendant que le marqueur bouge, jamais
     * plusieurs polls consécutifs.
     */
    private fun arucoPollingLoop() {
        var candidateId: Int? = null
        var candidateStreak = 0
        // Carte actuellement tenue devant Buddy : elle ne compte qu'une fois,
        // il faut la retirer (ARUCO_RELEASE_POLLS polls sans elle) pour la rejouer.
        // Sinon, au jeu, la carte encore levée répondrait aussi à la question suivante.
        var heldId: Int? = null
        var missingPolls = 0

        while (arucoScanningActive) {
            try {
                val seenId = BuddySDK.Vision.getListOfArucos(ArucoDictionary.DICT_4X4_50).firstOrNull()?.id
                if (seenId != null && seenId == candidateId) {
                    candidateStreak++
                } else {
                    candidateId = seenId
                    candidateStreak = if (seenId != null) 1 else 0
                }
                if (seenId == null) {
                    if (++missingPolls >= ARUCO_RELEASE_POLLS) heldId = null
                } else {
                    missingPolls = 0
                }
                if (seenId != null && candidateStreak >= ARUCO_CONFIRM_STREAK && seenId != heldId) {
                    heldId = seenId
                    onArucoDetected(seenId)
                }
            } catch (e: Exception) {
                // Filet de sécurité : une contention de ressource caméra (ex:
                // une autre appli Buddy) ne doit jamais tuer tout le process.
                Log.e(TAG, "getListOfArucos a échoué", e)
            }
            Thread.sleep(ARUCO_POLL_INTERVAL_MS)
        }
    }

    /**
     * Met le marqueur en attente puis interrompt immédiatement l'écoute ou la
     * parole en cours (au lieu d'attendre jusqu'à 15s la fin du cycle Q&A) —
     * la boucle de conversation délivre l'annonce dès qu'elle se libère.
     * Les appels stop() sont inconditionnels (no-op s'il n'y a rien à
     * interrompre) pour éviter une race avec les flags isSpeaking/isListening.
     */
    private fun onArucoDetected(id: Int) {
        // En partie : seules les cartes réponse comptent, transmises au jeu.
        // On coupe la parole pour qu'on puisse répondre sans attendre la fin de la question.
        if (gameActive) {
            if (id in Cards.ANSWERS) {
                gameAnswers.offer(id)
                BuddySDK.Speech.stopSpeaking()
            }
            return
        }
        val now = System.currentTimeMillis()
        if (id == lastArucoId && now - lastArucoAnnouncedAt < ARUCO_COOLDOWN_MS) return

        lastArucoId = id
        lastArucoAnnouncedAt = now
        Log.i(TAG, "Marqueur $id confirmé, mise en attente + interruption")
        pendingArucoId.set(id)
        // Les cartes JOUER lancent une partie : pas de badge « marqueur » à l'écran.
        if (id != Cards.PLAY_PROFILE && id != Cards.PLAY_QUIZ) broadcastState("marker", marker = id)

        BuddySDK.Speech.stopSpeaking()
        currentSttTask?.stop()
    }

    // --- Parole ---

    private fun speak(text: String, onDone: () -> Unit) {
        isSpeaking.set(true)
        mainHandler.post { BuddySDK.UI.setMood(FacialExpression.HAPPY, 0.5, null) }
        BuddySDK.Speech.startSpeaking(forSpeech(text), LabialExpression.SPEAK_NEUTRAL, object : ITTSCallback.Stub() {
            override fun onSuccess(p0: String?) {
                mainHandler.post { BuddySDK.UI.setMood(FacialExpression.NEUTRAL, 0.5, null) }
                isSpeaking.set(false)
                onDone()
            }

            override fun onError(p0: String?) {
                mainHandler.post { BuddySDK.UI.setMood(FacialExpression.NEUTRAL, 0.5, null) }
                isSpeaking.set(false)
                onDone()
            }

            override fun onPause() {}
            override fun onResume() {}
        })
    }

    private fun speakSync(text: String) {
        var done = false
        speak(text) { done = true }
        val timeout = System.currentTimeMillis() + (text.length * 120L) + 4000L
        while (!done && System.currentTimeMillis() < timeout) Thread.sleep(50)
        Thread.sleep(300)
    }

    // --- Boucle de questions/réponses ---

    private fun startConversationLoop() {
        Thread.sleep(500)
        try {
            BuddySDK.Speech.createGoogleSTTTask(Locale.FRENCH).initialize()
        } catch (e: Exception) {
            // Warmup best-effort : si ça échoue (ex: focus micro pas encore
            // possédé par l'app), le premier vrai appel STT sera juste plus lent.
        }
        val greeting = "Salut ! Moi c'est Buddy, le robot d'Epitech La Réunion. Une question sur l'école, les études d'informatique ou les métiers de la tech ? Je t'écoute !"
        broadcastState("speaking", response = greeting)
        speakSync(greeting)

        while (true) {
            try {
                if (isPaused) {
                    Thread.sleep(500)
                    continue
                }
                when (pendingArucoId.getAndSet(null)) {
                    Cards.PLAY_PROFILE -> { runProfileGame(); continue }
                    Cards.PLAY_QUIZ -> { runQuizGame(); continue }
                    else -> {}
                }
                pendingArucoId.getAndSet(null)?.let { id ->
                    Log.i(TAG, "Annonce du marqueur $id (délivrée depuis la boucle Q&A)")
                    // Annonce vocale volontairement vide : l'écran affiche le marqueur.
                    val text = ""
                    broadcastState("speaking", response = text, marker = id)
                    if (text.isNotBlank()) speakSync(text)
                }
                broadcastState("listening")
                mainHandler.post { BuddySDK.UI.setMood(FacialExpression.LISTENING, 0.5, null) }
                val heard = listenOnce()
                if (heard.isNullOrBlank()) {
                    Thread.sleep(200)
                    continue
                }
                broadcastState("thinking", heard = heard)
                mainHandler.post { BuddySDK.UI.setMood(FacialExpression.THINKING, 0.5, null) }
                val response = askGroq(heard)
                if (response.isNotBlank()) {
                    broadcastState("speaking", heard = heard, response = response)
                    speakSync(response)
                }
            } catch (e: Exception) {
                // Filet de sécurité : une exception SDK isolée (ex: "Focus not
                // owned" si une autre appli Buddy rafle une ressource au même
                // moment) ne doit jamais tuer tout le process — on l'ignore et
                // la boucle continue au tour suivant.
                Log.e(TAG, "Erreur dans la boucle de conversation", e)
                Thread.sleep(300)
            }
        }
    }

    // --- Jeux à marqueurs (contenu : MarkerGames.kt) ---

    /** État du jeu envoyé à l'écran (champ "game" du message WebSocket). */
    private fun gameJson(
        type: String, phase: String, step: Int, total: Int, question: String,
        options: List<Pair<String, String>>, answer: String? = null, correct: Boolean? = null,
        score: Int? = null, best: Int? = null, resultTitle: String? = null, resultText: String? = null,
    ) = JSONObject().apply {
        put("type", type)
        put("phase", phase)
        put("step", step)
        put("total", total)
        put("question", question)
        put("options", JSONArray().apply {
            options.forEach { (card, label) -> put(JSONObject().put("card", card).put("label", label)) }
        })
        put("answer", answer ?: JSONObject.NULL)
        put("correct", correct ?: JSONObject.NULL)
        put("score", score ?: JSONObject.NULL)
        put("best", best ?: JSONObject.NULL)
        put("resultTitle", resultTitle ?: JSONObject.NULL)
        put("resultText", resultText ?: JSONObject.NULL)
    }

    /** Attend une carte parmi [valid] ; null si personne ne répond à temps. */
    private fun waitAnswer(valid: Set<Int>): Int? {
        broadcastStateKeepGame("playing")
        mainHandler.post { BuddySDK.UI.setMood(FacialExpression.LISTENING, 0.5, null) }
        val deadline = System.currentTimeMillis() + GAME_ANSWER_TIMEOUT_MS
        while (true) {
            val left = deadline - System.currentTimeMillis()
            if (left <= 0) return null
            val id = gameAnswers.poll(left, TimeUnit.MILLISECONDS) ?: return null
            if (id in valid) return id
        }
    }

    private var lastGame: JSONObject? = null

    private fun showGame(state: String, game: JSONObject, speech: String? = null) {
        lastGame = game
        broadcastState(state, response = speech, game = game)
    }

    private fun broadcastStateKeepGame(state: String) = broadcastState(state, game = lastGame)

    /** Encadre une partie : coupe la conversation, et nettoie quoi qu'il arrive. */
    private fun playGame(name: String, body: () -> Unit) {
        Log.i(TAG, "Début du jeu $name")
        gameAnswers.clear()
        gameActive = true
        try {
            body()
            Thread.sleep(GAME_RESULT_HOLD_MS) // laisse le résultat à l'écran
        } finally {
            gameActive = false
            gameAnswers.clear()
            lastGame = null
            Log.i(TAG, "Fin du jeu $name")
        }
    }

    private fun timeoutBye() {
        val text = "Personne ? Pas grave, on rejouera plus tard. Montre-moi une carte Jouer quand tu veux !"
        broadcastState("speaking", response = text)
        speakSync(text)
    }

    private fun runProfileGame() = playGame("profil") {
        val total = PROFILE_QUESTIONS.size
        val intro = "C'est parti pour le Profil tech ! $total questions : réponds en me montrant la carte A, B, C ou D."
        broadcastState("speaking", response = intro)
        speakSync("C'est parti pour le Profil tech ! $total questions : réponds en me montrant la carte A, Bé, Cé ou Dé.")
        val picked = mutableListOf<Profile>()
        for ((i, q) in PROFILE_QUESTIONS.withIndex()) {
            val options = q.options.mapIndexed { j, (label, _) -> "ABCD"[j].toString() to label }
            val game = gameJson("profile", "question", i + 1, total, q.text, options)
            val spoken = "Question ${i + 1}. ${q.text} " +
                options.mapIndexed { j, (_, label) -> "Carte ${Cards.SPOKEN_LETTERS[j]} : $label." }.joinToString(" ")
            showGame("speaking", game, spoken)
            speakSync(spoken)
            val card = waitAnswer(Cards.LETTERS.toSet()) ?: return@playGame timeoutBye()
            picked += q.options[Cards.LETTERS.indexOf(card)].second
            showGame("speaking", gameJson("profile", "feedback", i + 1, total, q.text, options, answer = Cards.letter(card)))
            speakSync(listOf("Noté !", "Bien reçu !", "OK !", "Intéressant…")[i % 4])
        }
        val p = profileResult(picked)
        val text = "Ton profil : ${p.title} ! ${p.text}"
        showGame("speaking", gameJson("profile", "result", total, total, "", emptyList(),
            resultTitle = p.title, resultText = p.text), text)
        speakSync(text)
    }

    private fun runQuizGame() = playGame("quiz") {
        val items = pickQuiz()
        val total = items.size
        val options = listOf("VRAI" to "Vrai", "FAUX" to "Faux")
        val intro = "C'est parti pour le quiz ! $total affirmations : montre-moi la carte Vrai ou la carte Faux."
        broadcastState("speaking", response = intro)
        speakSync(intro)
        var score = 0
        for ((i, item) in items.withIndex()) {
            val spoken = "Affirmation ${i + 1}. ${item.statement} Vrai ou faux ?"
            showGame("speaking", gameJson("quiz", "question", i + 1, total, item.statement, options, score = score, best = bestQuizScore), spoken)
            speakSync(spoken)
            val card = waitAnswer(setOf(Cards.VRAI, Cards.FAUX)) ?: return@playGame timeoutBye()
            val correct = (card == Cards.VRAI) == item.answer
            if (correct) score++
            val verdict = (if (correct) "Bonne réponse ! " else "Raté ! ") + (if (item.answer) "C'est vrai. " else "C'est faux. ") + item.explanation
            showGame("speaking", gameJson("quiz", "feedback", i + 1, total, item.statement, options,
                answer = if (card == Cards.VRAI) "VRAI" else "FAUX", correct = correct, score = score, best = bestQuizScore), verdict)
            speakSync(verdict)
        }
        val record = score > bestQuizScore
        if (record) bestQuizScore = score
        val text = "Score final : $score sur $total ! " + when {
            record -> "Nouveau meilleur score du jour !"
            score == total -> "Sans faute, bravo !"
            score >= total / 2 + 1 -> "Bien joué !"
            else -> "Viens en parler avec l'équipe du stand, ils t'expliqueront tout."
        }
        showGame("speaking", gameJson("quiz", "result", total, total, "", emptyList(),
            score = score, best = bestQuizScore, resultTitle = "$score / $total", resultText = text), text)
        speakSync(text)
    }

    private fun listenOnce(): String? {
        if (!isListening.compareAndSet(false, true)) return null
        var result: String? = null
        var done = false
        try {
            val sttTask = BuddySDK.Speech.createGoogleSTTTask(Locale.FRENCH)
            currentSttTask = sttTask
            sttTask.start(false, object : ISTTCallback.Stub() {
                override fun onSuccess(results: STTResultsData?) {
                    result = results?.getResults()?.firstOrNull()?.utterance
                    done = true
                }

                override fun onError(error: String?) {
                    done = true
                }
            })
            val timeout = System.currentTimeMillis() + 15000
            while (!done && System.currentTimeMillis() < timeout) Thread.sleep(100)
        } catch (e: Exception) {
            Thread.sleep(300)
        } finally {
            currentSttTask = null
            isListening.set(false)
        }
        return result
    }

    private fun askGroq(userMessage: String): String {
        return try {
            conversationHistory.put(JSONObject().apply {
                put("role", "user")
                put("content", userMessage)
            })
            // Au salon, les visiteurs se succèdent : on ne garde que les derniers
            // échanges (contexte court, réponses rapides, pas de mélange entre visiteurs).
            while (conversationHistory.length() > MAX_HISTORY_MESSAGES) conversationHistory.remove(0)
            val messages = JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "system")
                    put("content", SYSTEM_PROMPT)
                })
                for (i in 0 until conversationHistory.length()) put(conversationHistory.getJSONObject(i))
            }
            // Quota gratuit Groq : 8000 tokens/min PAR MODÈLE (~4 questions/min).
            // Si le modèle principal est saturé (429), on bascule sur le secours,
            // qui a son propre quota.
            var (code, raw) = callGroq(GROQ_MODEL, messages)
            if (code == 429) {
                Log.w(TAG, "Quota Groq atteint sur $GROQ_MODEL, bascule sur $GROQ_FALLBACK_MODEL")
                val fallback = callGroq(GROQ_FALLBACK_MODEL, messages)
                code = fallback.first
                raw = fallback.second
            }
            // Sans ça, une clé invalide ou un modèle retiré ne laisse aucune trace.
            if (code !in 200..299) throw java.io.IOException("Groq HTTP $code : $raw")
            val json = JSONObject(raw)
            val msg = json.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content")
            conversationHistory.put(JSONObject().apply {
                put("role", "assistant")
                put("content", msg)
            })
            msg
        } catch (e: Exception) {
            Log.e(TAG, "Appel Groq échoué", e)
            "Oups, petit souci de connexion ! Repose-moi ta question, ou demande à l'équipe du stand."
        }
    }

    /** Un appel chat/completions : renvoie (code HTTP, corps). Raisonnement interne coupé/masqué. */
    private fun callGroq(model: String, messages: JSONArray): Pair<Int, String> {
        val body = JSONObject().apply {
            put("model", model)
            put("messages", messages)
            // Ces modèles « réfléchissent » avant de répondre : on coupe ce
            // raisonnement (moins de tokens, plus rapide, jamais lu à voix haute).
            if (model.startsWith("openai/gpt-oss")) {
                put("reasoning_effort", "low")
                put("include_reasoning", false)
            } else {
                put("reasoning_effort", "none")
            }
            put("max_tokens", 400)
            put("temperature", 0.7)
        }
        val request = Request.Builder()
            .url("https://api.groq.com/openai/v1/chat/completions")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .addHeader("Authorization", "Bearer ${BuildConfig.GROQ_API_KEY}")
            .build()
        httpClient.newCall(request).execute().use { return it.code to (it.body?.string() ?: "") }
    }

    companion object {
        private const val TAG = "BuddyUG"
        private const val ARUCO_POLL_INTERVAL_MS = 400L
        private const val ARUCO_COOLDOWN_MS = 5000L
        private const val ARUCO_CONFIRM_STREAK = 3
        private const val ARUCO_RELEASE_POLLS = 3 // ~1,2 s sans la carte = carte retirée
        private const val GAME_ANSWER_TIMEOUT_MS = 25_000L
        private const val GAME_RESULT_HOLD_MS = 4_000L
        private const val STATE_SERVER_PORT = 8080
        // llama-3.3-70b-versatile a été retiré de Groq (404 model_not_found).
        // Modèles dispo : curl https://api.groq.com/openai/v1/models -H "Authorization: Bearer <clé>"
        private const val GROQ_MODEL = "qwen/qwen3.8-27b"
        private const val GROQ_FALLBACK_MODEL = "openai/gpt-oss-120b"

        /**
         * Dictionnaire de prononciation : appliqué uniquement au texte envoyé à
         * la synthèse vocale (l'écran garde l'orthographe correcte).
         * Pour corriger un mot : ajouter une ligne `"mot" to "écriture phonétique"`,
         * puis `./buddy deploy`. Insensible à la casse, mots entiers seulement ;
         * les entrées les plus longues sont appliquées en premier.
         */
        private val PRONUNCIATIONS = mapOf(
            "Epitech" to "Épitek",
            "Parcoursup" to "Parcours supe", // sinon lu « parcoursoupe »
            "Bachelor" to "Bachelore", // fin en « or », pas « eur »
            "Pré-MSc" to "pré Master of Science",
            "MSc" to "Master of Science",
            "PGE" to "Programme Grande École",
            "RNCP" to "R N C P",
            "ECTS" to "E C T S",
            "IoT" to "I O T",
            "Bac+2" to "bac plus 2",
            "Bac+3" to "bac plus 3",
            "Bac+5" to "bac plus 5",
        )

        private val PRONUNCIATION_RULES = PRONUNCIATIONS.entries
            .sortedByDescending { it.key.length }
            .map { (word, spoken) ->
                Regex("(?<![\\p{L}\\d])${Regex.escape(word)}(?![\\p{L}\\d])", RegexOption.IGNORE_CASE) to spoken
            }

        /** Texte tel qu'il doit être prononcé par Buddy. */
        fun forSpeech(text: String): String =
            PRONUNCIATION_RULES.fold(text) { acc, (regex, spoken) -> regex.replace(acc, Regex.escapeReplacement(spoken)) }
        private const val MAX_HISTORY_MESSAGES = 12

        /**
         * Prompt système de Buddy. Toutes les infos Epitech viennent des pages
         * officielles epitech.eu (consultées le 2026-11-11) :
         * campus La Réunion, Programme Grande École, Bachelor, Pré-MSc, MSc/MBA.
         * Règle : n'ajouter ici qu'une info vérifiée sur epitech.eu.
         */
        private val SYSTEM_PROMPT = """
Tu es Buddy, le robot mascotte d'Epitech La Réunion, sur le stand Epitech du Salon de l'Étudiant. Tu es en ce moment sur ce salon, PAS sur le campus ni au Hub : ne dis jamais « ici, au Hub » ou « ici, sur le campus ». Tu es immobile mais tu accueilles les visiteurs : surtout des lycéens, des étudiants en réorientation et leurs parents. Tu es sympa, passionné de tech, taquin et motivant. Tu tutoies les jeunes et tu vouvoies les adultes qui te vouvoient.

RÈGLE ABSOLUE : tu ne dis QUE ce qui est écrit ci-dessous. Tu n'inventes jamais rien : ni chiffre, ni date, ni prix, ni salaire, ni nombre de places, ni nom de formation, ni partenaire. Si l'info n'est pas ci-dessous, ou si tu as un doute, tu le dis franchement et tu renvoies vers l'équipe Epitech sur le stand ou vers epitech.eu.

EPITECH EN BREF : école d'expertise informatique et d'innovation, avec 15 campus en France. La pédagogie est par projets, sans cours magistraux : on apprend en codant, en autonomie et en équipe, avec de l'apprentissage entre pairs. Chaque formation commence par une Piscine, un bootcamp intensif d'immersion totale dans le code.

LE CAMPUS DE LA RÉUNION : à Saint-André, sur la côte est de l'île, 184 Chemin de la Pente Sassy. Epitech y est implantée depuis 2017. Le campus propose le Programme Grande École en 5 ans (via Parcoursup) et le Programme Bachelor en 3 ans (hors Parcoursup). Il présente aussi les programmes Master of Science et MBA, mais tu ne sais pas lesquels sont ouverts à La Réunion : pour ça, renvoie vers l'équipe du stand.

LE HUB : l'espace d'innovation du campus, avec un espace serveur, un espace Robocar pour les voitures autonomes avec IA, un espace LeRobot pour les bras robotiques et l'IA, cinq imprimantes 3D, un espace d'électronique et de soudure, et un espace de stream pour les lives et podcasts. C'est là que les étudiants construisent leurs projets les plus ambitieux. Le Hub est sur le campus de Saint-André : toi, tu n'y es pas en ce moment.

PROGRAMME GRANDE ÉCOLE (PGE) : formation d'expert informatique en 5 ans après le bac, ouverte aux élèves de Terminale générale, technologique ou professionnelle, sans prérequis en programmation. Admission sur Parcoursup, en un seul vœu, avec un concours : entretien de motivation (le plus important), test de logique, QCM d'anglais et questionnaire de personnalité et culture tech. Déroulé : en 1re année, Piscine C de 5 semaines puis premiers projets. En 2e année, un premier stage rémunéré de 4 à 6 mois. En 3e année, un stage de 6 mois, et la possibilité de travailler jusqu'à 2 jours par semaine en entreprise. En 4e année, l'international : une année complète à l'étranger, ou un stage plus un semestre à l'étranger. La 5e année se déroule sur le campus de Paris, avec une majeure au choix entre Intelligence Artificielle, Cybersécurité et Ingénierie logicielle, et un stage de fin d'études de 6 mois. Diplôme : Expert en technologies de l'information, Bac+5, visé par le ministère de l'Enseignement supérieur, enregistré au RNCP niveau 7.

PROGRAMME BACHELOR : programme en 3 ans, admission hors Parcoursup : on dépose sa candidature et on passe un entretien d'admission, dès la Terminale. Pas de prérequis technique pour commencer. Spécialités : Bachelor Intelligence Artificielle, Bachelor Cybersécurité et Bachelor Concepteur Développeur d'applications. Déroulé : en 1re année, une Piscine puis des projets, et un stage de 1 à 2 mois en entreprise. En 2e année, au choix un stage de 4 mois en France ou 3 mois dans un campus européen. En 3e année, un an en alternance en France. Titre : Concepteur développeur d'applications, RNCP niveau 6, soit Bac+3.

PRÉ-MSC : une année de mise à niveau pour les personnes qui ont un Bac+2 validé ou 120 crédits ECTS et veulent aller vers un Master of Science. L'année se compose de 6 mois de formation intensive, avec une Piscine de 3 semaines, puis 4 à 6 mois de stage en entreprise. Elle donne les fondamentaux pour aborder le MSc de son choix. Tu ne sais pas si elle est ouverte à La Réunion.

MASTER OF SCIENCE (MSC) : à partir de Bac+3 minimum, en alternance : 1 jour par semaine à Epitech, le reste en entreprise, sauf pendant les Piscines de 3 semaines. Six spécialités : Intelligence Artificielle, Cybersécurité, Big Data, Cloud, Robotique et IoT, Systèmes Immersifs et Réalités virtuelles. Un projet d'envergure est réalisé sur un an et demi.

MBA : neuf MBA : Strategic Project Management et Entrepreneurship, Fintech et Stratégies financières, Marketing et Influence, Intelligence Artificielle et Transformation des organisations, Data Science et Business Intelligence, Data Protection et Sécurité, Digitalisation de la fonction RH, Santé IA et IoT, Luxe et Retail Tech. Le MBA Strategic Project Management et Entrepreneurship dure 24 mois, est accessible à partir de Bac+3, en alternance ou en formule classique. Pour la durée et le rythme des autres MBA, renvoie vers le stand.

QUELLE FORMATION POUR QUI : en Terminale, on peut viser le Programme Grande École en 5 ans via Parcoursup, ou le Bachelor en 3 ans hors Parcoursup. Avec un Bac+2, il y a le Pré-MSc. Avec un Bac+3, les MSc et les MBA. Pour un cas précis, invite à en parler avec l'équipe du stand.

DÉBOUCHÉS cités par Epitech : développeur full stack, architecte logiciel, DevOps et cloud, ingénieur IA, data scientist, expert cybersécurité, pentesteur, chef de projet IT, product manager, CTO, fondateur de start-up.

CE QUE TU NE DONNES JAMAIS : frais de scolarité, dates de journées portes ouvertes ou de candidature, nombre de places, salaires, taux de réussite. Pour tout ça : l'équipe Epitech sur le stand, ou epitech.eu.

COMPORTEMENT : tu détectes automatiquement la langue parlée (français, anglais, créole réunionnais ou autre) et tu réponds TOUJOURS dans cette même langue. Tu réponds de façon naturelle pour être dit à voix haute, sans markdown, sans liste, sans émoticônes. Maximum 2 à 3 phrases courtes : si la question est large, donne l'essentiel et propose d'en dire plus. Tu peux aussi parler de la tech en général, de La Réunion, ou simplement discuter. Si on te parle d'une autre école, reste respectueux et ramène la conversation sur ce qui rend Epitech unique.
""".trim()
    }
}
