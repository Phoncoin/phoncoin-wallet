package com.phoncoin.wallet.core.network

import android.content.Context
import com.phoncoin.wallet.core.storage.SecureStore

object NodeResolver {

    // TTL cache nodes.json (tu peux changer)
    private const val BOOTSTRAP_CACHE_TTL_MS: Long = 12L * 60L * 60L * 1000L // 12h

    // 🔐 Network fingerprint (FINAL) — wallet will ONLY accept this chain
    private const val EXPECTED_GENESIS_HASH =
        "c098fa5e985edd56634af262975b771f0dadc607494d6875cb725f1006611658"

    // ✅ Final labels (v4.1)
    private const val EXPECTED_CHAIN_NAME = "Phonchain"
    private const val EXPECTED_TICKER = "PHC"
    private const val EXPECTED_CONSENSUS = "Proof-of-Phone (PoP Secure v4.1)"
    private const val EXPECTED_INTEGRITY_MODE = "pop-secure-v4.1"

    fun resolve(context: Context): String {
        val store = SecureStore(context)

        // 0) Manual override (if the user forces a node)
        // ✅ PRO: only accept if it matches Phonchain + v4.1 + genesis hash
        val userNode = store.getUserNodeUrl()?.trim()?.trimEnd('/')
        if (!userNode.isNullOrBlank()) {
            val ok = PhoncoinApi.isCompatibleNetwork(
                baseUrl = userNode,
                expectedChainName = EXPECTED_CHAIN_NAME,
                expectedTicker = EXPECTED_TICKER,
                expectedConsensus = EXPECTED_CONSENSUS,
                expectedIntegrityMode = EXPECTED_INTEGRITY_MODE,
                expectedGenesisHash = EXPECTED_GENESIS_HASH
            )
            if (ok) return userNode
            // otherwise ignore the manual node (anti-cheat) and continue auto-resolve
        }

        // 1) Auto mode: try to use the cache if fresh
        val cached = store.getCachedBootstrapNodes()
        val cacheTs = store.getBootstrapCacheTimestampMs()
        val cacheFresh = cached.isNotEmpty() && cacheTs > 0L &&
                (System.currentTimeMillis() - cacheTs) <= BOOTSTRAP_CACHE_TTL_MS

        var candidates: List<String> = if (cacheFresh) cached else emptyList()

        // 2) If the cache is not fresh: fetch nodes.json (cleaner approach)
        if (candidates.isEmpty()) {
            val fetched = PhoncoinApi.fetchBootstrapNodes(store.getBootstrapIndexUrls())
            if (fetched.isNotEmpty()) {
                store.saveCachedBootstrapNodes(fetched)
                candidates = fetched
            }
        }

        // 3) If fetch fails: use the cache even if stale (last resort before fallback)
        if (candidates.isEmpty() && cached.isNotEmpty()) {
            candidates = cached
        }

        // 4) Last resort: DNS fallback (still without IPs, never A records)
        if (candidates.isEmpty()) {
            candidates = store.getEmergencyFallbackNodes()
        }

        // 5) Auto-discovery (bootstrap -> peers -> best compatible)
        val auto = PhoncoinApi.autoResolveNode(
            bootstrap = candidates,
            expectedGenesisHash = EXPECTED_GENESIS_HASH
        )
        if (!auto.isNullOrBlank()) {
            // ✅ last safety check
            val u = auto.trim().trimEnd('/')
            val ok = PhoncoinApi.isCompatibleNetwork(
                baseUrl = u,
                expectedChainName = EXPECTED_CHAIN_NAME,
                expectedTicker = EXPECTED_TICKER,
                expectedConsensus = EXPECTED_CONSENSUS,
                expectedIntegrityMode = EXPECTED_INTEGRITY_MODE,
                expectedGenesisHash = EXPECTED_GENESIS_HASH
            )
            if (ok) return u
        }

        // 6) Last resort: first compatible candidate, else hard fallback
        val first = candidates.firstOrNull()?.trim()?.trimEnd('/')
        if (!first.isNullOrBlank()) {
            val ok = PhoncoinApi.isCompatibleNetwork(
                baseUrl = first,
                expectedChainName = EXPECTED_CHAIN_NAME,
                expectedTicker = EXPECTED_TICKER,
                expectedConsensus = EXPECTED_CONSENSUS,
                expectedIntegrityMode = EXPECTED_INTEGRITY_MODE,
                expectedGenesisHash = EXPECTED_GENESIS_HASH
            )
            if (ok) return first
        }

        return "https://api.phonchain.org"
    }
}
