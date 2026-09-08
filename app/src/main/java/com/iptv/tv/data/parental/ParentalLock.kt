package com.iptv.tv.data.parental

import com.iptv.tv.data.preferences.AppPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ParentalLock @Inject constructor(
    private val preferences: AppPreferences,
) {
    private val _unlocked = MutableStateFlow(false)
    val unlocked: StateFlow<Boolean> = _unlocked.asStateFlow()

    suspend fun hasPin(): Boolean = !preferences.parentalPinHash.first().isNullOrBlank()

    suspend fun setPin(pin: String) {
        require(pin.length in 4..8 && pin.all { it.isDigit() }) { "PIN must be 4–8 digits" }
        preferences.setParentalPinHash(sha256(pin))
        _unlocked.value = false
    }

    /** Only someone who knows the current PIN may replace it. */
    suspend fun changePin(current: String, next: String): Boolean {
        if (!verify(current)) return false
        setPin(next)
        return true
    }

    suspend fun clearPin(current: String): Boolean {
        if (!verify(current)) return false
        preferences.setParentalPinHash(null)
        _unlocked.value = true
        return true
    }

    suspend fun verify(pin: String): Boolean {
        val stored = preferences.parentalPinHash.first() ?: return false
        val ok = stored == sha256(pin)
        if (ok) _unlocked.value = true
        return ok
    }

    fun lock() {
        _unlocked.value = false
    }

    suspend fun adultBlocked(): Boolean = hasPin() && !_unlocked.value

    private fun sha256(value: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
