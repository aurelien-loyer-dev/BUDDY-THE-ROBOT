package com.example.buddyug

import android.os.Bundle
import com.bfr.buddysdk.BuddyActivity
import com.bfr.buddysdk.BuddySDK
import com.bfr.buddysdk.FacialExpression

/**
 * Activité principale de l'application Buddy.
 *
 * Hérite de BuddyActivity (jamais AppCompatActivity) pour recevoir le
 * cycle de vie du SDK. Tout appel à BuddySDK doit se faire dans
 * onSDKReady(), jamais dans onCreate() — le SDK n'est pas encore prêt.
 */
class MainActivity : BuddyActivity() {

    /**
     * Pose le layout contenant la vue du visage.
     *
     * @param savedInstanceState état précédent de l'activité, le cas échéant.
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
    }

    /**
     * Callback appelé quand le SDK Buddy est initialisé.
     *
     * Branche la vue du visage et fixe l'expression par défaut.
     */
    override fun onSDKReady() {
        BuddySDK.UI.setViewAsFace(findViewById(R.id.view_face))
        BuddySDK.UI.setMood(FacialExpression.NEUTRAL, 0.5, null)
    }
}
