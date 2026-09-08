package com.iptv.tv

import android.app.SearchManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.iptv.tv.player.PlayerChannelKeys
import com.iptv.tv.ui.TvApp
import com.iptv.tv.ui.search.VoiceSearchController
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var voiceSearch: VoiceSearchController

    /**
     * The latest intent, observed by [TvApp]. Kept as Compose state so a reminder or
     * search intent arriving while the app is open updates the running composition
     * instead of replacing it (which used to reset every screen).
     */
    private var currentIntent by mutableStateOf<Intent?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 100)
        }
        handleSearchIntent(intent)
        currentIntent = intent
        setContent {
            TvApp(deepLinkIntent = currentIntent)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.action == Intent.ACTION_ASSIST) {
            voiceSearch.onVoiceKey()
            return
        }
        handleSearchIntent(intent)
        currentIntent = intent
    }

    // Lint flags super.dispatchKeyEvent as a restricted androidx.core API; overriding it on an
    // Activity is the documented way to see remote keys first, so the warning is a false positive.
    @Suppress("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (VoiceSearchController.isVoiceKey(event.keyCode)) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                voiceSearch.onVoiceKey()
            }
            return true
        }
        // Live channel up/down (rocker and D-pad) before overlay buttons swallow them.
        if (PlayerChannelKeys.dispatch(event)) return true
        return super.dispatchKeyEvent(event)
    }

    override fun onSearchRequested(): Boolean {
        voiceSearch.onVoiceKey()
        return true
    }

    private fun handleSearchIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEARCH) {
            val q = intent.getStringExtra(SearchManager.QUERY).orEmpty()
            if (q.isNotBlank()) voiceSearch.onSpokenOrTypedQuery(q)
            else voiceSearch.onVoiceKey()
        }
    }
}
