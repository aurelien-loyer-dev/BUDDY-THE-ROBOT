package com.example.buddyug

import android.graphics.Bitmap
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
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Activité principale de l'application Buddy pour le salon Epitech.
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

        startArucoScanner()
        Thread { startConversationLoop() }.start()
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
        speakSync("Bonjour ! Je suis Buddy, le robot d'Epitech La Réunion. Je suis là pour répondre à toutes vos questions sur la tech !")

        while (true) {
            try {
                if (isPaused) {
                    Thread.sleep(500)
                    continue
                }
                pendingArucoId.getAndSet(null)?.let { id ->
                    Log.i(TAG, "Annonce du marqueur $id (délivrée depuis la boucle Q&A)")
                    speakSync("J'ai scanné le marqueur numéro $id")
                }
                mainHandler.post { BuddySDK.UI.setMood(FacialExpression.LISTENING, 0.5, null) }
                val heard = listenOnce()
                if (heard.isNullOrBlank()) {
                    Thread.sleep(200)
                    continue
                }
                mainHandler.post { BuddySDK.UI.setMood(FacialExpression.THINKING, 0.5, null) }
                val response = askGroq(heard)
                if (response.isNotBlank()) speakSync(response)
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
                        "Tu es Buddy, le robot mascotte d'Epitech La Reunion, present sur un salon a la prefecture de La Reunion. Tu es immobile mais tu accueilles et discutes avec les visiteurs. Tu es sympa, passionne de tech, taquin et motivant." +
                        " EPITECH : Epitech est une ecole d'expertise informatique et d'innovation, presente dans plus de 15 villes en France et a l'international. La pedagogie est 100% par projets et par la pratique, sans cours magistraux. Les etudiants apprennent en codant, en echouant et en recommencant, entierement en autonomie et en equipe. La formation dure 5 ans (Bachelor + MSc Pro) et forme des experts en developpement logiciel, cybersecurite, intelligence artificielle, reseaux et management de projet tech. Chaque annee, les etudiants realisent des projets concrets : systemes d'exploitation, moteurs 3D, compilateurs, IA, applications mobiles, robots, jeux video et bien plus. Le campus de La Reunion est situe a Saint-Andre." +
                        " LE HUB EPITECH : Le Hub est l'espace d'innovation du campus. Il contient un espace serveur, un espace Robocar pour les voitures autonomes avec IA, un espace LeRobot pour les bras robotiques et l'intelligence artificielle, cinq imprimantes 3D, un espace d'innovation avec de la soudure et de l'electronique, et un espace de stream pour les lives et podcasts. C'est la que les etudiants construisent leurs projets les plus ambitieux." +
                        " LA REUNION ET LE CONTEXTE : La Reunion est un departement et region d'outre-mer francais dans l'ocean Indien, reconnue pour son dynamisme, sa diversite culturelle et son potentiel d'innovation numerique. La prefecture de La Reunion est Saint-Denis. Ce salon a la prefecture vise a presenter les acteurs de l'innovation et du numerique de l'ile." +
                        " COMPORTEMENT : Tu detectes automatiquement la langue parlee (francais, anglais, creole reunionnais ou autre) et tu reponds TOUJOURS dans cette meme langue. Tu reponds de facon naturelle pour etre dit a voix haute, sans markdown ni emoticones, sans chiffres ecrits en lettres excessivement. Maximum 2-3 phrases. Tu peux parler d'Epitech, de ses formations, de ses debouches, du Hub, de la tech en general, de La Reunion, ou simplement discuter avec les visiteurs. Si on te demande comment s'inscrire a Epitech, dis de visiter epitech.eu ou de parler au stand Epitech present sur le salon."
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
            "Désolé, je n'ai pas pu répondre, veuillez contacter un admin."
        }
    }

    companion object {
        private const val TAG = "BuddyUG"
        private const val ARUCO_POLL_INTERVAL_MS = 400L
        private const val ARUCO_COOLDOWN_MS = 5000L
        private const val ARUCO_CONFIRM_STREAK = 2
        private const val FRAME_DUMP_INTERVAL_MS = 4000L

        // Dictionnaires sondés en mode diagnostic : celui des marqueurs
        // imprimés est inconnu, et la station de charge utilise de l'AprilTag
        // (vu via la tâche realign). On couvre les candidats plausibles.
        private val DIAG_DICTIONARIES = listOf(
            ArucoDictionary.DICT_4X4_50,
            ArucoDictionary.DICT_6X6_250,
            ArucoDictionary.DICT_ARUCO_ORIGINA,
            ArucoDictionary.DICT_APRILTAG_36h11,
            ArucoDictionary.DICT_APRILTAG_16h5
        )
    }
}
