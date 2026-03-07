package com.phoncoin.wallet.core.storage

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.util.UUID

class SecureStore(context: Context) {

    private val appContext = context.applicationContext
    private val prefsName = "phoncoin_secure_store"

    private val prefs: SharedPreferences = createEncryptedPrefs()

    private val installIdLock = Any()

    private fun createEncryptedPrefs(): SharedPreferences {
        fun build(): SharedPreferences {
            val masterKey = MasterKey.Builder(appContext)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()

            return EncryptedSharedPreferences.create(
                appContext,
                prefsName,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        }

        return try {
            build()
        } catch (_: Exception) {
            // If keystore/prefs are corrupted: wipe and rebuild (avoids boot crash)
            appContext.deleteSharedPreferences(prefsName)
            build()
        }
    }

    private 
    /** Expected network fingerprint (genesis hash) used to reject fake nodes. */
    fun expectedGenesisHash(): String = EXPECTED_GENESIS_HASH

companion object {
        const val EXPECTED_GENESIS_HASH: String = "c098fa5e985edd56634af262975b771f0dadc607494d6875cb725f1006611658"

        const val KEY_SEED = "seed"

        // Manual override (if the user forces a node)
        const val KEY_NODE_URL = "node_url"

        // Install ID stable (audit-friendly, anti-bot simple)
        const val KEY_INSTALL_ID = "install_id"

        // Cache de la liste nodes (issue de nodes.json)
        const val KEY_BOOTSTRAP_CACHE_CSV = "bootstrap_cache_nodes_csv"
        const val KEY_BOOTSTRAP_CACHE_TS = "bootstrap_cache_ts_ms"

        /**
         * ✅ The wallet NEVER knows A.
         * ✅ The wallet never knows the IPs.
         *
         * The wallet only knows "bootstrap index" DNS endpoints.
         * nodes.json renvoie ensuite la liste des nodes (api.*, seed2.*, seed3.*...)
         */
        val DEFAULT_BOOTSTRAP_INDEX_URLS = listOf(
            "https://bootstrap.phonchain.org/nodes.json",
            // Possible fallbacks (optional but robust to avoid a SPOF)
            "https://api.phonchain.org/nodes.json",
            "https://seed2.phonchain.org/nodes.json"
        )

        /**
         * Last resort (DNS only) if nodes.json is down AND the cache is empty.
         * -> toujours sans IP, et toujours sans A.
         */
        val EMERGENCY_FALLBACK_NODES = listOf(
            "https://api.phonchain.org",
            "https://seed2.phonchain.org"
        )
    }

    // -------- Wallet seed --------

    fun saveSeed(seed: String) {
        prefs.edit().putString(KEY_SEED, seed).apply()
    }

    fun getSeed(): String? = prefs.getString(KEY_SEED, null)

    fun clearSeed() {
        prefs.edit().remove(KEY_SEED).apply()
    }

    fun isWalletInitialized(): Boolean = !getSeed().isNullOrBlank()

    // -------- Manual node (override) --------

    /**
     * Manual:
     * - non-empty URL => store the normalized URL
     * Auto:
     * - empty/blank URL => remove the key (no "" in prefs)
     */
    fun saveNodeUrl(url: String) {
        val cleaned = url.trim().trimEnd('/')
        if (cleaned.isBlank()) {
            prefs.edit().remove(KEY_NODE_URL).apply()
        } else {
            prefs.edit().putString(KEY_NODE_URL, cleaned).apply()
        }
    }

    /** URL forced by the user, or null if Auto mode */
    fun getUserNodeUrl(): String? {
        val saved = prefs.getString(KEY_NODE_URL, null)?.trim()?.trimEnd('/')
        return saved?.takeIf { it.isNotBlank() }
    }

    fun clearNodeUrl() {
        prefs.edit().remove(KEY_NODE_URL).apply()
    }

    // -------- Bootstrap index (nodes.json) --------

    fun getBootstrapIndexUrls(): List<String> = DEFAULT_BOOTSTRAP_INDEX_URLS

    fun getEmergencyFallbackNodes(): List<String> = EMERGENCY_FALLBACK_NODES

    // -------- Cache nodes list --------

    fun saveCachedBootstrapNodes(nodes: List<String>) {
        val cleaned = nodes
            .map { it.trim().trimEnd('/') }
            .filter { it.isNotBlank() }
            .distinct()

        val csv = cleaned.joinToString(",")
        prefs.edit()
            .putString(KEY_BOOTSTRAP_CACHE_CSV, csv)
            .putLong(KEY_BOOTSTRAP_CACHE_TS, System.currentTimeMillis())
            .apply()
    }

    fun getCachedBootstrapNodes(): List<String> {
        val csv = prefs.getString(KEY_BOOTSTRAP_CACHE_CSV, null)?.trim().orEmpty()
        if (csv.isBlank()) return emptyList()

        return csv.split(",")
            .map { it.trim().trimEnd('/') }
            .filter { it.isNotBlank() }
            .distinct()
    }

    fun getBootstrapCacheTimestampMs(): Long = prefs.getLong(KEY_BOOTSTRAP_CACHE_TS, 0L)

    fun clearBootstrapCache() {
        prefs.edit()
            .remove(KEY_BOOTSTRAP_CACHE_CSV)
            .remove(KEY_BOOTSTRAP_CACHE_TS)
            .apply()
    }

    // -------- Install ID --------

    fun getOrCreateInstallId(): String {
        prefs.getString(KEY_INSTALL_ID, null)?.let { existing ->
            if (existing.isNotBlank()) return existing
        }

        synchronized(installIdLock) {
            val existing2 = prefs.getString(KEY_INSTALL_ID, null)
            if (!existing2.isNullOrBlank()) return existing2

            val newId = UUID.randomUUID().toString()
            prefs.edit().putString(KEY_INSTALL_ID, newId).apply()
            return newId
        }
    }

    fun clearInstallId() {
        prefs.edit().remove(KEY_INSTALL_ID).apply()
    }

    // -------- Global --------

    fun clearAll() {
        prefs.edit().clear().apply()
    }
}
