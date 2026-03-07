package com.phoncoin.wallet.core.network

import com.phoncoin.wallet.core.model.AddressState
import com.phoncoin.wallet.core.model.NetworkInfo
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

object PhoncoinApi {

    private const val UA = "PHONCOIN-Wallet/1.0"
    private const val CONNECT_TIMEOUT_SEC = 6L
    private const val READ_TIMEOUT_SEC = 12L
    private const val WRITE_TIMEOUT_SEC = 12L

    private val client = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT_SEC, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT_SEC, TimeUnit.SECONDS)
        .writeTimeout(WRITE_TIMEOUT_SEC, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    // ---------- JSON helpers (fix warnings: optString(key, null) is invalid) ----------

    private fun JSONObject.optStringOrNull(key: String): String? {
        val v = optString(key, "").trim()
        return v.takeIf { it.isNotBlank() }
    }

    private fun normBaseUrl(baseUrl: String): String {
        val u = baseUrl.trim().trimEnd('/')
        if (u.startsWith("http://") || u.startsWith("https://")) return u
        return "https://$u"
    }

    @Throws(IOException::class)
    private fun get(url: String): String {
        val req = Request.Builder()
            .url(url)
            .get()
            .header("User-Agent", UA)
            .build()

        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            return resp.body?.string().orEmpty()
        }
    }

    @Throws(IOException::class)
    private fun postJson(url: String, json: JSONObject): String {
        val body = json.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
        val req = Request.Builder()
            .url(url)
            .post(body)
            .header("User-Agent", UA)
            .build()

        client.newCall(req).execute().use { resp ->
            val txt = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}: $txt")
            return txt
        }
    }

    /** Returns UI-usable text (OK / Error / raw JSON). */
    fun getNetworkInfo(baseUrl: String): String {
        val url = "${normBaseUrl(baseUrl)}/network_info"
        return try {
            val body = get(url)
            body.ifBlank { "OK" }
        } catch (e: Exception) {
            when (e) {
                is IOException -> "Erreur ${e.message ?: "réseau"}"
                else -> "Node injoignable (${e.javaClass.simpleName})"
            }
        }
    }

    /** Parsed object. Null if offline / invalid JSON. */
    fun getNetworkInfoParsed(baseUrl: String): NetworkInfo? {
        val raw = getNetworkInfo(baseUrl)
        if (raw.startsWith("Erreur", true) || raw.startsWith("Node injoignable", true)) return null

        return try {
            val j = JSONObject(raw)

            val totalIssuedPhc = j.optJSONObject("total_issued")?.optDouble("phc", Double.NaN)
                ?.takeIf { !it.isNaN() }
            val totalSupplyCapPhc = j.optJSONObject("total_supply_cap")?.optDouble("phc", Double.NaN)
                ?.takeIf { !it.isNaN() }

            NetworkInfo(
                chainName = j.optStringOrNull("chain_name"),
                ticker = j.optStringOrNull("ticker"),
                consensus = j.optStringOrNull("consensus"),
                integrityMode = j.optStringOrNull("integrity_mode"),

                height = if (j.has("height")) j.optInt("height") else null,
                activeMiners10m = if (j.has("active_miners_10m")) j.optInt("active_miners_10m") else null,

                difficultyZeros = if (j.has("difficulty_zeros")) j.optInt("difficulty_zeros") else null,
                effectiveDifficultyZeros = if (j.has("effective_difficulty_zeros")) j.optInt("effective_difficulty_zeros") else null,

                hbRateDeviceSec = if (j.has("hb_rate_device_sec")) j.optInt("hb_rate_device_sec") else null,
                hbRatePubkeySec = if (j.has("hb_rate_pubkey_sec")) j.optInt("hb_rate_pubkey_sec") else null,

                mempoolHb = if (j.has("mempool_hb")) j.optInt("mempool_hb") else null,
                mempoolTx = if (j.has("mempool_tx")) j.optInt("mempool_tx") else null,

                blockThreshold = if (j.has("block_threshold")) j.optInt("block_threshold") else null,
                txThreshold = if (j.has("tx_threshold")) j.optInt("tx_threshold") else null,

                targetBlockTime = if (j.has("target_block_time")) j.optInt("target_block_time") else null,

                totalIssuedPhc = totalIssuedPhc,
                totalSupplyCapPhc = totalSupplyCapPhc,

                // 🔐 Network fingerprint (FINAL)
                genesisHash = j.optStringOrNull("genesis_hash")
                    ?: j.optStringOrNull("genesis")
                    ?: j.optStringOrNull("genesisBlockHash")
            )
        } catch (_: Exception) {
            null
        }
    }

    /** Reads a field that can be either a Double or {raw, phc}. Always returns the PHC value. */
    private fun readPhc(j: JSONObject, key: String): Double? {
        val v = j.opt(key)
        return when (v) {
            is Number -> v.toDouble()
            is JSONObject -> v.optDouble("phc", Double.NaN).takeIf { !it.isNaN() }
            else -> null
        }
    }

    private fun readPendingCount(j: JSONObject): Int {
        val v = j.opt("pending_txs")
        return when (v) {
            is Number -> v.toInt()
            is JSONArray -> v.length()
            else -> 0
        }
    }

    /** Wallet state. Null if error / offline / unexpected format. */
    fun getAddressState(baseUrl: String, pubKeyHex: String): AddressState? {
        val url = "${normBaseUrl(baseUrl)}/address/$pubKeyHex/state"
        return try {
            val raw = get(url)
            val json = JSONObject(raw)

            val balance = readPhc(json, "balance")
            val available = readPhc(json, "available")
            val pendingOut = readPhc(json, "pending_outgoing")
            val pendingTxs = readPendingCount(json)

            if (balance == null || available == null || pendingOut == null) return null

            val nextNonce = when (val v = json.opt("next_nonce")) {
                is Number -> v.toLong()
                is String -> v.toLongOrNull()
                else -> null
            }

            val txVersionMax = when (val v = json.opt("tx_version_max")) {
                is Number -> v.toInt()
                is String -> v.toIntOrNull()
                else -> null
            }

            AddressState(
                address = pubKeyHex,
                balance = balance,
                available = available,
                pending_outgoing = pendingOut,
                pending_txs = pendingTxs,
                next_nonce = nextNonce,
                chain_id = json.optStringOrNull("chain_id"),
                tx_version_max = txVersionMax
            )
        } catch (_: Exception) {
            null
        }
    }

    // -------------------------------
    // Transactions – /address/<pubkey>/transactions
    // -------------------------------
    data class TxItem(
        val txid: String?,
        val from: String?,
        val to: String?,
        val amountRaw: Long?,
        val amountPhc: Double?,
        val timestamp: Long?,
        val status: String?,
        val layer: String?,
        val rawJson: String
    )

    data class AddressTransactions(
        val pending: List<TxItem>,
        val confirmed: List<TxItem>
    )

    private fun readAmountRaw(any: Any?): Long? = when (any) {
        is Number -> any.toLong()
        is JSONObject -> {
            val r = any.opt("raw")
            when (r) {
                is Number -> r.toLong()
                is String -> r.toLongOrNull()
                else -> null
            }
        }
        is String -> any.toLongOrNull()
        else -> null
    }

    private fun readAmountPhc(any: Any?): Double? = when (any) {
        is Number -> any.toDouble()
        is JSONObject -> any.optDouble("phc", Double.NaN).takeIf { !it.isNaN() }
        is String -> any.toDoubleOrNull()
        else -> null
    }

    private fun parseTxArray(arr: JSONArray?): List<TxItem> {
        if (arr == null) return emptyList()
        val out = ArrayList<TxItem>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue

            val txid = o.optStringOrNull("txid")
                ?: o.optStringOrNull("id")
                ?: o.optStringOrNull("hash")

            val from = o.optStringOrNull("from_pubkey")
                ?: o.optStringOrNull("from")

            val to = o.optStringOrNull("to_pubkey")
                ?: o.optStringOrNull("to")

            val amountAny = o.opt("amount")
            val amountRaw = readAmountRaw(amountAny)
            val amountPhc = readAmountPhc(amountAny)

            val ts = when (val v = o.opt("timestamp")) {
                is Number -> v.toLong()
                is String -> v.toLongOrNull()
                else -> null
            }

            val status = o.optStringOrNull("status")
                ?: o.optStringOrNull("state")

            val layer = o.optStringOrNull("layer")

            out.add(
                TxItem(
                    txid = txid,
                    from = from,
                    to = to,
                    amountRaw = amountRaw,
                    amountPhc = amountPhc,
                    timestamp = ts,
                    status = status,
                    layer = layer,
                    rawJson = o.toString()
                )
            )
        }
        return out
    }

    /** Transactions: pending + confirmed. Null if offline / invalid JSON. */
    fun getAddressTransactions(baseUrl: String, pubKeyHex: String, limit: Int = 80): AddressTransactions? {
        val url = "${normBaseUrl(baseUrl)}/address/$pubKeyHex/transactions?limit=$limit"
        return try {
            val raw = get(url)
            val j = JSONObject(raw)
            val pending = parseTxArray(j.optJSONArray("pending"))
            val confirmed = parseTxArray(j.optJSONArray("confirmed"))
            AddressTransactions(pending = pending, confirmed = confirmed)
        } catch (_: Exception) {
            null
        }
    }

    // -------------------------------
    // Transfer – /transfer
    // -------------------------------
    data class TransferResult(
        val ok: Boolean,
        val status: String? = null,
        val layer: String? = null,
        val reason: String? = null,
        val txid: String? = null,
        val rawJson: String? = null
    )

    fun transfer(
        baseUrl: String,
        fromPubKeyHex: String,
        toPubKeyHex: String,
        amountRaw: Long,
        timestampSec: Long,
        signatureHex: String,
        feeRaw: Long? = null,
        nonce: Long? = null,
        chainId: String? = null
    ): TransferResult {
        val url = "${normBaseUrl(baseUrl)}/transfer"
        return try {
            val j = JSONObject().apply {
                put("from_pubkey", fromPubKeyHex)
                put("to_pubkey", toPubKeyHex)
                put("amount", amountRaw)
                put("timestamp", timestampSec)
                put("signature", signatureHex)

                // Optional V2 — legacy kept if null.
                if (feeRaw != null) put("fee", feeRaw)
                if (nonce != null) put("nonce", nonce)
                if (!chainId.isNullOrBlank()) put("chain_id", chainId)
            }

            val raw = postJson(url, j)
            val resp = JSONObject(raw)

            TransferResult(
                ok = resp.optBoolean("ok", false),
                status = resp.optStringOrNull("status"),
                layer = resp.optStringOrNull("layer"),
                reason = resp.optStringOrNull("reason"),
                txid = resp.optStringOrNull("txid"),
                rawJson = raw
            )
        } catch (e: Exception) {
            TransferResult(ok = false, reason = e.message ?: e.javaClass.simpleName)
        }
    }

    // -------------------------------
    // PoP Mining – /submit_proof
    // -------------------------------
    data class SubmitProofResult(
        val ok: Boolean,
        val reason: String = "unknown",
        val metaRaw: String = ""
    )

    fun submitProof(baseUrl: String, payloadJson: JSONObject): SubmitProofResult {
        val url = "${normBaseUrl(baseUrl)}/submit_proof"
        return try {
            val raw = postJson(url, payloadJson)
            val j = JSONObject(raw)
            SubmitProofResult(
                ok = j.optBoolean("ok", false),
                reason = j.optString("reason", "unknown"),
                metaRaw = j.optJSONObject("meta")?.toString() ?: raw
            )
        } catch (e: Exception) {
            SubmitProofResult(ok = false, reason = e.message ?: e.javaClass.simpleName, metaRaw = "")
        }
    }

    // ------------------------------------------------------------------
    // Multi-node helpers (bootstrap + peers + fallback)
    // ------------------------------------------------------------------

    /** Fetch peers from a node (requires node to expose /p2p/peers). Returns empty list if unavailable. */
    fun getPeers(baseUrl: String): List<String> {
        val url = "${normBaseUrl(baseUrl)}/p2p/peers"
        return try {
            val body = get(url)
            if (body.isBlank()) return emptyList()
            val j = JSONObject(body)
            val arr = j.optJSONArray("peers") ?: return emptyList()
            val out = ArrayList<String>(arr.length())
            for (i in 0 until arr.length()) {
                val p = arr.optString(i, "").trim().trimEnd('/')
                if (p.isNotBlank()) out.add(p)
            }
            out
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun isCompatibleNetwork(
        baseUrl: String,
        expectedChainName: String = "Phonchain",
        expectedTicker: String = "PHC",
        expectedConsensus: String = "Proof-of-Phone (PoP Secure v4.1)",
        expectedIntegrityMode: String = "pop-secure-v4.1",
        expectedGenesisHash: String? = null
    ): Boolean {
        val info = getNetworkInfoParsed(baseUrl) ?: return false

        val cn = (info.chainName ?: "").trim()
        val tk = (info.ticker ?: "").trim()
        val cs = (info.consensus ?: "").trim()
        val im = (info.integrityMode ?: "").trim()

        // Chain + ticker are mandatory checks
        if (!cn.equals(expectedChainName, ignoreCase = true)) return false
        if (!tk.equals(expectedTicker, ignoreCase = true)) return false

        // Consensus / integrity are strong hints that we're talking to the right network.
        // If server doesn't expose them (null/blank), we treat it as incompatible (safer default).
        if (cs.isBlank() || !cs.equals(expectedConsensus, ignoreCase = true)) return false
        if (im.isBlank() || !im.equals(expectedIntegrityMode, ignoreCase = true)) return false
        // Optional but STRONG: bind wallet to the real Phonchain via genesis hash.
        // We use the already-parsed NetworkInfo to avoid a second HTTP call.
        if (!expectedGenesisHash.isNullOrBlank()) {
            val gh = (info.genesisHash ?: "").trim()
            if (gh.isBlank()) return false
            if (!gh.equals(expectedGenesisHash.trim(), ignoreCase = true)) return false
        }
        return true
    }

    /** Pick the first responsive compatible node (simple + reliable). */
    fun selectBestNode(candidates: List<String>, expectedGenesisHash: String? = null): String? {
        for (c in candidates) {
            val u = c.trim().trimEnd('/')
            if (u.isBlank()) continue
            try {
                if (isCompatibleNetwork(u, expectedGenesisHash = expectedGenesisHash)) return u
            } catch (_: Exception) {
                // ignore and try next
            }
        }
        return null
    }

    /**
     * Resolve node automatically:
     * 1) try bootstrap nodes
     * 2) if /p2p/peers exists, extend candidates
     * 3) pick best responsive compatible node
     */
    fun autoResolveNode(bootstrap: List<String>, expectedGenesisHash: String? = null): String? {
        val boot = bootstrap.map { it.trim().trimEnd('/') }.filter { it.isNotBlank() }
        if (boot.isEmpty()) return null

        val first = selectBestNode(boot, expectedGenesisHash) ?: return null
        val peers = getPeers(first)
        val all = (boot + peers).distinct()
        return selectBestNode(all, expectedGenesisHash) ?: first
    }

    // ------------------------------------------------------------------
    // ✅ Bootstrap manifest (nodes.json) — DNS only, scalable, wallet never knows Node A
    // ------------------------------------------------------------------

    /**
     * Downloads a nodes.json manifest from multiple URLs (fallback).
     * Exemple:
     * - https://bootstrap.phonchain.org/nodes.json
     * - https://api.phonchain.org/nodes.json
     * - https://seed2.phonchain.org/nodes.json
     */
    fun fetchBootstrapNodes(manifestUrls: List<String>, maxNodes: Int = 50): List<String> {
        val urls = manifestUrls
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()

        if (urls.isEmpty()) return emptyList()

        for (u in urls) {
            val out = fetchBootstrapNodes(u, maxNodes)
            if (out.isNotEmpty()) return out
        }
        return emptyList()
    }

    /**
     * Downloads a nodes.json manifest (for example: https://bootstrap.phonchain.org/nodes.json)
     *
     * Supported formats:
     * 1) { "nodes": ["https://api...", "https://seed2..."] }
     * 2) { "nodes": [{"url":"https://api..."}, {"url":"https://seed2...", "priority": 2}] }
     * 3) ["https://api...", "https://seed2..."]
     *
     * Your current format is #2 (with network/mode/min_version + node objects)
     *
     * Returns: list of normalized URLs (trimmed, no trailing slash), unique, sorted by priority if present.
     */
    fun fetchBootstrapNodes(manifestUrl: String, maxNodes: Int = 50): List<String> {
        val url = manifestUrl.trim()
        if (url.isBlank()) return emptyList()

        return try {
            val req = Request.Builder()
                .url(url)
                .get()
                .header("User-Agent", UA)
                .build()

            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return emptyList()
                val body = resp.body?.string().orEmpty().trim()
                if (body.isBlank()) return emptyList()

                parseNodesJson(body)
                    .map { it.trim().trimEnd('/') }
                    .filter { it.isNotBlank() }
                    .distinct()
                    .take(maxNodes)
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Parse nodes.json:
     * - array of string or objects
     * - object with "nodes": array of string or objects
     *
     * If objects contain "priority": Int => sorts ascending (1 first).
     */
    private fun parseNodesJson(body: String): List<String> {
        val trimmed = body.trim()

        fun parseArray(arr: JSONArray): List<Pair<String, Int?>> {
            val out = ArrayList<Pair<String, Int?>>(arr.length())
            for (i in 0 until arr.length()) {
                val v = arr.opt(i)
                when (v) {
                    is String -> out.add(v to null)
                    is JSONObject -> {
                        val url = v.optStringOrNull("url")
                            ?: v.optStringOrNull("endpoint")
                            ?: v.optStringOrNull("base_url")
                        val prio = if (v.has("priority")) v.optInt("priority") else null
                        if (!url.isNullOrBlank()) out.add(url to prio)
                    }
                }
            }
            return out
        }

        return try {
            if (trimmed.startsWith("[")) {
                val arr = JSONArray(trimmed)
                val pairs = parseArray(arr)
                pairs
                    .sortedWith(compareBy<Pair<String, Int?>>({ it.second ?: Int.MAX_VALUE }, { it.first }))
                    .map { it.first }
            } else {
                val obj = JSONObject(trimmed)
                val nodesAny = obj.opt("nodes")
                if (nodesAny is JSONArray) {
                    val pairs = parseArray(nodesAny)
                    pairs
                        .sortedWith(compareBy<Pair<String, Int?>>({ it.second ?: Int.MAX_VALUE }, { it.first }))
                        .map { it.first }
                } else {
                    emptyList()
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }
}
