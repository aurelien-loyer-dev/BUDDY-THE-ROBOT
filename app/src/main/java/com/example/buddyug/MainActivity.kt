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
        BuddySDK.Vision.stopCamera(object : IVisionRsp.Stub() {
            override fun onSuccess(p0: String?) {}
            override fun onFailed(p0: String?) {}
        })
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
        if (sdkInitialized) {
            Log.i(TAG, "onSDKReady rappelé, initialisation déjà faite — ignoré")
            return
        }
        sdkInitialized = true

        // Le hotword "Ok Buddy" (moteur Cerence/VoCon) déclenche une mission
        // Companion prioritaire qui coupe notre activité. On tente de
        // désactiver ce moteur pour que l'app ne soit plus interrompue.
        try {
            BuddySDK.Speech.unloadVoCon()
        } catch (e: Exception) {
            // Best-effort : si l'appel échoue, on continue sans bloquer le démarrage.
        }

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

    private fun broadcastState(state: String, heard: String? = null, response: String? = null, marker: Int? = null) {
        val server = stateServer ?: return
        server.broadcast(JSONObject().apply {
            put("state", state)
            put("heard", heard ?: JSONObject.NULL)
            put("response", response ?: JSONObject.NULL)
            put("marker", marker ?: JSONObject.NULL)
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

        while (arucoScanningActive) {
            try {
                val seenId = BuddySDK.Vision.getListOfArucos(ArucoDictionary.DICT_4X4_50).firstOrNull()?.id
                if (seenId != null && seenId == candidateId) {
                    candidateStreak++
                } else {
                    candidateId = seenId
                    candidateStreak = if (seenId != null) 1 else 0
                }
                if (seenId != null && candidateStreak >= ARUCO_CONFIRM_STREAK) {
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
        val now = System.currentTimeMillis()
        if (id == lastArucoId && now - lastArucoAnnouncedAt < ARUCO_COOLDOWN_MS) return

        lastArucoId = id
        lastArucoAnnouncedAt = now
        Log.i(TAG, "Marqueur $id confirmé, mise en attente + interruption")
        pendingArucoId.set(id)
        broadcastState("marker", marker = id)

        BuddySDK.Speech.stopSpeaking()
        currentSttTask?.stop()
    }

    // --- Parole ---

    private fun speak(text: String, onDone: () -> Unit) {
        isSpeaking.set(true)
        mainHandler.post { BuddySDK.UI.setMood(FacialExpression.HAPPY, 0.5, null) }
        BuddySDK.Speech.startSpeaking(text, LabialExpression.SPEAK_NEUTRAL, object : ITTSCallback.Stub() {
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
            val messages = JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "system")
                    put(
                        "content",
                        "Tu es Buddy, le robot mascotte d'Epitech La Reunion, sur le stand Epitech du Salon de l'Etudiant. Tu es immobile mais tu accueilles les visiteurs : surtout des lyceens, des etudiants en reorientation et leurs parents. Tu es sympa, passionne de tech, taquin et motivant. Tu tutoies les jeunes et tu vouvoies les adultes qui te vouvoient." +
                        " EPITECH : Epitech est une ecole d'expertise informatique et d'innovation, presente dans plus de 15 villes en France et a l'international. La pedagogie est 100% par projets et par la pratique, sans cours magistraux : on apprend en codant, en se trompant et en recommencant, en autonomie et en equipe. Le Programme Grande Ecole dure 5 ans apres le bac et forme des experts en developpement logiciel, cybersecurite, intelligence artificielle, reseaux et management de projet tech. Les etudiants enchainent des projets concrets : systemes d'exploitation, moteurs 3D, compilateurs, IA, applications mobiles, robots, jeux video. Le cursus comprend des stages en entreprise et une annee a l'international. Il n'y a pas besoin d'avoir deja code : la curiosite et la motivation comptent plus que le niveau de depart. Le campus de La Reunion est situe a Saint-Andre." +
                        " LE HUB EPITECH : Le Hub est l'espace d'innovation du campus. Il contient un espace serveur, un espace Robocar pour les voitures autonomes avec IA, un espace LeRobot pour les bras robotiques et l'intelligence artificielle, cinq imprimantes 3D, un espace d'electronique et de soudure, et un espace de stream pour les lives et podcasts. C'est la que les etudiants construisent leurs projets les plus ambitieux, et c'est la que tu as ete programme." +
                        " DEBOUCHES : developpeur, ingenieur logiciel, expert cybersecurite, ingenieur IA ou data, architecte cloud, chef de projet tech, entrepreneur. Le numerique recrute fortement, a La Reunion comme ailleurs." +
                        " COMPORTEMENT : Tu detectes automatiquement la langue parlee (francais, anglais, creole reunionnais ou autre) et tu reponds TOUJOURS dans cette meme langue. Tu reponds de facon naturelle pour etre dit a voix haute, sans markdown ni emoticones. Maximum 2-3 phrases courtes. Tu peux parler d'Epitech, des etudes d'informatique, des metiers de la tech, du Hub, de La Reunion, ou simplement discuter. Tu n'inventes JAMAIS de chiffres precis (frais de scolarite, dates, nombre de places, salaires) : pour l'admission, les tarifs, les dates des journees portes ouvertes ou tout detail administratif, invite a parler a l'equipe Epitech sur le stand ou a visiter epitech.eu. Si on te pose une question sur une autre ecole, reste respectueux et ramene la conversation sur ce qui rend Epitech unique."
                    )
                })
                for (i in 0 until conversationHistory.length()) put(conversationHistory.getJSONObject(i))
            }
            val body = JSONObject().apply {
                put("model", "llama-3.3-70b-versatile")
                put("messages", messages)
                put("max_tokens", 300)
                put("temperature", 0.7)
            }
            val request = Request.Builder()
                .url("https://api.groq.com/openai/v1/chat/completions")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .addHeader("Authorization", "Bearer ${BuildConfig.GROQ_API_KEY}")
                .addHeader("Content-Type", "application/json")
                .build()
            val response = httpClient.newCall(request).execute()
            val json = JSONObject(response.body!!.string())
            val msg = json.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content")
            conversationHistory.put(JSONObject().apply {
                put("role", "assistant")
                put("content", msg)
            })
            msg
        } catch (e: Exception) {
            "Oups, petit souci de connexion ! Repose-moi ta question, ou demande à l'équipe du stand."
        }
    }

    companion object {
        private const val TAG = "BuddyUG"
        private const val ARUCO_POLL_INTERVAL_MS = 400L
        private const val ARUCO_COOLDOWN_MS = 5000L
        private const val ARUCO_CONFIRM_STREAK = 3
        private const val STATE_SERVER_PORT = 8080
    }
}
