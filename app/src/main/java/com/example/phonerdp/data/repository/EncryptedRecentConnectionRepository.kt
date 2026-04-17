package com.example.phonerdp.data.repository

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.example.phonerdp.domain.model.ConnectionConfig
import com.example.phonerdp.domain.usecase.UpsertRecentConnectionUseCase
import org.json.JSONArray
import org.json.JSONObject

class EncryptedRecentConnectionRepository(
    context: Context,
    private val upsertUseCase: UpsertRecentConnectionUseCase = UpsertRecentConnectionUseCase(),
) : RecentConnectionRepository {
    private val appContext = context.applicationContext
    private val lock = Any()
    private val encryptedPrefs: SharedPreferences? by lazy { createEncryptedPreferences() }

    override fun getRecentConnections(): List<ConnectionConfig> = synchronized(lock) {
        val prefs = encryptedPrefs ?: return@synchronized emptyList()
        val encoded = prefs.getString(KEY_RECENT_CONNECTIONS, "[]").orEmpty()
        decodeConnections(encoded)
    }

    override fun saveConnection(config: ConnectionConfig) = synchronized(lock) {
        val prefs = encryptedPrefs ?: return@synchronized
        val current = decodeConnections(prefs.getString(KEY_RECENT_CONNECTIONS, "[]").orEmpty())
        val updated = upsertUseCase(current, config, maxCount = 5)
        prefs.edit().putString(KEY_RECENT_CONNECTIONS, encodeConnections(updated)).apply()
    }

    private fun createEncryptedPreferences(): SharedPreferences? {
        return runCatching {
            val masterKey = MasterKey.Builder(appContext)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()

            EncryptedSharedPreferences.create(
                appContext,
                PREFS_FILE_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        }.onFailure { error ->
            Log.e(TAG, "Failed to initialize encrypted recent repository", error)
        }.getOrNull()
    }

    private fun encodeConnections(items: List<ConnectionConfig>): String {
        val array = JSONArray()
        items.forEach { config ->
            val obj = JSONObject()
                .put("host", config.host)
                .put("port", config.port)
                .put("username", config.username)
                .put("password", config.password)

            if (!config.domain.isNullOrBlank()) {
                obj.put("domain", config.domain)
            }

            array.put(obj)
        }
        return array.toString()
    }

    private fun decodeConnections(raw: String): List<ConnectionConfig> {
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val host = item.optString("host")
                    val port = item.optInt("port", 3389)
                    val username = item.optString("username")
                    val password = item.optString("password")
                    val domain = item.optString("domain").ifBlank { null }

                    if (host.isBlank() || username.isBlank() || password.isBlank()) {
                        continue
                    }

                    add(
                        ConnectionConfig(
                            host = host,
                            port = port,
                            username = username,
                            password = password,
                            domain = domain
                        )
                    )
                }
            }
        }.onFailure { error ->
            Log.w(TAG, "Failed to decode recent connections; resetting cache", error)
        }.getOrDefault(emptyList())
    }

    companion object {
        private const val TAG = "RecentRepo"
        private const val PREFS_FILE_NAME = "rdp_secure_recent"
        private const val KEY_RECENT_CONNECTIONS = "recent_connections_v1"
    }
}
