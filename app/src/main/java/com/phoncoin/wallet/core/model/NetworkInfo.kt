package com.phoncoin.wallet.core.model

data class NetworkInfo(
    val chainName: String? = null,
    val ticker: String? = null,
    val consensus: String? = null,
    val integrityMode: String? = null,

    val height: Int? = null,
    val activeMiners10m: Int? = null,

    val difficultyZeros: Int? = null,
    val effectiveDifficultyZeros: Int? = null,

    val hbRateDeviceSec: Int? = null,
    val hbRatePubkeySec: Int? = null,

    val mempoolHb: Int? = null,
    val mempoolTx: Int? = null,

    val blockThreshold: Int? = null,
    val txThreshold: Int? = null,

    val targetBlockTime: Int? = null,

    val totalIssuedPhc: Double? = null,
    val totalSupplyCapPhc: Double? = null,

    // 🔐 Network fingerprint (FINAL)
    val genesisHash: String? = null
)
