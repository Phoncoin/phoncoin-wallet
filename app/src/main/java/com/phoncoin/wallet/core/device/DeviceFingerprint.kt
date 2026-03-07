package com.phoncoin.wallet.core.device

import android.content.Context
import com.phoncoin.wallet.core.storage.SecureStore
import java.security.MessageDigest

object DeviceFingerprint {

    fun get(context: Context): String {
        val store = SecureStore(context)
        val installId = store.getOrCreateInstallId()

        val raw = "${context.packageName}|$installId"
        return sha256Hex(raw.toByteArray(Charsets.UTF_8))
    }

    private fun sha256Hex(b: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        val d = md.digest(b)
        return d.joinToString("") { "%02x".format(it) }
    }
}
