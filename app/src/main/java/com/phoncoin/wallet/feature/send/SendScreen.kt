package com.phoncoin.wallet.feature.send

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.phoncoin.wallet.core.crypto.KeyDerivation
import com.phoncoin.wallet.core.crypto.TxSigner
import com.phoncoin.wallet.core.network.PhoncoinApi
import com.phoncoin.wallet.core.storage.SecureStore
import com.phoncoin.wallet.core.theme.PhonColors
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.NumberFormat
import java.util.Locale
import com.phoncoin.wallet.R

private const val RAW_PER_PHC = 1_000_000L

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SendScreen(
    nodeUrl: String,
    onBack: () -> Unit,
    onScanQrStart: () -> Unit = {} // ✅ grace window anti-lock (MainActivity)
) {
    val context = LocalContext.current

    val msgQrScanned = stringResource(R.string.send_qr_scanned_toast)
    val msgQrInvalid = stringResource(R.string.send_qr_invalid_toast)
    val msgScanPrompt = stringResource(R.string.send_scan_prompt)

    val msgClipboardEmpty = stringResource(R.string.import_seed_clipboard_empty)

    val msgWalletNotInit = stringResource(R.string.wallet_not_initialized)
    val msgRecipientInvalid = stringResource(R.string.send_error_recipient_invalid)
    val msgSelfSend = stringResource(R.string.send_error_self_send)
    val msgAmountInvalid = stringResource(R.string.send_error_amount_invalid)

    val store = remember { SecureStore(context) }
    @Suppress("DEPRECATION")
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val scroll = rememberScrollState()

    val nf = remember {
        NumberFormat.getNumberInstance(Locale.FRANCE).apply {
            minimumFractionDigits = 3
            maximumFractionDigits = 6
            isGroupingUsed = true
        }
    }

    var fromPub by remember { mutableStateOf<String?>(null) }
    var availablePhc by remember { mutableStateOf<Double?>(null) }
    var pendingPhc by remember { mutableStateOf<Double?>(null) }

    // ✅ IMPORTANT: saveable => does not disappear after scan/rotation
    var toPub by rememberSaveable { mutableStateOf("") }
    var amountStr by rememberSaveable { mutableStateOf("") }

    var isSending by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    // ✅ Flag to prevent later code from overwriting toPub when returning from scan
    var scannedJustNow by remember { mutableStateOf(false) }

    fun toast(msg: String) = Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()

    fun normalizeHex(s: String): String {
        val t = s.trim()
        val cleaned = t
            .removePrefix("phoncoin:")
            .removePrefix("PHONCOIN:")
            .removePrefix("phc:")
            .removePrefix("PHC:")
            .trim()

        return cleaned
            .removePrefix("0x").removePrefix("0X")
            .replace(" ", "")
            .replace("\n", "")
            .replace("\r", "")
            .trim()
    }

    fun isHex(s: String): Boolean = s.isNotBlank() && s.all { it in "0123456789abcdefABCDEF" }

    fun parseAmountRaw(input: String): Long? {
        val cleaned = input.trim().replace(" ", "").replace(",", ".")
        if (cleaned.isBlank()) return null
        return try {
            val bd = BigDecimal(cleaned).setScale(6, RoundingMode.DOWN)
            val raw = bd.multiply(BigDecimal(RAW_PER_PHC)).toLong()
            if (raw <= 0) null else raw
        } catch (_: Exception) {
            null
        }
    }

    suspend fun refreshBalance() {
        val seed = store.getSeed()
        if (seed.isNullOrBlank()) {
            fromPub = null
            availablePhc = null
            pendingPhc = null
            error = msgWalletNotInit
            return
        }

        val keys = KeyDerivation.deriveFromSeed(seed)
        fromPub = keys.publicKeyHex

        val st = withContext(Dispatchers.IO) { PhoncoinApi.getAddressState(nodeUrl, keys.publicKeyHex) }
        availablePhc = st?.available
        pendingPhc = st?.pending_outgoing
    }

    // ✅ Balance refreshes without touching toPub
    LaunchedEffect(nodeUrl) { refreshBalance() }

    // ✅ QR Scanner launcher
    val qrLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        val content = result?.contents?.trim().orEmpty()
        if (content.isNotBlank()) {
            val hex = normalizeHex(content)
            if (isHex(hex) && hex.length >= 32) {
                scannedJustNow = true
                toPub = hex
                toast(msgQrScanned)
            } else {
                toast(msgQrInvalid)
            }
        }
    }

    // ✅ If another effect overwrites it, release the flag right after returning
    LaunchedEffect(scannedJustNow) {
        if (scannedJustNow) {
            // small delay to let Compose stabilize the screen
            kotlinx.coroutines.delay(250)
            scannedJustNow = false
        }
    }

    fun openQrScanner() {
        onScanQrStart()

        val options = ScanOptions().apply {
            setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            setPrompt(msgScanPrompt)
            setBeepEnabled(false)
            setOrientationLocked(false)
        }
        qrLauncher.launch(options)
    }

    fun doSend() {
        if (isSending) return
        error = null

        val from = fromPub ?: run {
            error = msgWalletNotInit
            return
        }

        val to = normalizeHex(toPub)
        if (!isHex(to) || to.length < 32) {
            error = msgRecipientInvalid
            return
        }
        if (to.equals(from, ignoreCase = true)) {
            error = msgSelfSend
            return
        }

        val amountRaw = parseAmountRaw(amountStr) ?: run {
            error = msgAmountInvalid
            return
        }

        val available = availablePhc ?: 0.0
        val availableRaw = BigDecimal(available).multiply(BigDecimal(RAW_PER_PHC)).toLong()
        if (amountRaw > availableRaw) {
            error = context.getString(R.string.send_error_insufficient_fmt, nf.format(available))
            return
        }

        scope.launch {
            isSending = true
            try {
                val seed = store.getSeed().orEmpty()
                val ts = System.currentTimeMillis() / 1000L

                val state = withContext(Dispatchers.IO) {
                    PhoncoinApi.getAddressState(nodeUrl, from)
                }

                val nonce = state?.next_nonce
                val chainId = state?.chain_id
                val txVersionMax = state?.tx_version_max ?: 1

                val res = if (nonce != null && !chainId.isNullOrBlank() && txVersionMax >= 2) {
                    val feeRaw = 0L
                    val sigHex = withContext(Dispatchers.Default) {
                        TxSigner.signTransferV2Hex(
                            seedString = seed,
                            chainId = chainId,
                            fromPubKeyHex = from,
                            toPubKeyHex = to,
                            amountRaw = amountRaw,
                            feeRaw = feeRaw,
                            nonce = nonce,
                            timestampSec = ts
                        )
                    }

                    withContext(Dispatchers.IO) {
                        PhoncoinApi.transfer(
                            baseUrl = nodeUrl,
                            fromPubKeyHex = from,
                            toPubKeyHex = to,
                            amountRaw = amountRaw,
                            timestampSec = ts,
                            signatureHex = sigHex,
                            feeRaw = feeRaw,
                            nonce = nonce,
                            chainId = chainId
                        )
                    }
                } else {
                    val sigHex = withContext(Dispatchers.Default) {
                        TxSigner.signTransferLegacyHex(
                            seedString = seed,
                            fromPubKeyHex = from,
                            toPubKeyHex = to,
                            amountRaw = amountRaw,
                            timestampSec = ts
                        )
                    }

                    withContext(Dispatchers.IO) {
                        PhoncoinApi.transfer(
                            baseUrl = nodeUrl,
                            fromPubKeyHex = from,
                            toPubKeyHex = to,
                            amountRaw = amountRaw,
                            timestampSec = ts,
                            signatureHex = sigHex
                        )
                    }
                }

                if (res.ok) {
                    toast(context.getString(R.string.send_tx_sent_fmt, res.status ?: "pending", res.layer ?: "L2"))
                    refreshBalance()
                    onBack()
                } else {
                    error = context.getString(R.string.send_rejected_fmt, res.reason ?: "unknown")
                }
            } catch (e: Exception) {
                error = context.getString(R.string.error_fmt, e.message ?: e.javaClass.simpleName)
            } finally {
                isSending = false
            }
        }
    }

    Surface(color = PhonColors.Bg, modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scroll)
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = PhonColors.Accent)
                }
                Text(
                    stringResource(R.string.send_title),
                    color = PhonColors.Text,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 6.dp)
                )
            }

            Card(
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = PhonColors.Card),
                border = BorderStroke(1.dp, PhonColors.Border),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(stringResource(R.string.send_from), color = PhonColors.Muted, style = MaterialTheme.typography.bodySmall)
                    Text(fromPub?.let { it.take(12) + "…" + it.takeLast(6) } ?: "—", color = PhonColors.Text)

                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(stringResource(R.string.available), color = PhonColors.Muted)
                        Text(
                            availablePhc?.let { "${nf.format(it)} PHC" } ?: "—",
                            color = PhonColors.Text,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(stringResource(R.string.pending), color = PhonColors.Muted)
                        Text(pendingPhc?.let { "${nf.format(it)} PHC" } ?: "—", color = PhonColors.Text)
                    }
                }
            }

            Card(
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = PhonColors.Card),
                border = BorderStroke(1.dp, PhonColors.Border),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {

                    OutlinedTextField(
                        value = toPub,
                        onValueChange = { toPub = it },
                        label = { Text(stringResource(R.string.send_recipient_label)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        trailingIcon = {
                            Row(verticalAlignment = Alignment.CenterVertically) {

                                IconButton(onClick = {
                                    val txt = clipboard.getText()?.text.orEmpty()
                                    if (txt.isNotBlank()) {
                                        toPub = normalizeHex(txt)
                                    } else {
                                        toast(msgClipboardEmpty)
                                    }
                                }) {
                                    Icon(Icons.Filled.ContentPaste, contentDescription = "Coller", tint = PhonColors.Accent)
                                }

                                IconButton(onClick = { openQrScanner() }) {
                                    Icon(Icons.Filled.PhotoCamera, contentDescription = stringResource(R.string.cd_scan_qr), tint = PhonColors.Accent)
                                }
                            }
                        }
                    )

                    OutlinedTextField(
                        value = amountStr,
                        onValueChange = { amountStr = it },
                        label = { Text(stringResource(R.string.send_amount_label)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth()
                    )

                    error?.let {
                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }

                    Button(
                        onClick = { doSend() },
                        enabled = !isSending,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Text(if (isSending) "Envoi…" else stringResource(R.string.send_title))
                    }
                }
            }
        }
    }
}
