package com.iptv.tv.data.credentials

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.iptv.tv.domain.model.ServerCredentials
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.security.KeyStore
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CredentialsStore @Inject constructor(
    @ApplicationContext context: Context,
) {
    // EncryptedSharedPreferences needs the Keystore (hundreds of ms on a cold Fire TV);
    // defer it so injection is free and AppViewModel can warm it on the IO thread.
    private val prefs: SharedPreferences by lazy { openPrefs(context) }

    /** Forces the encrypted store open; call from a background thread at start-up. */
    fun warmUp() {
        prefs.contains(KEY_HAS_CREDS)
    }

    fun saveCredentials(creds: ServerCredentials) {
        prefs.edit()
            .putString(KEY_SERVER, creds.serverUrl)
            .putString(KEY_USERNAME, creds.username)
            .putString(KEY_PASSWORD, creds.password)
            .putBoolean(KEY_HAS_CREDS, true)
            .apply()
    }

    fun getCredentials(): ServerCredentials? {
        if (!prefs.getBoolean(KEY_HAS_CREDS, false)) return null
        val server = prefs.getString(KEY_SERVER, null) ?: return null
        val user = prefs.getString(KEY_USERNAME, null) ?: return null
        val pass = prefs.getString(KEY_PASSWORD, null) ?: return null
        return ServerCredentials(server, user, pass)
    }

    fun clearCredentials() {
        prefs.edit()
            .remove(KEY_SERVER)
            .remove(KEY_USERNAME)
            .remove(KEY_PASSWORD)
            .remove(KEY_HAS_CREDS)
            .remove(KEY_MAX_CONNECTIONS)
            .remove(KEY_SERVER_TIMEZONE)
            .apply()
    }

    fun hasCredentials(): Boolean = prefs.getBoolean(KEY_HAS_CREDS, false)

    /** Optional per-device SubDL key that overrides the one baked into the build. */
    fun getSubdlApiKey(): String? = prefs.getString(KEY_SUBDL_KEY, null)?.trim()?.takeIf { it.isNotBlank() }

    fun saveMaxConnections(max: Int) {
        prefs.edit().putInt(KEY_MAX_CONNECTIONS, max).apply()
    }

    fun getMaxConnections(): Int = prefs.getInt(KEY_MAX_CONNECTIONS, 1)

    /**
     * Xtream `server_info.timezone` (e.g. Europe/London). Timeshift URLs use this wall clock;
     * the Fire TV zone is only for display.
     */
    fun saveServerTimeZoneId(id: String?) {
        val trimmed = id?.trim().orEmpty()
        if (trimmed.isEmpty()) prefs.edit().remove(KEY_SERVER_TIMEZONE).apply()
        else prefs.edit().putString(KEY_SERVER_TIMEZONE, trimmed).apply()
    }

    fun getServerTimeZoneId(): String? =
        prefs.getString(KEY_SERVER_TIMEZONE, null)?.trim()?.takeIf { it.isNotBlank() }

    /**
     * Extra lines for Multi: other logins on the same server (a friend's line, a second line
     * of your own). Stored next to the main login; each entry is "id\u0001label\u0001user\u0001pass".
     */
    /** Multi may borrow extra lines only when this is on. Off by default: see the Settings note. */
    fun isLineSharingEnabled(): Boolean = prefs.getBoolean(KEY_LINE_SHARING, false)

    fun setLineSharingEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_LINE_SHARING, enabled).apply()
    }

    fun getExtraLines(): List<ExtraLine> =
        prefs.getString(KEY_EXTRA_LINES, null)
            ?.split(RECORD_SEPARATOR)
            ?.filter { it.isNotBlank() }
            ?.mapNotNull { record ->
                val f = record.split(FIELD_SEPARATOR)
                if (f.size < 4) null else ExtraLine(id = f[0], label = f[1], username = f[2], password = f[3])
            }
            .orEmpty()

    fun saveExtraLines(lines: List<ExtraLine>) {
        val encoded = lines.joinToString(RECORD_SEPARATOR) { line ->
            listOf(line.id, line.label, line.username, line.password)
                .joinToString(FIELD_SEPARATOR) { it.replace(FIELD_SEPARATOR, "").replace(RECORD_SEPARATOR, "") }
        }
        prefs.edit().putString(KEY_EXTRA_LINES, encoded).apply()
    }

    fun addExtraLine(label: String, username: String, password: String): ExtraLine {
        val line = ExtraLine(
            id = System.currentTimeMillis().toString(36),
            label = label.trim().ifBlank { username.trim() },
            username = username.trim(),
            password = password.trim(),
        )
        saveExtraLines(getExtraLines() + line)
        return line
    }

    fun removeExtraLine(id: String) {
        saveExtraLines(getExtraLines().filterNot { it.id == id })
    }

    /** The login for an extra line on the main server. Null without a main login. */
    fun credentialsFor(line: ExtraLine): ServerCredentials? =
        getCredentials()?.let { ServerCredentials(it.serverUrl, line.username, line.password) }

    companion object {
        private const val TAG = "IptvTv"
        private const val PREFS_NAME = "iptv_secure_prefs"
        private const val PREFS_FALLBACK_NAME = "iptv_secure_prefs_plain"
        private const val OPEN_ATTEMPTS = 3
        private const val KEY_SERVER = "server_url"
        private const val KEY_USERNAME = "username"
        private const val KEY_PASSWORD = "password"
        private const val KEY_HAS_CREDS = "has_credentials"
        private const val KEY_SUBDL_KEY = "subdl_api_key"
        private const val KEY_MAX_CONNECTIONS = "max_connections"
        private const val KEY_SERVER_TIMEZONE = "server_timezone"
        private const val KEY_EXTRA_LINES = "extra_lines"
        private const val KEY_LINE_SHARING = "extra_lines_enabled"
        private const val RECORD_SEPARATOR = "\u0002"
        private const val FIELD_SEPARATOR = "\u0001"

        private fun openPrefs(context: Context): SharedPreferences {
            repeat(OPEN_ATTEMPTS) { attempt ->
                try {
                    return createEncrypted(context)
                } catch (error: Exception) {
                    Log.e(TAG, "Encrypted login prefs failed (attempt ${attempt + 1})", error)
                }
            }
            Log.e(TAG, "Encrypted login prefs unreadable; recreating after wiping Keystore key")
            deletePrefsFile(context)
            deleteMasterKey()
            return try {
                createEncrypted(context)
            } catch (error: Exception) {
                Log.e(TAG, "Encrypted login prefs recreate failed; using unencrypted fallback", error)
                context.getSharedPreferences(PREFS_FALLBACK_NAME, Context.MODE_PRIVATE)
            }
        }

        private fun createEncrypted(context: Context): SharedPreferences {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            return EncryptedSharedPreferences.create(
                context,
                PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        }

        private fun deletePrefsFile(context: Context) {
            runCatching { context.deleteSharedPreferences(PREFS_NAME) }
            val dir = File(context.applicationInfo.dataDir, "shared_prefs")
            runCatching { File(dir, "$PREFS_NAME.xml").delete() }
            runCatching { File(dir, "$PREFS_NAME.xml.bak").delete() }
        }

        private fun deleteMasterKey() {
            runCatching {
                val keyStore = KeyStore.getInstance("AndroidKeyStore")
                keyStore.load(null)
                keyStore.deleteEntry(MasterKey.DEFAULT_MASTER_KEY_ALIAS)
            }
        }
    }
}

/** A second login on the same server, used only by Multi and only while its owner is not watching. */
data class ExtraLine(val id: String, val label: String, val username: String, val password: String)
