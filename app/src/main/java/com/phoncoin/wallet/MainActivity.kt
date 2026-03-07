package com.phoncoin.wallet

import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.appcompat.app.AppCompatActivity
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
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.phoncoin.wallet.core.crypto.KeyDerivation
import com.phoncoin.wallet.core.network.NodeResolver
import com.phoncoin.wallet.core.security.SecurityStore
import com.phoncoin.wallet.core.storage.SecureStore
import com.phoncoin.wallet.core.theme.PHONTheme
import com.phoncoin.wallet.core.theme.PhonColors
import com.phoncoin.wallet.feature.dashboard.DashboardScreen
import com.phoncoin.wallet.feature.explorer.ExplorerScreen
import com.phoncoin.wallet.feature.home.HomeScreen
import com.phoncoin.wallet.feature.mining.MiningScreen
import com.phoncoin.wallet.feature.onboarding.CreateWalletScreen
import com.phoncoin.wallet.feature.onboarding.ImportWalletScreen
import com.phoncoin.wallet.feature.receive.ReceiveScreen
import com.phoncoin.wallet.feature.send.SendScreen
import com.phoncoin.wallet.feature.settings.NodeSettingsScreen
import com.phoncoin.wallet.feature.settings.SecuritySettingsScreen
import com.phoncoin.wallet.feature.splash.SplashScreen
import com.phoncoin.wallet.feature.transactions.TransactionsScreen
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 🔒 Anti-screenshot + no preview in the recent apps screen
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )

        // ✅ Edge-to-edge
        WindowCompat.setDecorFitsSystemWindows(window, false)

        setContentView(
            ComposeView(this).apply {
                setContent {
                    PHONTheme {

                        // Stores
                        val secureStore = remember { SecurityStore(applicationContext) }
                        val walletStore = remember { SecureStore(applicationContext) }

                        // ✅ Settings
                        val securitySettings = remember { mutableStateOf(secureStore.load()) }

                        // ✅ Saveable states (survives rotation / recreation)
                        val showSplash = rememberSaveable { mutableStateOf(true) }
                        val screen = rememberSaveable { mutableStateOf("home") }
                        val isInitialized = rememberSaveable { mutableStateOf(walletStore.isWalletInitialized()) }
                        val locked = rememberSaveable { mutableStateOf(true) }

                        // ✅ “anti relock” during scan / child activity
                        val suppressAutoLock = rememberSaveable { mutableStateOf(false) }

                        // ✅ Node URL (saveable)
                        val nodeUrl = rememberSaveable {
                            mutableStateOf(NodeResolver.resolve(applicationContext))
                        }

                        // seed (may be null)
                        val seed = remember { mutableStateOf(walletStore.getSeed()) }

                        val addressHex: String? = remember(seed.value) {
                            seed.value?.let { KeyDerivation.deriveFromSeed(it).publicKeyHex }
                        }

                        // ✅ explorer base URL (reactive)
                        val explorerBaseUrl by remember {
                            derivedStateOf { nodeUrl.value.trim().trimEnd('/') + "/explorer" }
                        }
                        val explorerUrl = rememberSaveable { mutableStateOf(explorerBaseUrl) }

                        // ✅ interaction time (saveable)
                        var lastInteraction by rememberSaveable { mutableLongStateOf(System.currentTimeMillis()) }
                        fun touch() { lastInteraction = System.currentTimeMillis() }

                        // ✨ Splash
                        if (showSplash.value) {
                            SplashScreen(onDone = { showSplash.value = false })
                            return@PHONTheme
                        }

                        /**
                         * ✅ PRO:
                         * As soon as we return to the screen (after scan), suppressAutoLock is turned off.
                         * (Prevents staying in scan mode if the scanner activity returns).
                         */
                        LaunchedEffect(screen.value) {
                            if (screen.value != "send") {
                                suppressAutoLock.value = false
                            }
                        }

                        // 🔒 Auto-lock loop (respecte suppressAutoLock)
                        LaunchedEffect(securitySettings.value) {
                            lifecycleScope.launch {
                                repeatOnLifecycle(Lifecycle.State.RESUMED) {
                                    while (true) {
                                        delay(1_000)
                                        if (suppressAutoLock.value) continue

                                        val timeoutMs = securitySettings.value.autoLockSeconds * 1000L
                                        if (
                                            (securitySettings.value.pinEnabled || securitySettings.value.biometricEnabled) &&
                                            timeoutMs >= 0 &&
                                            System.currentTimeMillis() - lastInteraction > timeoutMs
                                        ) {
                                            locked.value = true
                                        }
                                    }
                                }
                            }
                        }

                        /**
                         * ✅ IMPORTANT (PRO):
                         * Lock only when the entire app goes to the background,
                         * not when MainActivity stops because the scanner camera is opened.
                         */
                        DisposableEffect(Unit) {
                            val procLifecycle = ProcessLifecycleOwner.get().lifecycle
                            val observer = LifecycleEventObserver { _, event ->
                                if (event == Lifecycle.Event.ON_STOP) {
                                    if (!suppressAutoLock.value) {
                                        locked.value = true
                                    }
                                }
                            }
                            procLifecycle.addObserver(observer)
                            onDispose { procLifecycle.removeObserver(observer) }
                        }

                        // 🔐 LockScreen
                        if (
                            locked.value &&
                            (securitySettings.value.pinEnabled || securitySettings.value.biometricEnabled)
                        ) {
                            com.phoncoin.wallet.feature.security.LockScreen(
                                securitySettings = securitySettings.value,
                                onUnlockSuccess = {
                                    locked.value = false
                                    touch()
                                }
                            )
                        } else {

                            when (screen.value) {

                                "home" -> HomeScreen(
                                    isInitialized = isInitialized.value,
                                    onCreateWallet = { screen.value = "create" },
                                    onImportWallet = { screen.value = "import" },
                                    onUnlock = {
                                        locked.value = false
                                        touch()
                                        screen.value = "dashboard"
                                    }
                                )

                                "create" -> CreateWalletScreen(
                                    onBack = { screen.value = "home" },
                                    onConfirm = { newSeed ->
                                        walletStore.saveSeed(newSeed)
                                        seed.value = walletStore.getSeed()
                                        isInitialized.value = true
                                        screen.value = "home"
                                    }
                                )

                                "import" -> ImportWalletScreen(
                                    onBack = { screen.value = "home" },
                                    onConfirm = { importedSeed ->
                                        walletStore.saveSeed(importedSeed)
                                        seed.value = walletStore.getSeed()
                                        isInitialized.value = true
                                        screen.value = "home"
                                    }
                                )

                                "dashboard" -> DashboardScreen(
                                    nodeUrl = nodeUrl.value,
                                    onOpenNodeSettings = { screen.value = "node_settings" },
                                    onOpenSecuritySettings = { screen.value = "security_settings" },
                                    onOpenSeedRecovery = { screen.value = "seed_recovery" },
                                    onLock = {
                                        locked.value = true
                                        screen.value = "home"
                                    },
                                    onSend = { touch(); screen.value = "send" },
                                    onReceive = { touch(); screen.value = "receive" },
                                    onMining = { touch(); screen.value = "mining" },
                                    onTransactions = { touch(); screen.value = "transactions" },
                                    onOpenExplorer = {
                                        explorerUrl.value = explorerBaseUrl
                                        screen.value = "explorer"
                                    }
                                )

                                "send" -> SendScreen(
                                    nodeUrl = nodeUrl.value,
                                    onBack = {
                                        suppressAutoLock.value = false
                                        touch()
                                        screen.value = "dashboard"
                                    },
                                    // ✅ IMPORTANT: matches YOUR SendScreen (onScanQrStart)
                                    onScanQrStart = {
                                        suppressAutoLock.value = true
                                        touch()
                                    }
                                )

                                "mining" -> MiningScreen(
                                    nodeUrl = nodeUrl.value,
                                    onBack = { screen.value = "dashboard" }
                                )

                                "receive" -> ReceiveScreen(
                                    addressHex = addressHex,
                                    explorerBaseUrl = explorerBaseUrl,
                                    onBack = { screen.value = "dashboard" }
                                )

                                "transactions" -> TransactionsScreen(
                                    nodeUrl = nodeUrl.value,
                                    onBack = { screen.value = "dashboard" },
                                    onOpenExplorerTx = { txid ->
                                        explorerUrl.value = "$explorerBaseUrl/tx/$txid"
                                        screen.value = "explorer"
                                    }
                                )

                                "explorer" -> ExplorerScreen(
                                    url = explorerUrl.value,
                                    onBack = { screen.value = "dashboard" }
                                )

                                "node_settings" -> NodeSettingsScreen(
                                    currentUrl = nodeUrl.value,
                                    onSave = { newUrl ->
                                        walletStore.saveNodeUrl(newUrl)

                                        val resolved = NodeResolver.resolve(applicationContext)
                                        nodeUrl.value = resolved
                                        explorerUrl.value = resolved.trim().trimEnd('/') + "/explorer"

                                        screen.value = "dashboard"
                                    },
                                    onBack = { screen.value = "dashboard" }
                                )

                                "security_settings" -> SecuritySettingsScreen(
                                    onBack = {
                                        securitySettings.value = secureStore.load()
                                        screen.value = "dashboard"
                                    }
                                )

                                "seed_recovery" -> {
                                    val s = seed.value
                                    if (s == null) {
                                        screen.value = "dashboard"
                                    } else {
                                        SeedRecoveryScreen(
                                            seed = s,
                                            onBack = { screen.value = "dashboard" }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SeedRecoveryScreen(
    seed: String,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scroll = rememberScrollState()
    val shape = RoundedCornerShape(18.dp)

    var revealed by remember { mutableStateOf(false) }

    val cdBack = stringResource(R.string.cd_back)
    val title = stringResource(R.string.seed_recovery_title)
    val warning = stringResource(R.string.seed_recovery_warning)
    val seedLabel = stringResource(R.string.seed_recovery_seed_label)
    val show = stringResource(R.string.seed_recovery_show)
    val hide = stringResource(R.string.seed_recovery_hide)
    val copy = stringResource(R.string.copy)
    val mustRevealToast = stringResource(R.string.seed_recovery_must_reveal_toast)
    val copiedToast = stringResource(R.string.seed_recovery_copied_toast)
    val tip = stringResource(R.string.seed_recovery_tip)
    val masked = stringResource(R.string.seed_recovery_masked)

    fun toast(msg: String) = Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()

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
                Spacer(Modifier.width(6.dp))
                Text(
                    title,
                    color = PhonColors.Text,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold
                )
            }

            Card(
                shape = shape,
                colors = CardDefaults.cardColors(containerColor = PhonColors.Card),
                border = BorderStroke(1.dp, PhonColors.Border),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(warning, color = PhonColors.Muted, style = MaterialTheme.typography.bodyMedium)

                    val display = if (revealed) seed else masked

                    OutlinedTextField(
                        value = display,
                        onValueChange = {},
                        readOnly = true,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(seedLabel) }
                    )

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        OutlinedButton(
                            onClick = { revealed = !revealed },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(16.dp),
                            border = BorderStroke(1.dp, PhonColors.Border)
                        ) { Text(if (revealed) hide else show) }

                        Button(
                            onClick = {
                                if (!revealed) toast(mustRevealToast)
                                else {
                                    clipboard.setText(AnnotatedString(seed))
                                    toast(copiedToast)
                                }
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Icon(Icons.Filled.ContentCopy, contentDescription = copy)
                            Spacer(Modifier.width(8.dp))
                            Text(copy)
                        }
                    }
                }
            }

            Text(tip, color = PhonColors.Muted, style = MaterialTheme.typography.bodySmall)
        }
    }

    BackHandler { onBack() }
}
