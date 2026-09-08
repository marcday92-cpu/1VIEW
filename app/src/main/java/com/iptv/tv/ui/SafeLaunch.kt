package com.iptv.tv.ui

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Launches work that must never take the app down. Transient feed, EPG and
 * storage failures are expected on an IPTV client, so they are logged and
 * reported rather than thrown.
 */
fun ViewModel.launchSafely(
    tag: String,
    onError: ((Throwable) -> Unit)? = null,
    block: suspend CoroutineScope.() -> Unit,
): Job = viewModelScope.launch {
    try {
        block()
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: Throwable) {
        // Never log the raw throwable: OkHttp/Retrofit messages carry the full request URL,
        // which for Xtream servers embeds the username and password.
        Log.e("IptvTv", "$tag failed: ${SecretRedactor.describe(error)}")
        onError?.invoke(error)
    }
}
