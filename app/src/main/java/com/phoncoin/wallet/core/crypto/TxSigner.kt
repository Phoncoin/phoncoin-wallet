package com.phoncoin.wallet.core.crypto

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer

/**
 * TxSigner – signature Ed25519 compatible node /transfer
 *
 * Legacy:
 *   from + to + amount_raw + timestamp
 *
 * V2:
 *   chain_id|from|to|amount_raw|fee_raw|nonce|timestamp
 *
 * Legacy is kept for the mainnet transition.
 */
object   TxSigner {

    fun signPayloadHex(seedString: String, payload: ByteArray): String {
        val keys = KeyDerivation.deriveFromSeed(seedString)
        val privSeed32 = keys.privateSeedBytes()

        val priv = Ed25519PrivateKeyParameters(privSeed32, 0)
        val signer = Ed25519Signer()
        signer.init(true, priv)
        signer.update(payload, 0, payload.size)

        val sig = signer.generateSignature()
        return sig.joinToString("") { "%02x".format(it) }
    }

    /** Legacy signature kept for temporary compatibility. */
    fun signTransferLegacyHex(
        seedString: String,
        fromPubKeyHex: String,
        toPubKeyHex: String,
        amountRaw: Long,
        timestampSec: Long
    ): String {
        val payload = (fromPubKeyHex + toPubKeyHex + amountRaw.toString() + timestampSec.toString())
            .toByteArray(Charsets.UTF_8)
        return signPayloadHex(seedString, payload)
    }

    /** Secure V2 signature (anti-replay with nonce + chain_id). */
    fun signTransferV2Hex(
        seedString: String,
        chainId: String,
        fromPubKeyHex: String,
        toPubKeyHex: String,
        amountRaw: Long,
        feeRaw: Long,
        nonce: Long,
        timestampSec: Long
    ): String {
        val payload = "$chainId|$fromPubKeyHex|$toPubKeyHex|$amountRaw|$feeRaw|$nonce|$timestampSec"
            .toByteArray(Charsets.UTF_8)
        return signPayloadHex(seedString, payload)
    }
}
