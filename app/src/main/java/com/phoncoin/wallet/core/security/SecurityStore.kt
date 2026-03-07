package com.phoncoin.wallet.core.security

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.MessageDigest
import java.security.SecureRandom
import kotlin.math.min

data class SecuritySettings(
    val pinEnabled: Boolean = false,
    val biometricEnabled: Boolean = false,
    val autoLockSeconds: Int = 60,
    val privacyMode: Boolean = false
)

class SecurityStore(context: Context) {

    private val appContext = context.applicationContext
    private val prefsName = "phoncoin_security_prefs"

    // ✅ SAFE creation (anti-crash)
    private val prefs: SharedPreferences = createEncryptedPrefs()

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
        } catch (e: Exception) {
            // 🔥 WALLET-GRADE FIX :
            // Corrupted / changed keystore -> wipe encrypted preferences
            appContext.deleteSharedPreferences(prefsName)
            build()
        }
    }

    private fun getBool(k: String, d: Boolean) = prefs.getBoolean(k, d)
    private fun getInt(k: String, d: Int) = prefs.getInt(k, d)
    private fun getStr(k: String, d: String? = null) = prefs.getString(k, d)

    private fun putBool(k: String, v: Boolean) = prefs.edit().putBoolean(k, v).apply()
    private fun putInt(k: String, v: Int) = prefs.edit().putInt(k, v).apply()
    private fun putStr(k: String, v: String?) = prefs.edit().putString(k, v).apply()

    fun load(): SecuritySettings = SecuritySettings(
        pinEnabled = getBool("pin_enabled", false),
        biometricEnabled = getBool("bio_enabled", false),
        autoLockSeconds = getInt("autolock_sec", 60),
        privacyMode = getBool("privacy_mode", false)
    )

    fun save(settings: SecuritySettings) {
        putBool("pin_enabled", settings.pinEnabled)
        putBool("bio_enabled", settings.biometricEnabled)
        putInt("autolock_sec", settings.autoLockSeconds)
        putBool("privacy_mode", settings.privacyMode)
    }

    // ---------------- PIN ----------------

    fun hasPin(): Boolean =
        !getStr("pin_hash").isNullOrBlank() && !getStr("pin_salt").isNullOrBlank()

    fun setPin(pin: String) {
        require(pin.length in 4..12) { "PIN invalide" }
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val hash = sha256Hex(salt + pin.toByteArray(Charsets.UTF_8))
        putStr("pin_salt", salt.toHex())
        putStr("pin_hash", hash)
    }

    fun verifyPin(pin: String): Boolean {
        val saltHex = getStr("pin_salt") ?: return false
        val hashHex = getStr("pin_hash") ?: return false

        val salt = saltHex.hexToBytesStrict() ?: return false
        val test = sha256Hex(salt + pin.toByteArray(Charsets.UTF_8))

        return constantTimeEqualsHex(test, hashHex)
    }

    fun clearPin() {
        putStr("pin_salt", null)
        putStr("pin_hash", null)
        val s = load()
        save(s.copy(pinEnabled = false, biometricEnabled = false))
    }

    private fun sha256Hex(input: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(input)
            .joinToString("") { "%02x".format(it) }

    private fun ByteArray.toHex(): String =
        joinToString("") { "%02x".format(it) }

    private fun String.hexToBytesStrict(): ByteArray? {
        val clean = trim()
        if (clean.isEmpty() || clean.length % 2 != 0) return null
        return try {
            ByteArray(clean.length / 2) { i ->
                clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
            }
        } catch (_: Exception) {
            null
        }
    }

    // ⏱️ Timing-safe comparison (wallet-grade)
    private fun constantTimeEqualsHex(a: String, b: String): Boolean {
        val aa = a.lowercase()
        val bb = b.lowercase()
        val n = min(aa.length, bb.length)
        var diff = aa.length xor bb.length
        for (i in 0 until n) diff = diff or (aa[i].code xor bb[i].code)
        return diff == 0
    }
}
