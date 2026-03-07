package com.phoncoin.wallet.feature.mining

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.phoncoin.wallet.R
import com.phoncoin.wallet.core.theme.PhonColors
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MiningScreen(
    nodeUrl: String,
    onBack: () -> Unit
) {
    val ctx = LocalContext.current
    val shape = RoundedCornerShape(18.dp)
    val scroll = rememberScrollState()

    // ✅ i18n strings (safe for callbacks)
    val cdBack = stringResource(R.string.cd_back)
    val cdCopy = stringResource(R.string.copy)

    val title = stringResource(R.string.mining_pop)
    val labelNode = stringResource(R.string.mining_node)
    val labelStatus = stringResource(R.string.mining_status)
    val labelAccepted = stringResource(R.string.mining_accepted)
    val labelRejected = stringResource(R.string.mining_rejected)
    val labelDiff = stringResource(R.string.mining_diff)
    val labelLastSubmit = stringResource(R.string.mining_last_submit)
    val labelLastReason = stringResource(R.string.mining_last_reason)

    val btnStart = stringResource(R.string.mining_start)
    val btnStop = stringResource(R.string.mining_stop)
    val btnCopyPayload = stringResource(R.string.mining_copy_payload)

    val statusRunning = stringResource(R.string.mining_running_foreground)
    val statusStopped = stringResource(R.string.mining_stopped_badge)

    val notifDialogTitle = stringResource(R.string.mining_notif_perm_title)
    val notifDialogText = stringResource(R.string.mining_notif_perm_text)
    val notifAllow = stringResource(R.string.mining_notif_perm_allow)
    val notifLater = stringResource(R.string.mining_notif_perm_later)

    val miningHelp = stringResource(R.string.mining_help_text)

    // ✅ Background activity guidance (Play-safe wording)
    val bgTitle = stringResource(R.string.mining_bg_title)
    val bgBody = stringResource(R.string.mining_bg_body)
    val bgOpenSettings = stringResource(R.string.mining_bg_open_settings)
    val bgOptimized = stringResource(R.string.mining_bg_optimized)
    val bgUnrestricted = stringResource(R.string.mining_bg_unrestricted)

    val isBatteryOptimized = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = ctx.getSystemService(PowerManager::class.java)
            pm != null && !pm.isIgnoringBatteryOptimizations(ctx.packageName)
        } else false
    }

    // --- stats live (prefs) ---
    val prefs = remember { ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    var running by remember { mutableStateOf(false) }
    var accepted by remember { mutableIntStateOf(0) }
    var rejected by remember { mutableIntStateOf(0) }
    var lastReason by remember { mutableStateOf("—") }
    var lastDiff by remember { mutableIntStateOf(0) }
    var lastSubmitTs by remember { mutableLongStateOf(0L) }
    var lastPayload by remember { mutableStateOf("") }

    var showNotifDialog by remember { mutableStateOf(false) }

    fun load() {
        running = prefs.getBoolean(KEY_RUNNING, false)
        accepted = prefs.getInt(KEY_ACCEPTED, 0)
        rejected = prefs.getInt(KEY_REJECTED, 0)
        lastReason = prefs.getString(KEY_LAST_REASON, "—") ?: "—"
        lastDiff = prefs.getInt(KEY_LAST_DIFF, 0)
        lastSubmitTs = prefs.getLong(KEY_LAST_SUBMIT_TS, 0L)
        lastPayload = prefs.getString(KEY_LAST_PAYLOAD, "") ?: ""
    }

    LaunchedEffect(Unit) {
        while (true) {
            load()
            delay(1000)
        }
    }

    // Android 13+ notif permission
    val requestNotifPermission = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            startMiningService(ctx, nodeUrl)
        } else {
            showNotifDialog = true
        }
    }

    fun startClicked() {
        if (nodeUrl.isBlank()) return

        if (Build.VERSION.SDK_INT >= 33) {
            val granted = ContextCompat.checkSelfPermission(
                ctx,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED

            if (!granted) {
                requestNotifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                return
            }
        }
        startMiningService(ctx, nodeUrl)
    }

    fun stopClicked() {
        // ✅ Important: on Android 8+, keep behavior consistent -> also use startForegroundService for STOP
        val i = Intent(ctx, PopMiningService::class.java).apply {
            action = PopMiningService.ACTION_STOP
        }
        if (Build.VERSION.SDK_INT >= 26) {
            ContextCompat.startForegroundService(ctx, i)
        } else {
            ctx.startService(i)
        }
    }

    fun copyLastPayload() {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(
            ClipData.newPlainText(
                "PHONCOIN last heartbeat",
                lastPayload.ifBlank { "{}" }
            )
        )
    }

    if (showNotifDialog) {
        AlertDialog(
            onDismissRequest = { showNotifDialog = false },
            title = { Text(notifDialogTitle, color = PhonColors.Text, fontWeight = FontWeight.SemiBold) },
            text = { Text(notifDialogText, color = PhonColors.Muted) },
            confirmButton = {
                Button(
                    onClick = {
                        showNotifDialog = false
                        if (Build.VERSION.SDK_INT >= 33) {
                            requestNotifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    },
                    shape = RoundedCornerShape(14.dp)
                ) { Text(notifAllow) }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = {
                        showNotifDialog = false
                        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                            putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
                        }
                        ctx.startActivity(intent)
                    },
                    border = BorderStroke(1.dp, PhonColors.Border),
                    shape = RoundedCornerShape(14.dp)
                ) { Text(notifLater) }
            }
        )
    }

    val fmt = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    val lastTime = if (lastSubmitTs > 0) fmt.format(Date(lastSubmitTs)) else "—"

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
                    Icon(Icons.Filled.ArrowBack, contentDescription = cdBack, tint = PhonColors.Accent)
                }
                Text(
                    title,
                    color = PhonColors.Text,
                    fontWeight = FontWeight.ExtraBold,
                    style = MaterialTheme.typography.headlineSmall
                )
            }

            Card(
                shape = shape,
                colors = CardDefaults.cardColors(containerColor = PhonColors.Card),
                border = BorderStroke(1.dp, PhonColors.Border),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(labelNode, color = PhonColors.Muted)
                    Text(nodeUrl, color = PhonColors.Text)

                    Spacer(Modifier.height(6.dp))

                    Text(labelStatus, color = PhonColors.Muted)
                    Text(
                        text = if (running) statusRunning else statusStopped,
                        color = if (running) PhonColors.Accent else MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.Bold
                    )

                    Spacer(Modifier.height(8.dp))

                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(labelAccepted, color = PhonColors.Muted)
                        Text("$accepted", color = PhonColors.Text, fontWeight = FontWeight.SemiBold)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(labelRejected, color = PhonColors.Muted)
                        Text("$rejected", color = PhonColors.Text, fontWeight = FontWeight.SemiBold)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(labelDiff, color = PhonColors.Muted)
                        Text(if (lastDiff > 0) lastDiff.toString() else "—", color = PhonColors.Text)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(labelLastSubmit, color = PhonColors.Muted)
                        Text(lastTime, color = PhonColors.Text)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(labelLastReason, color = PhonColors.Muted)
                        Text(prettyReason(lastReason), color = PhonColors.Text)
                    }

                    Spacer(Modifier.height(10.dp))

                    Button(
                        onClick = { if (!running) startClicked() else stopClicked() },
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (!running) btnStart else btnStop)
                    }

                    OutlinedButton(
                        onClick = { copyLastPayload() },
                        enabled = lastPayload.isNotBlank(),
                        shape = RoundedCornerShape(16.dp),
                        border = BorderStroke(1.dp, PhonColors.Border),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Filled.ContentCopy, contentDescription = cdCopy, tint = PhonColors.Accent)
                        Spacer(Modifier.width(10.dp))
                        Text(btnCopyPayload)
                    }

                    Text(
                        miningHelp,
                        color = PhonColors.Muted,
                        style = MaterialTheme.typography.bodySmall
                    )

                    Spacer(Modifier.height(10.dp))

                    // --- Background activity (Play-safe) ---
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = PhonColors.Bg),
                        border = BorderStroke(1.dp, PhonColors.Border),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text(bgTitle, color = PhonColors.Text, fontWeight = FontWeight.SemiBold)

                            Text(bgBody, color = PhonColors.Muted, style = MaterialTheme.typography.bodySmall)

                            Text(
                                text = if (isBatteryOptimized) bgOptimized else bgUnrestricted,
                                color = if (isBatteryOptimized) MaterialTheme.colorScheme.error else PhonColors.Accent,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold
                            )

                            // ✅ Button unchanged visually, but opens "App info" directly (PHONCOIN)
                            OutlinedButton(
                                onClick = { openAppDetailsSettings(ctx) },
                                shape = RoundedCornerShape(14.dp),
                                border = BorderStroke(1.dp, PhonColors.Border),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(bgOpenSettings)
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun prettyReason(r: String): String = when (r) {
    "accepted" -> "Accepted"
    "rejected" -> "Rejected"
    "offline" -> "Offline"
    "waiting_next_block" -> "Waiting next block"
    "—" -> "—"
    else -> r
}

/**
 * ✅ Always opens the PHONCOIN “App info” page.
 * From there, the user can go to Battery/Energy and set it to “Unrestricted”
 * (the exact label varies by manufacturer, but this menu always exists).
 */
private fun openAppDetailsSettings(ctx: Context) {
    runCatching {
        ctx.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${ctx.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
    }
}

private fun startMiningService(ctx: Context, nodeUrl: String) {
    val i = Intent(ctx, PopMiningService::class.java).apply {
        action = PopMiningService.ACTION_START
        putExtra(PopMiningService.EXTRA_NODE_URL, nodeUrl)
    }
    if (Build.VERSION.SDK_INT >= 26) {
        ContextCompat.startForegroundService(ctx, i)
    } else {
        ctx.startService(i)
    }
}

private const val PREFS = "pop_mining_prefs"
private const val KEY_RUNNING = "running"
private const val KEY_ACCEPTED = "accepted"
private const val KEY_REJECTED = "rejected"
private const val KEY_LAST_REASON = "lastReason"
private const val KEY_LAST_DIFF = "lastDiff"
private const val KEY_LAST_SUBMIT_TS = "lastSubmitTs"
private const val KEY_LAST_PAYLOAD = "lastPayload"
