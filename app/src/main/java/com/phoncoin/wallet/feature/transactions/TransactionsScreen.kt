package com.phoncoin.wallet.feature.transactions

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.phoncoin.wallet.R
import com.phoncoin.wallet.core.crypto.KeyDerivation
import com.phoncoin.wallet.core.network.PhoncoinApi
import com.phoncoin.wallet.core.storage.SecureStore
import com.phoncoin.wallet.core.theme.PhonColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransactionsScreen(
    nodeUrl: String,
    onBack: () -> Unit,
    onOpenExplorerTx: ((String) -> Unit)? = null
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val store = remember { SecureStore(context) }
    val scope = rememberCoroutineScope()

    val shape = RoundedCornerShape(18.dp)

    // ✅ i18n (safe for callbacks / suspend)
    val tTitle = stringResource(R.string.transactions)
    val tPendingFmt = stringResource(R.string.transactions_tab_pending_fmt)
    val tConfirmedFmt = stringResource(R.string.transactions_tab_confirmed_fmt)
    val tWalletNotInit = stringResource(R.string.wallet_not_initialized)
    val tTxUnavailable = stringResource(R.string.transactions_unavailable)
    val tAddrCopied = stringResource(R.string.address_copied)
    val tAddress = stringResource(R.string.address_title)
    val tErrFmt = stringResource(R.string.error_fmt)

    val cdBack = stringResource(R.string.cd_back)
    val cdRefresh = stringResource(R.string.cd_refresh)
    val cdCopy = stringResource(R.string.copy)

    // Empty states
    val tEmptyPending = stringResource(R.string.tx_empty_pending)
    val tEmptyConfirmed = stringResource(R.string.tx_empty_confirmed)

    // Tx UI labels
    val tAmount = stringResource(R.string.tx_amount)
    val tFrom = stringResource(R.string.tx_from)
    val tTo = stringResource(R.string.tx_to)
    val tTime = stringResource(R.string.tx_time)
    val tTxidLabel = stringResource(R.string.tx_txid)
    val tCopyTxid = stringResource(R.string.tx_copy_txid)
    val tCopyJson = stringResource(R.string.tx_copy_json)
    val tHint = stringResource(R.string.tx_hint_open_explorer)

    val tTxidUnavailable = stringResource(R.string.tx_txid_unavailable)
    val tTxidCopied = stringResource(R.string.tx_txid_copied)
    val tJsonUnavailable = stringResource(R.string.tx_json_unavailable)
    val tJsonCopied = stringResource(R.string.tx_json_copied)

    val tStatusConfirmed = stringResource(R.string.tx_status_confirmed)
    val tStatusPending = stringResource(R.string.tx_status_pending)
    val tDirIn = stringResource(R.string.tx_direction_in)
    val tDirOut = stringResource(R.string.tx_direction_out)

    // ✅ Device locale (not forced to French)
    val nf = remember {
        NumberFormat.getNumberInstance(Locale.getDefault()).apply {
            minimumFractionDigits = 3
            maximumFractionDigits = 3
            isGroupingUsed = true
        }
    }
    val timeFmt = remember { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()) }

    fun toast(msg: String) = Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()

    var pubKey by remember { mutableStateOf<String?>(null) }
    var tab by remember { mutableIntStateOf(0) } // 0 pending, 1 confirmed

    var isLoading by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }

    var pending by remember { mutableStateOf<List<PhoncoinApi.TxItem>>(emptyList()) }
    var confirmed by remember { mutableStateOf<List<PhoncoinApi.TxItem>>(emptyList()) }

    fun short(a: String?): String {
        val s = a ?: "—"
        return if (s.length <= 18) s else s.take(12) + "…" + s.takeLast(6)
    }

    fun shortTxid(a: String?): String {
        val s = a ?: "—"
        return if (s.length <= 20) s else s.take(10) + "…" + s.takeLast(8)
    }

    fun fmtPhc(tx: PhoncoinApi.TxItem): String {
        val v = tx.amountPhc
        return if (v == null) "—" else "${nf.format(v)} PHC"
    }

    fun fmtTime(ts: Long?): String {
        if (ts == null || ts <= 0) return "—"
        val ms = if (ts < 3_000_000_000L) ts * 1000L else ts
        return timeFmt.format(Date(ms))
    }

    suspend fun refresh() {
        if (isLoading) return
        isLoading = true
        err = null
        try {
            val seed = store.getSeed()
            if (seed == null) {
                pubKey = null
                pending = emptyList()
                confirmed = emptyList()
                err = tWalletNotInit
                return
            }

            val keys = KeyDerivation.deriveFromSeed(seed)
            pubKey = keys.publicKeyHex

            val res = withContext(Dispatchers.IO) {
                PhoncoinApi.getAddressTransactions(nodeUrl, keys.publicKeyHex, limit = 120)
            }

            if (res == null) {
                err = tTxUnavailable
                pending = emptyList()
                confirmed = emptyList()
            } else {
                pending = res.pending
                confirmed = res.confirmed
            }
        } catch (e: Exception) {
            err = String.format(tErrFmt, e.javaClass.simpleName)
        } finally {
            isLoading = false
        }
    }

    LaunchedEffect(nodeUrl) { refresh() }

    Surface(color = PhonColors.Bg, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {

            TopAppBar(
                title = { Text(tTitle, color = PhonColors.Text, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = cdBack, tint = PhonColors.Accent)
                    }
                },
                actions = {
                    IconButton(onClick = { scope.launch { refresh() } }, enabled = !isLoading) {
                        Icon(Icons.Filled.Refresh, contentDescription = cdRefresh, tint = PhonColors.Accent)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = PhonColors.Bg)
            )

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 18.dp)
                    .padding(top = 10.dp, bottom = 18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Address card
                Card(
                    shape = shape,
                    colors = CardDefaults.cardColors(containerColor = PhonColors.Card),
                    border = BorderStroke(1.dp, PhonColors.Border),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(tAddress, color = PhonColors.Muted, style = MaterialTheme.typography.bodySmall)

                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = short(pubKey),
                                color = PhonColors.Text,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            IconButton(
                                onClick = {
                                    pubKey?.let {
                                        clipboard.setText(AnnotatedString(it))
                                        toast(tAddrCopied)
                                    }
                                }
                            ) {
                                Icon(Icons.Filled.ContentCopy, contentDescription = cdCopy, tint = PhonColors.Accent)
                            }
                        }

                        if (isLoading) {
                            LinearProgressIndicator(
                                modifier = Modifier.fillMaxWidth(),
                                color = PhonColors.Accent,
                                trackColor = PhonColors.Border
                            )
                        }

                        err?.let {
                            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }

                // Tabs
                Card(
                    shape = shape,
                    colors = CardDefaults.cardColors(containerColor = PhonColors.Card),
                    border = BorderStroke(1.dp, PhonColors.Border),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(10.dp)) {
                        TabRow(
                            selectedTabIndex = tab,
                            containerColor = PhonColors.Card,
                            contentColor = PhonColors.Accent
                        ) {
                            Tab(
                                selected = tab == 0,
                                onClick = { tab = 0 },
                                text = { Text(String.format(tPendingFmt, pending.size)) }
                            )
                            Tab(
                                selected = tab == 1,
                                onClick = { tab = 1 },
                                text = { Text(String.format(tConfirmedFmt, confirmed.size)) }
                            )
                        }
                    }
                }

                // List
                Card(
                    shape = shape,
                    colors = CardDefaults.cardColors(containerColor = PhonColors.Card),
                    border = BorderStroke(1.dp, PhonColors.Border),
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) {
                    val list = if (tab == 0) pending else confirmed

                    if (list.isEmpty() && !isLoading) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(
                                text = if (tab == 0) tEmptyPending else tEmptyConfirmed,
                                color = PhonColors.Muted
                            )
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(12.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            items(
                                items = list,
                                key = { it.txid ?: (it.rawJson.hashCode().toString() + "_" + (it.timestamp ?: 0L)) }
                            ) { tx ->
                                TxCard(
                                    tx = tx,
                                    myPubKey = pubKey,
                                    shape = RoundedCornerShape(16.dp),
                                    fmtPhc = { fmtPhc(tx) },
                                    fmtTime = { fmtTime(tx.timestamp) },
                                    short = ::short,
                                    shortTxid = ::shortTxid,
                                    tAmount = tAmount,
                                    tFrom = tFrom,
                                    tTo = tTo,
                                    tTime = tTime,
                                    tTxidLabel = tTxidLabel,
                                    tCopyTxid = tCopyTxid,
                                    tCopyJson = tCopyJson,
                                    tHint = tHint,
                                    tTxidUnavailable = tTxidUnavailable,
                                    tTxidCopied = tTxidCopied,
                                    tJsonUnavailable = tJsonUnavailable,
                                    tJsonCopied = tJsonCopied,
                                    tStatusConfirmed = tStatusConfirmed,
                                    tStatusPending = tStatusPending,
                                    tDirIn = tDirIn,
                                    tDirOut = tDirOut,
                                    onCopyTxid = {
                                        val id = tx.txid
                                        if (id.isNullOrBlank()) {
                                            toast(tTxidUnavailable)
                                            return@TxCard
                                        }
                                        clipboard.setText(AnnotatedString(id))
                                        toast(tTxidCopied)
                                    },
                                    onCopyJson = {
                                        val raw = tx.rawJson
                                        if (raw.isBlank()) {
                                            toast(tJsonUnavailable)
                                            return@TxCard
                                        }
                                        clipboard.setText(AnnotatedString(raw))
                                        toast(tJsonCopied)
                                    },
                                    onTap = {
                                        val id = tx.txid
                                        if (!id.isNullOrBlank() && onOpenExplorerTx != null) {
                                            onOpenExplorerTx.invoke(id)
                                        } else {
                                            if (!id.isNullOrBlank()) {
                                                clipboard.setText(AnnotatedString(id))
                                                toast(tTxidCopied)
                                            } else {
                                                toast(tTxidUnavailable)
                                            }
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TxCard(
    tx: PhoncoinApi.TxItem,
    myPubKey: String?,
    shape: RoundedCornerShape,
    fmtPhc: () -> String,
    fmtTime: () -> String,
    short: (String?) -> String,
    shortTxid: (String?) -> String,

    // ✅ i18n injected
    tAmount: String,
    tFrom: String,
    tTo: String,
    tTime: String,
    tTxidLabel: String,
    tCopyTxid: String,
    tCopyJson: String,
    tHint: String,
    tTxidUnavailable: String,
    tTxidCopied: String,
    tJsonUnavailable: String,
    tJsonCopied: String,
    tStatusConfirmed: String,
    tStatusPending: String,
    tDirIn: String,
    tDirOut: String,

    onCopyTxid: () -> Unit,
    onCopyJson: () -> Unit,
    onTap: () -> Unit
) {
    val rawStatus = tx.status ?: "—"
    val s = rawStatus.lowercase(Locale.ROOT)

    // ✅ Harmonise status label
    val statusLabel = when {
        s.contains("confirm") || s.contains("accept") || s == "ok" -> tStatusConfirmed
        s.contains("pending") || s.contains("mempool") -> tStatusPending
        else -> rawStatus
    }

    val statusColor = when {
        s.contains("accept") || s == "ok" || s.contains("confirm") -> PhonColors.Accent
        s.contains("reject") || s.contains("fail") || s.contains("error") -> MaterialTheme.colorScheme.error
        s.contains("pending") || s.contains("mempool") -> MaterialTheme.colorScheme.tertiary
        else -> PhonColors.Muted
    }

    val outgoing = run {
        val me = myPubKey
        val from = tx.from
        me != null && from != null && from.equals(me, ignoreCase = true)
    }

    val directionLabel = if (outgoing) "⬆ $tDirOut" else "⬇ $tDirIn"
    val directionColor = if (outgoing) PhonColors.Text else PhonColors.Muted

    Card(
        shape = shape,
        colors = CardDefaults.cardColors(containerColor = PhonColors.Bg.copy(alpha = 0.55f)),
        border = BorderStroke(1.dp, PhonColors.Border),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onTap() }
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Text(
                        text = statusLabel,
                        color = statusColor,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.labelLarge
                    )
                    Text(
                        text = directionLabel,
                        color = directionColor,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Text(
                    text = tx.layer ?: "",
                    color = PhonColors.Muted,
                    style = MaterialTheme.typography.bodySmall
                )
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(tAmount, color = PhonColors.Muted, style = MaterialTheme.typography.bodySmall)
                Text(fmtPhc(), color = PhonColors.Text, fontWeight = FontWeight.SemiBold)
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(tFrom, color = PhonColors.Muted, style = MaterialTheme.typography.bodySmall)
                Text(short(tx.from), color = PhonColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(tTo, color = PhonColors.Muted, style = MaterialTheme.typography.bodySmall)
                Text(short(tx.to), color = PhonColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(tTime, color = PhonColors.Muted, style = MaterialTheme.typography.bodySmall)
                Text(fmtTime(), color = PhonColors.Text)
            }

            tx.txid?.takeIf { it.isNotBlank() }?.let { id ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "$tTxidLabel: ${shortTxid(id)}",
                        color = PhonColors.Muted,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    IconButton(onClick = onCopyTxid) {
                        Icon(Icons.Filled.ContentCopy, contentDescription = tCopyTxid, tint = PhonColors.Accent)
                    }
                }
            }

            Divider(color = PhonColors.Border)

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = onCopyTxid,
                    border = BorderStroke(1.dp, PhonColors.Border),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.weight(1f)
                ) { Text(tCopyTxid) }

                OutlinedButton(
                    onClick = onCopyJson,
                    border = BorderStroke(1.dp, PhonColors.Border),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.weight(1f)
                ) { Text(tCopyJson) }
            }

            Text(
                text = tHint,
                color = PhonColors.Muted,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}
