package com.example.buddyug

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import com.bfr.buddy.speech.shared.ISTTCallback
import com.bfr.buddy.speech.shared.ITTSCallback
import com.bfr.buddy.speech.shared.STTResultsData
import com.bfr.buddy.ui.shared.FacialExpression
import com.bfr.buddy.ui.shared.LabialExpression
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

    override fun onSDKReady() {
        BuddySDK.UI.setViewAsFace(findViewById(R.id.view_face))
        BuddySDK.UI.setMood(FacialExpression.NEUTRAL, 0.5, null)

        Thread { startConversationLoop() }.start()
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
        speakSync("Bonjour ! Je suis Buddy, le robot d'Epitech. Je suis là pour répondre à toutes vos questions sur la tech !")

        while (true) {
            if (isPaused) {
                Thread.sleep(500)
                continue
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
}
