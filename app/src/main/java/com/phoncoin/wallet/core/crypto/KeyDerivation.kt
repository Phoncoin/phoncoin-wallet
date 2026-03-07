package com.phoncoin.wallet.core.crypto

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * KeyDerivation – Ed25519 deterministic (Wallet Pro)
 *
 * ✅ Stable: same seed => same keys, even if the user pastes it with
 *    line breaks / double spaces / uppercase letters.
 *
 * IMPORTANT:
 * - The node verifies Ed25519.
 * - A seed32 is derived with SHA-256("PHONCOIN_ED25519|" + normalizedSeed) and then used for Ed25519.
 */
object KeyDerivation {

    private const val DOMAIN = "PHONCOIN_ED25519|"

    data class Keys(
        val publicKeyHex: String,
        val privateKeyHex: String
    ) {
        fun privateSeedBytes(): ByteArray = privateKeyHex.hexToBytes()
        fun publicBytes(): ByteArray = publicKeyHex.hexToBytes()
    }

    fun deriveFromSeed(seed: String): Keys {
        val normalized = normalizeSeed(seed)
        val seed32 = sha256((DOMAIN + normalized).toByteArray(StandardCharsets.UTF_8))
            .copyOfRange(0, 32)

        val priv = Ed25519PrivateKeyParameters(seed32, 0)
        val pub = priv.generatePublicKey()

        return Keys(
            publicKeyHex = pub.encoded.toHex(),
            privateKeyHex = seed32.toHex()
        )
    }

    private fun normalizeSeed(s: String): String {
        // - trim
        // - lowercase
        // - remplace \n par espace
        // - collapse multi-spaces
        return s.trim()
            .lowercase()
            .replace("\n", " ")
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }
            .joinToString(" ")
    }

    private fun sha256(input: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(input)

    private fun ByteArray.toHex(): String =
        joinToString("") { "%02x".format(it) }

    private fun String.hexToBytes(): ByteArray {
        val s = this.trim().removePrefix("0x").removePrefix("0X")
        require(s.length % 2 == 0) { "Hex length must be even" }
        return ByteArray(s.length / 2) { i ->
            s.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }
}
