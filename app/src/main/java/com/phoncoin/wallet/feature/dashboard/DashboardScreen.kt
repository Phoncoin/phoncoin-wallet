package com.phoncoin.wallet.feature.dashboard

import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.phoncoin.wallet.R
import com.phoncoin.wallet.core.crypto.KeyDerivation
import com.phoncoin.wallet.core.network.NodeResolver
import com.phoncoin.wallet.core.network.PhoncoinApi
import com.phoncoin.wallet.core.storage.SecureStore
import com.phoncoin.wallet.core.theme.PhonColors
import com.phoncoin.wallet.core.util.LanguageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// -----------------------------
// Helpers (hors Composable)
// -----------------------------
private fun shortAddr(a: String): String =
    if (a.length <= 18) a else a.take(12) + "…" + a.takeLast(6)

private fun fmtInt(v: Int?): String = v?.toString() ?: "—"

private fun fmtPhc(nf: NumberFormat, v: Double?): String {
    if (v == null) return "—"
    return "${nf.format(v)} PHC"
}

private fun miningLastTime(timeFmt: SimpleDateFormat, ts: Long): String =
    if (ts > 0) timeFmt.format(Date(ts)) else "—"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    nodeUrl: String,
    onOpenNodeSettings: () -> Unit,
    onOpenSecuritySettings: () -> Unit,
    onOpenSeedRecovery: () -> Unit,
    onLock: () -> Unit,
    onSend: () -> Unit,
    onReceive: () -> Unit,
    onMining: () -> Unit,
    onTransactions: () -> Unit,
    onOpenExplorer: () -> Unit,
) {
    val shape = RoundedCornerShape(18.dp)
    val context = LocalContext.current
    val store = remember { SecureStore(context) }
    @Suppress("DEPRECATION")
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val scroll = rememberScrollState()

    // ✅ Strings (only those used in this file)
    val msgAddressCopied = stringResource(R.string.address_copied)
    val msgWalletNotInitialized = stringResource(R.string.wallet_not_initialized)
    val msgBalanceUnavailableOffline = stringResource(R.string.balance_unavailable_node_offline)
    val msgAddressNewOrNotIndexed = stringResource(R.string.address_new_or_not_indexed)
    val msgLoadingNetwork = stringResource(R.string.loading_network)

    val cdLogo = stringResource(R.string.cd_phoncoin_logo)
    val cdSettings = stringResource(R.string.cd_settings)
    val cdRefresh = stringResource(R.string.cd_refresh)
    val cdLock = stringResource(R.string.cd_lock)

    val tClose = stringResource(R.string.close)
    val tCopy = stringResource(R.string.copy)
    val tView = stringResource(R.string.view)

    fun toast(msg: String) = Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()

    var showSettingsMenu by remember { mutableStateOf(false) }
    var showLanguageMenu by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { LanguageManager.applySavedLanguage(context) }

    // --- Data ---
    var pubKey by remember { mutableStateOf<String?>(null) }

    var nodeOnline by remember { mutableStateOf(false) }
    var networkInfoObj by remember { mutableStateOf<com.phoncoin.wallet.core.model.NetworkInfo?>(null) }
    var networkInfoRaw by remember { mutableStateOf<String?>(null) }
    var networkError by remember { mutableStateOf<String?>(null) }

    var addressState by remember { mutableStateOf<com.phoncoin.wallet.core.model.AddressState?>(null) }
    var walletError by remember { mutableStateOf<String?>(null) }

    var showAddressDialog by remember { mutableStateOf(false) }
    var isRefreshing by remember { mutableStateOf(false) }

    // UI states
    var networkExpanded by rememberSaveable { mutableStateOf(false) }

    // -----------------------------
    // Mining status
    // -----------------------------
    val miningPrefs = remember { context.getSharedPreferences(MINING_PREFS, Context.MODE_PRIVATE) }
    var miningRunning by remember { mutableStateOf(false) }
    var miningAccepted by remember { mutableIntStateOf(0) }
    var miningRejected by remember { mutableIntStateOf(0) }
    var miningLastReason by remember { mutableStateOf("—") }
    var miningLastDiff by remember { mutableIntStateOf(0) }
    var miningLastSubmitTs by remember { mutableLongStateOf(0L) }

    val timeFmt = remember { SimpleDateFormat("HH:mm:ss", Locale.FRANCE) }
    val lastMiningTime by remember { derivedStateOf { miningLastTime(timeFmt, miningLastSubmitTs) } }

    fun loadMiningStatus() {
        miningRunning = miningPrefs.getBoolean(MINING_KEY_RUNNING, false)
        miningAccepted = miningPrefs.getInt(MINING_KEY_ACCEPTED, 0)
        miningRejected = miningPrefs.getInt(MINING_KEY_REJECTED, 0)
        miningLastReason = miningPrefs.getString(MINING_KEY_LAST_REASON, "—") ?: "—"
        miningLastDiff = miningPrefs.getInt(MINING_KEY_LAST_DIFF, 0)
        miningLastSubmitTs = miningPrefs.getLong(MINING_KEY_LAST_SUBMIT_TS, 0L)
    }

    LaunchedEffect(Unit) {
        while (true) {
            loadMiningStatus()
            delay(1000)
        }
    }

    // --- Formatting helpers ---
    val nf: NumberFormat = remember {
        NumberFormat.getNumberInstance(Locale.FRANCE).apply {
            minimumFractionDigits = 3
            maximumFractionDigits = 3
            isGroupingUsed = true
        }
    }

    /**
     * ✅ Source of truth:
     * - use the nodeUrl provided by MainActivity (already resolved)
     * - if empty -> fallback resolver
     */
    val activeNode by remember(nodeUrl) {
        mutableStateOf(
            nodeUrl.trim().trimEnd('/').ifBlank { NodeResolver.resolve(context).trim().trimEnd('/') }
        )
    }

    suspend fun refreshSuspend() {
        if (isRefreshing) return
        isRefreshing = true

        networkError = null
        walletError = null

        try {
            val raw = withContext(Dispatchers.IO) { PhoncoinApi.getNetworkInfo(activeNode) }
            networkInfoRaw = raw

            val parsed = withContext(Dispatchers.IO) { PhoncoinApi.getNetworkInfoParsed(activeNode) }
            networkInfoObj = parsed
            nodeOnline = parsed != null

            if (parsed == null) {
                networkError = raw.takeIf { it.isNotBlank() } ?: msgLoadingNetwork
            }

            val seed = store.getSeed()
            if (seed == null) {
                pubKey = null
                addressState = null
                walletError = msgWalletNotInitialized
                return
            }

            val keys = KeyDerivation.deriveFromSeed(seed)
            pubKey = keys.publicKeyHex

            val st = withContext(Dispatchers.IO) {
                PhoncoinApi.getAddressState(activeNode, keys.publicKeyHex)
            }
            addressState = st

            if (st == null) {
                walletError = if (!nodeOnline) msgBalanceUnavailableOffline else msgAddressNewOrNotIndexed
            }
        } catch (e: Exception) {
            nodeOnline = false
            val err = context.getString(R.string.error_fmt, e.javaClass.simpleName)
            networkError = err
            walletError = err
        } finally {
            isRefreshing = false
        }
    }

    fun triggerRefresh() = scope.launch { refreshSuspend() }
    LaunchedEffect(nodeUrl) { refreshSuspend() }

    // Address dialog
    if (showAddressDialog && pubKey != null) {
        AlertDialog(
            onDismissRequest = { showAddressDialog = false },
            title = {
                Text(
                    stringResource(R.string.address_title),
                    color = PhonColors.Text,
                    fontWeight = FontWeight.SemiBold
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(pubKey!!, color = PhonColors.Muted)
                    Text(
                        stringResource(R.string.address_copy_hint),
                        color = PhonColors.Muted,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        clipboard.setText(AnnotatedString(pubKey!!))
                        toast(msgAddressCopied)
                        showAddressDialog = false
                    },
                    shape = RoundedCornerShape(14.dp)
                ) { Text(tCopy) }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = { showAddressDialog = false },
                    border = BorderStroke(1.dp, PhonColors.Border),
                    shape = RoundedCornerShape(14.dp)
                ) { Text(tClose) }
            }
        )
    }

    if (showSettingsMenu) {
        AlertDialog(
            onDismissRequest = { showSettingsMenu = false },
            title = { Text(stringResource(R.string.settings_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(
                        onClick = {
                            showSettingsMenu = false
                            onOpenSecuritySettings()
                        },
                        border = BorderStroke(1.dp, PhonColors.Border),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(stringResource(R.string.settings_security)) }

                    OutlinedButton(
                        onClick = {
                            showSettingsMenu = false
                            onOpenSeedRecovery()
                        },
                        border = BorderStroke(1.dp, PhonColors.Border),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(stringResource(R.string.settings_seed_backup)) }

                    OutlinedButton(
                        onClick = {
                            showSettingsMenu = false
                            onOpenNodeSettings()
                        },
                        border = BorderStroke(1.dp, PhonColors.Border),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(stringResource(R.string.settings_network_node)) }

                    OutlinedButton(
                        onClick = {
                            showSettingsMenu = false
                            showLanguageMenu = true
                        },
                        border = BorderStroke(1.dp, PhonColors.Border),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(stringResource(R.string.settings_language)) }
                }
            },
            confirmButton = {},
            dismissButton = {
                OutlinedButton(
                    onClick = { showSettingsMenu = false },
                    border = BorderStroke(1.dp, PhonColors.Border),
                    shape = RoundedCornerShape(14.dp)
                ) { Text(tClose) }
            }
        )
    }

    // ✅✅✅ LANGUAGE MENU (FULL - ALL YOUR values-xx folders)
    if (showLanguageMenu) {

        @Composable
        fun LangButton(labelRes: Int, code: String) {
            OutlinedButton(
                onClick = {
                    LanguageManager.saveAndApply(context, code)
                    showLanguageMenu = false
                },
                border = BorderStroke(1.dp, PhonColors.Border),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            ) { Text(stringResource(labelRes)) }
        }

        AlertDialog(
            onDismissRequest = { showLanguageMenu = false },
            title = { Text(stringResource(R.string.language_title)) },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // --- Base ---
                    LangButton(R.string.language_english, "en")
                    LangButton(R.string.language_french, "fr")

                    // --- Most used worldwide / crypto ---
                    LangButton(R.string.language_spanish, "es")
                    LangButton(R.string.language_russian, "ru")
                    LangButton(R.string.language_arabic, "ar")
                    LangButton(R.string.language_chinese, "zh")

                    LangButton(R.string.language_hindi, "hi")

                    // --- Europe ---
                    LangButton(R.string.language_german, "de")
                    LangButton(R.string.language_greek, "el")
                    LangButton(R.string.language_italian, "it")
                    LangButton(R.string.language_dutch, "nl")
                    LangButton(R.string.language_polish, "pl")
                    LangButton(R.string.language_portuguese, "pt")
                    LangButton(R.string.language_romanian, "ro")
                    LangButton(R.string.language_swedish, "sv")
                    LangButton(R.string.language_turkish, "tr")
                    LangButton(R.string.language_ukrainian, "uk")
                    LangButton(R.string.language_czech, "cs")
                    LangButton(R.string.language_hungarian, "hu")
                    LangButton(R.string.language_norwegian, "nb")
                    LangButton(R.string.language_danish, "da")

                    // --- Middle East / Asia ---
                    LangButton(R.string.language_persian, "fa")

                    // Hebrew: values-iw (old code)
                    LangButton(R.string.language_hebrew, "iw")

                    LangButton(R.string.language_japanese, "ja")
                    LangButton(R.string.language_korean, "ko")
                    LangButton(R.string.language_thai, "th")
                    LangButton(R.string.language_vietnamese, "vi")

                    // Indonesian: values-in (old code)
                    LangButton(R.string.language_indonesian, "in")
                }
            },
            confirmButton = {},
            dismissButton = {
                OutlinedButton(
                    onClick = { showLanguageMenu = false },
                    border = BorderStroke(1.dp, PhonColors.Border),
                    shape = RoundedCornerShape(14.dp)
                ) { Text(tClose) }
            }
        )
    }

    Surface(color = PhonColors.Bg, modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Image(
                            painter = painterResource(id = R.drawable.phoncoin_logo),
                            contentDescription = cdLogo,
                            modifier = Modifier.size(28.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "PHONCOIN",
                            color = PhonColors.Accent,
                            fontWeight = FontWeight.Bold
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { showSettingsMenu = true }) {
                        Icon(Icons.Filled.Settings, contentDescription = cdSettings, tint = PhonColors.Accent)
                    }
                    IconButton(onClick = { triggerRefresh() }, enabled = !isRefreshing) {
                        Icon(Icons.Filled.Refresh, contentDescription = cdRefresh, tint = PhonColors.Accent)
                    }
                    IconButton(onClick = onLock) {
                        Icon(Icons.Filled.Lock, contentDescription = cdLock, tint = PhonColors.Accent)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = PhonColors.Bg,
                    titleContentColor = PhonColors.Text
                )
            )

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scroll)
                    .padding(horizontal = 18.dp)
                    .padding(top = 10.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Wallet card
                Card(
                    shape = shape,
                    colors = CardDefaults.cardColors(containerColor = PhonColors.Card),
                    border = BorderStroke(1.dp, PhonColors.Border),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                stringResource(R.string.wallet_title),
                                fontWeight = FontWeight.SemiBold,
                                color = PhonColors.Text,
                                modifier = Modifier.weight(1f)
                            )

                            if (miningRunning) {
                                Surface(
                                    color = PhonColors.Accent.copy(alpha = 0.14f),
                                    shape = RoundedCornerShape(999.dp),
                                    border = BorderStroke(1.dp, PhonColors.Accent),
                                    modifier = Modifier.clickable { onMining() }
                                ) {
                                    Text(
                                        text = stringResource(R.string.mining_active_badge),
                                        color = PhonColors.Accent,
                                        fontWeight = FontWeight.Bold,
                                        style = MaterialTheme.typography.labelMedium,
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                                    )
                                }
                            }
                        }

                        if (pubKey == null) {
                            Text(stringResource(R.string.address_generating), color = PhonColors.Muted)
                        } else {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = shortAddr(pubKey!!),
                                    color = PhonColors.Muted,
                                    modifier = Modifier
                                        .weight(1f)
                                        .clickable {
                                            clipboard.setText(AnnotatedString(pubKey!!))
                                            toast(msgAddressCopied)
                                        },
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(Modifier.width(10.dp))
                                OutlinedButton(
                                    onClick = { showAddressDialog = true },
                                    border = BorderStroke(1.dp, PhonColors.Border),
                                    shape = RoundedCornerShape(14.dp),
                                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                                ) { Text(tView) }
                            }
                        }

                        AnimatedVisibility(visible = isRefreshing) {
                            LinearProgressIndicator(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 2.dp),
                                color = PhonColors.Accent,
                                trackColor = PhonColors.Border
                            )
                        }

                        when {
                            addressState != null -> {
                                val st = addressState!!
                                Text(
                                    text = fmtPhc(nf, st.balance),
                                    color = PhonColors.Text,
                                    fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.headlineSmall
                                )

                                Spacer(Modifier.height(2.dp))

                                MetricsRow(
                                    leftLabel = stringResource(R.string.available),
                                    leftValue = fmtPhc(nf, st.available),
                                    rightLabel = stringResource(R.string.pending),
                                    rightValue = fmtPhc(nf, st.pending_outgoing)
                                )

                                if (st.pending_txs > 0) {
                                    Text(
                                        text = stringResource(R.string.pending_txs_fmt, st.pending_txs),
                                        color = PhonColors.Muted,
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }

                                walletError?.let {
                                    Text(text = it, color = PhonColors.Muted, style = MaterialTheme.typography.bodySmall)
                                }
                            }

                            isRefreshing -> {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    SkeletonLine(height = 28.dp, widthFill = 0.55f)
                                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                        SkeletonBlock(modifier = Modifier.weight(1f))
                                        SkeletonBlock(modifier = Modifier.weight(1f))
                                    }
                                }
                                Text(
                                    text = stringResource(R.string.loading_balance),
                                    color = PhonColors.Muted,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }

                            !walletError.isNullOrBlank() -> {
                                Text(text = walletError!!, color = PhonColors.Muted, style = MaterialTheme.typography.bodySmall)
                            }

                            else -> {
                                Text(
                                    text = stringResource(R.string.balance_unavailable),
                                    color = PhonColors.Muted,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }

                        // Mining mini-stats
                        if (miningRunning) {
                            Spacer(Modifier.height(6.dp))
                            Surface(
                                color = PhonColors.Bg.copy(alpha = 0.55f),
                                shape = RoundedCornerShape(14.dp),
                                border = BorderStroke(1.dp, PhonColors.Border),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onMining() }
                            ) {
                                Column(
                                    modifier = Modifier.padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                        Text(
                                            stringResource(R.string.mining_title),
                                            color = PhonColors.Muted,
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                        Text("PoP-S4", color = PhonColors.Text, fontWeight = FontWeight.SemiBold)
                                    }

                                    MetricsRow(
                                        leftLabel = "Accepted",
                                        leftValue = miningAccepted.toString(),
                                        rightLabel = "Diff",
                                        rightValue = if (miningLastDiff > 0) miningLastDiff.toString() else "—"
                                    )

                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                        Text(
                                            stringResource(R.string.mining_last_submit),
                                            color = PhonColors.Muted,
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                        Text(lastMiningTime, color = PhonColors.Text, fontWeight = FontWeight.SemiBold)
                                    }

                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                        Text(
                                            stringResource(R.string.mining_last_reason),
                                            color = PhonColors.Muted,
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                        Text(prettyMiningReason(miningLastReason), color = PhonColors.Text, fontWeight = FontWeight.SemiBold)
                                    }

                                    if (miningRejected > 0) {
                                        Text(
                                            stringResource(R.string.mining_rejected_fmt, miningRejected),
                                            color = PhonColors.Muted,
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // Network card
                Card(
                    shape = shape,
                    colors = CardDefaults.cardColors(containerColor = PhonColors.Card),
                    border = BorderStroke(1.dp, PhonColors.Border),
                    modifier = Modifier
                        .fillMaxWidth()
                        .animateContentSize()
                        .clickable { networkExpanded = !networkExpanded }
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(stringResource(R.string.network_title), fontWeight = FontWeight.SemiBold, color = PhonColors.Text)
                            val statusText = if (nodeOnline) stringResource(R.string.online) else stringResource(R.string.offline)
                            val statusColor = if (nodeOnline) PhonColors.Accent else PhonColors.Danger
                            Text(statusText, color = statusColor, fontWeight = FontWeight.Bold)
                        }

                        // ✅ Show the actual node being used
                        Text(
                            stringResource(R.string.node_fmt, activeNode),
                            color = PhonColors.Muted,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = if (networkExpanded) 3 else 1,
                            overflow = TextOverflow.Ellipsis
                        )

                        if (networkInfoObj == null) {
                            Text(
                                text = networkError ?: networkInfoRaw ?: msgLoadingNetwork,
                                color = if (networkError != null) PhonColors.Danger else PhonColors.Muted,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = if (networkExpanded) 10 else 3,
                                overflow = TextOverflow.Ellipsis
                            )
                        } else {
                            val n = networkInfoObj!!

                            Text(
                                "${n.chainName ?: "Phonchain"} • ${n.consensus ?: "PoP"} • ${n.ticker ?: "PHC"}",
                                color = PhonColors.Muted,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = if (networkExpanded) 3 else 2,
                                overflow = TextOverflow.Ellipsis
                            )

                            AnimatedVisibility(visible = networkExpanded) {
                                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    MetricsRow(stringResource(R.string.net_height), fmtInt(n.height), stringResource(R.string.net_miners_10m), fmtInt(n.activeMiners10m))
                                    MetricsRow(stringResource(R.string.net_difficulty), fmtInt(n.difficultyZeros), stringResource(R.string.net_eff_difficulty), fmtInt(n.effectiveDifficultyZeros))
                                    MetricsRow(stringResource(R.string.net_mempool_hb), fmtInt(n.mempoolHb), stringResource(R.string.net_mempool_tx), fmtInt(n.mempoolTx))
                                    MetricsRow(stringResource(R.string.net_target_block), "${fmtInt(n.targetBlockTime)}s", stringResource(R.string.net_hb_threshold), fmtInt(n.blockThreshold))
                                    MetricsRow(stringResource(R.string.net_issued), fmtPhc(nf, n.totalIssuedPhc), stringResource(R.string.net_cap), fmtPhc(nf, n.totalSupplyCapPhc))

                                    if (!n.integrityMode.isNullOrBlank()) {
                                        Text(
                                            stringResource(R.string.integrity_fmt, n.integrityMode),
                                            color = PhonColors.Muted,
                                            style = MaterialTheme.typography.bodySmall,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }

                            Text(
                                if (networkExpanded) stringResource(R.string.tap_to_collapse) else stringResource(R.string.tap_for_details),
                                color = PhonColors.Muted,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }

                        OutlinedButton(
                            onClick = { triggerRefresh() },
                            enabled = !isRefreshing,
                            modifier = Modifier.fillMaxWidth(),
                            border = BorderStroke(1.dp, PhonColors.Border),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Text(if (isRefreshing) stringResource(R.string.refreshing) else stringResource(R.string.refresh))
                        }
                    }
                }

                // Actions card
                Card(
                    shape = shape,
                    colors = CardDefaults.cardColors(containerColor = PhonColors.Card),
                    border = BorderStroke(1.dp, PhonColors.Border),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    val pendingBadge = addressState?.pending_txs ?: 0

                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = onSend,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(14.dp)
                        ) { Text(stringResource(R.string.send)) }

                        OutlinedButton(
                            onClick = onReceive,
                            modifier = Modifier.fillMaxWidth(),
                            border = BorderStroke(1.dp, PhonColors.Border),
                            shape = RoundedCornerShape(14.dp)
                        ) { Text(stringResource(R.string.receive)) }

                        OutlinedButton(
                            onClick = onTransactions,
                            modifier = Modifier.fillMaxWidth(),
                            border = BorderStroke(1.dp, PhonColors.Border),
                            shape = RoundedCornerShape(14.dp),
                            contentPadding = PaddingValues(vertical = 14.dp)
                        ) {
                            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                Text(stringResource(R.string.transactions))
                                if (pendingBadge > 0) {
                                    Box(
                                        modifier = Modifier
                                            .align(Alignment.CenterEnd)
                                            .padding(end = 8.dp)
                                    ) { KnowingBadge(pendingBadge) }
                                }
                            }
                        }

                        OutlinedButton(
                            onClick = onMining,
                            modifier = Modifier.fillMaxWidth(),
                            border = BorderStroke(1.dp, PhonColors.Border),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Text(if (miningRunning) stringResource(R.string.mining_pop_active) else stringResource(R.string.mining_pop))
                        }

                        OutlinedButton(
                            onClick = onOpenExplorer,
                            modifier = Modifier.fillMaxWidth(),
                            border = BorderStroke(1.dp, PhonColors.Border),
                            shape = RoundedCornerShape(14.dp)
                        ) { Text(stringResource(R.string.explorer)) }
                    }
                }

                Spacer(Modifier.height(10.dp))
            }
        }
    }
}

