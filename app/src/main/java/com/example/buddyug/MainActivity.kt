package com.example.buddyug

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import com.bfr.buddy.speech.shared.ISTTCallback
import com.bfr.buddy.speech.shared.ITTSCallback
import com.bfr.buddy.speech.shared.STTResultsData
import com.bfr.buddy.ui.shared.FacialExpression
import com.bfr.buddy.ui.shared.LabialExpression
import com.bfr.buddy.vision.shared.IVisionRsp
import com.bfr.buddy.vision.shared.enums.ArucoDictionary
import com.bfr.buddysdk.BuddyActivity
import com.bfr.buddysdk.BuddySDK
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
 * Activité principale de l'application Buddy pour le salon Epitech.
 *
 * Buddy reste immobile : deux fonctionnalités actives en parallèle —
 * une boucle de questions/réponses vocales (STT → Groq → TTS), et un
 * scan continu de marqueurs ArUco (BuddySDK.Vision) qui annonce à voix
 * haute leur numéro.
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

        startArucoScanner()
        Thread { startConversationLoop() }.start()
    }

    // --- Scan de marqueurs ArUco (BuddySDK.Vision) ---

    private fun startArucoScanner() {
        BuddySDK.Vision.startCamera(object : IVisionRsp.Stub() {
            override fun onSuccess(p0: String?) {
                arucoScanningActive = true
                Thread { arucoPollingLoop() }.start()
            }

            override fun onFailed(p0: String?) {
                // Caméra indisponible : le scan est simplement désactivé.
            }
        })
    }

    private fun arucoPollingLoop() {
        while (arucoScanningActive) {
            val marker = BuddySDK.Vision.getListOfArucos(ArucoDictionary.DICT_4X4_50).firstOrNull()
            if (marker != null) onArucoDetected(marker.id)
            Thread.sleep(ARUCO_POLL_INTERVAL_MS)
        }
    }

    /**
     * Marque le marqueur comme "à annoncer" plutôt que de parler immédiatement :
     * la boucle Q&A (STT/TTS) est occupée la plupart du temps, donc annoncer ici
     * directement finirait presque toujours ignoré. C'est la boucle de
     * conversation qui délivre l'annonce dès qu'elle redevient libre.
     */
    private fun onArucoDetected(id: Int) {
        val now = System.currentTimeMillis()
        if (id == lastArucoId && now - lastArucoAnnouncedAt < ARUCO_COOLDOWN_MS) return

        lastArucoId = id
        lastArucoAnnouncedAt = now
        pendingArucoId.set(id)
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
        val warmupTask = BuddySDK.Speech.createGoogleSTTTask(Locale.FRENCH)
        try {
            warmupTask.initialize()
        } catch (e: Exception) {
            // Warmup best-effort : si ça échoue, le premier vrai appel STT sera juste plus lent.
        }
        speakSync("Bonjour ! Je suis Buddy, le robot d'Epitech. Posez-moi une question, ou montrez-moi un marqueur !")

        while (true) {
            if (isPaused) {
                Thread.sleep(500)
                continue
            }
            pendingArucoId.getAndSet(null)?.let { id ->
                speakSync("J'ai détecté le marqueur numéro $id")
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
        }
    }

    private fun listenOnce(): String? {
        if (!isListening.compareAndSet(false, true)) return null
        var result: String? = null
        var done = false
        try {
            val sttTask = BuddySDK.Speech.createGoogleSTTTask(Locale.FRENCH)
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
                        "Tu es Buddy, le robot mascotte d'Epitech, present sur un salon a la " +
                            "prefecture de La Reunion. Tu es immobile. Tu es sympa, passionne de tech " +
                            "et motivant. Tu detectes automatiquement la langue parlee et tu reponds " +
                            "TOUJOURS dans cette meme langue. Tu reponds de facon naturelle pour etre " +
                            "dit a voix haute, sans markdown ni emoticones. Maximum 2-3 phrases."
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
            "Desole, je n'ai pas pu repondre."
        }
    }

    companion object {
        private const val ARUCO_POLL_INTERVAL_MS = 400L
        private const val ARUCO_COOLDOWN_MS = 5000L
    }
}
