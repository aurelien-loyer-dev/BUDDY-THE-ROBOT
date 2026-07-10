package com.example.buddyug

import android.os.Bundle
import com.bfr.buddysdk.BuddyActivity
import com.bfr.buddysdk.BuddySDK
import com.bfr.buddysdk.FacialExpression

class MainActivity : BuddyActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
    }

    override fun onSDKReady() {
        BuddySDK.UI.setViewAsFace(findViewById(R.id.view_face))
        BuddySDK.UI.setMood(FacialExpression.NEUTRAL, 0.5, null)
    }
}