@Composable
private fun KnowingBadge(pending: Int) {
    if (pending <= 0) return
    Surface(
        color = PhonColors.Accent.copy(alpha = 0.16f),
        shape = RoundedCornerShape(999.dp),
        border = BorderStroke(1.dp, PhonColors.Accent)
    ) {
        Text(
            text = "$pending",
            color = PhonColors.Accent,
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
        )
    }
}

@Composable
private fun MetricsRow(
    leftLabel: String,
    leftValue: String,
    rightLabel: String,
    rightValue: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(leftLabel, color = PhonColors.Muted, style = MaterialTheme.typography.bodySmall)
            Text(leftValue, color = PhonColors.Text, fontWeight = FontWeight.SemiBold)
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(rightLabel, color = PhonColors.Muted, style = MaterialTheme.typography.bodySmall)
            Text(rightValue, color = PhonColors.Text, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun SkeletonLine(height: androidx.compose.ui.unit.Dp, widthFill: Float) {
    Surface(
        color = PhonColors.Border,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier
            .fillMaxWidth(widthFill)
            .height(height)
    ) {}
}

@Composable
private fun SkeletonBlock(modifier: Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Surface(
            color = PhonColors.Border,
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.fillMaxWidth(0.55f).height(12.dp)
        ) {}
        Surface(
            color = PhonColors.Border,
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.fillMaxWidth(0.85f).height(18.dp)
        ) {}
    }
}

// Must match PopMiningService / MiningScreen prefs keys
private const val MINING_PREFS = "pop_mining_prefs"
private const val MINING_KEY_RUNNING = "running"
private const val MINING_KEY_ACCEPTED = "accepted"
private const val MINING_KEY_REJECTED = "rejected"
private const val MINING_KEY_LAST_REASON = "lastReason"
private const val MINING_KEY_LAST_DIFF = "lastDiff"
private const val MINING_KEY_LAST_SUBMIT_TS = "lastSubmitTs"
private fun prettyMiningReason(r: String): String = when (r) {
    "accepted" -> "Accepted"
    "rejected" -> "Rejected"
    "offline" -> "Offline"
    "waiting_next_block" -> "Waiting next block"
    "—" -> "—"
    else -> r
}

