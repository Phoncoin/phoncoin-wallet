package com.phoncoin.wallet.core.model

data class AddressState(
    val address: String,
    val balance: Double,
    val available: Double,
    val pending_outgoing: Double,
    val pending_txs: Int,
    val next_nonce: Long? = null,
    val chain_id: String? = null,
    val tx_version_max: Int? = null
)
