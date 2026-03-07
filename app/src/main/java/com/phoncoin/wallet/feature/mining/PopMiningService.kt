package com.phoncoin.wallet.feature.mining

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.phoncoin.wallet.R
import com.phoncoin.wallet.core.crypto.KeyDerivation
import com.phoncoin.wallet.core.crypto.TxSigner
import com.phoncoin.wallet.core.device.DeviceFingerprint
import com.phoncoin.wallet.core.device.DeviceInfoV4Factory
import com.phoncoin.wallet.core.network.PhoncoinApi
import com.phoncoin.wallet.core.storage.SecureStore
import kotlinx.coroutines.*
import org.json.JSONObject
import org.json.JSONArray
import java.io.File
import java.security.MessageDigest
import kotlin.math.max
import kotlin.random.Random

/**
 * WORLD BEST (Mainnet safe, scalable, "no missed blocks" focus)
 *
 * Keeps:
 * - Foreground service
 * - Same PoP payload/signature/pow/security
 * - 3 attempts per height MAX (hb_rate-aware)
 *
 * Adds (reliability without spam):
 * - Block start estimator (anti-drift)
 * - hb_rate-aware scheduling (prevents "too soon")
 * - Quick retry ONLY on failure/timeout
 * - Emergency tighter polling near end of block
 */
class PopMiningService : Service() {

    // -----------------------------------------------------------------------
    // Emulator guard (client-side) — makes it HARD to fake "is_emulator=false".
    // The node already rejects when device_info.is_emulator == true.
    // -----------------------------------------------------------------------
    private object EmuGuard {
        private val suspiciousStrings = listOf(
            "bluestacks", "bstack", "nox", "ldplayer", "genymotion",
            "android sdk built for", "emulator", "sdk_gphone", "goldfish", "ranchu"
        )

        private val knownPkgs = listOf(
            // BlueStacks / MSI App Player
            "com.bluestacks.appplayer", "com.bluestacks", "com.bluestacks.settings",
            "com.bluestacks.home", "com.bluestacks.bstfolder", "com.bluestacks.appsync",
            // Nox
            "com.bignox.app", "com.nox.mopen.app", "com.nox.play", "com.noxhelper",
            // LDPlayer
            "com.ldmnq.launcher3", "com.ldmnq.keyboard", "com.ldmnq.boyaa",
            // Genymotion
            "com.genymotion", "com.genymotion.superuser"
        )

        private val knownFiles = listOf(
            "/dev/qemu_pipe",
            "/dev/socket/qemud",
            "/system/lib/libc_malloc_debug_qemu.so",
            "/system/bin/qemu-props",
            "/system/bin/bsthelper",
            "/system/bin/nox-prop",
            "/system/bin/ttVM-prop",
            "/data/.bluestacks.prop",
            "/data/.bluestacks",
            "/data/data/com.bluestacks.appplayer",
            "/data/data/com.bignox.app"
        )

        fun evaluate(ctx: Context, info: JSONObject): Pair<Boolean, List<String>> {
            val reasons = mutableListOf<String>()

            fun addIf(cond: Boolean, reason: String) {
                if (cond) reasons.add(reason)
            }

            // 1) ABI signals (most emulators are x86/x86_64)
            val abis = try { Build.SUPPORTED_ABIS?.toList() ?: emptyList() } catch (_: Throwable) { emptyList() }
            val isX86 = abis.any { it.contains("x86", ignoreCase = true) }
            addIf(isX86, "abi_x86")

            // 2) Build.* & reported fields signals (strings)
            val buildSignals = listOf(
                Build.FINGERPRINT ?: "",
                Build.MODEL ?: "",
                Build.MANUFACTURER ?: "",
                Build.BRAND ?: "",
                Build.DEVICE ?: "",
                Build.PRODUCT ?: "",
                Build.HARDWARE ?: ""
            ).joinToString(" ").lowercase()

            val infoSignals = listOf(
                info.optString("manufacturer", ""),
                info.optString("model", ""),
                info.optString("brand", ""),
                info.optString("device", ""),
                info.optString("product", ""),
                info.optString("hardware", "")
            ).joinToString(" ").lowercase()

            val allSignals = "$buildSignals $infoSignals"
            for (s in suspiciousStrings) {
                if (allSignals.contains(s)) {
                    reasons.add("sig_$s")
                }
            }

            // 3) Known emulator packages installed
            try {
                val pm = ctx.packageManager
                for (p in knownPkgs) {
                    try {
                        pm.getPackageInfo(p, 0)
                        reasons.add("pkg_$p")
                        break
                    } catch (_: Throwable) {}
                }
            } catch (_: Throwable) {}

            // 4) Known emulator files present
            for (f in knownFiles) {
                try {
                    if (File(f).exists()) {
                        reasons.add("file_$f")
                        break
                    }
                } catch (_: Throwable) {}
            }

            // 5) System properties (best signal when accessible)
            val qemu1 = getSystemProperty("ro.kernel.qemu")
            val qemu2 = getSystemProperty("ro.boot.qemu")
            addIf(qemu1 == "1" || qemu2 == "1", "prop_qemu")

            // Decision:
            // - Any strong signal => emulator
            // - Or (x86 ABI + any other signal) => emulator
            val strong = reasons.any { it.startsWith("pkg_") || it.startsWith("file_") || it == "prop_qemu" }
            val isEmu = strong || (isX86 && reasons.size >= 2) || reasons.any { it.startsWith("sig_") && it != "sig_" }

            return Pair(isEmu, reasons.distinct())
        }

        private fun getSystemProperty(key: String): String? {
            return try {
                val c = Class.forName("android.os.SystemProperties")
                val m = c.getMethod("get", String::class.java)
                (m.invoke(null, key) as? String)?.takeIf { it.isNotBlank() }
            } catch (_: Throwable) {
                null
            }
        }
    }


    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    private var pollWakeLock: PowerManager.WakeLock? = null
    private var attemptWakeLock: PowerManager.WakeLock? = null

    // Hold lock to prevent Doze freezing (absolute no-miss)
    private var holdWakeLock: PowerManager.WakeLock? = null
    private var lastHoldRefreshMs: Long = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startInForeground(getString(R.string.mining_ready))
        // IMPORTANT:
        // Do NOT reset KEY_RUNNING here.
        // If the OS restarts the process/service (Doze/OEM kill), we must keep the user's
        // "mining enabled" state so onStartCommand() can auto-resume.
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action.orEmpty()

        return when (action) {
            ACTION_STOP -> {
                stopMining()
                START_NOT_STICKY
            }

            ACTION_START -> {
                val nodeUrl = intent?.getStringExtra(EXTRA_NODE_URL).orEmpty().trim()
                if (!isValidNodeUrl(nodeUrl)) {
                    updateNotif("Invalid node URL")
                    stopMining()
                    START_NOT_STICKY
                } else {
                    persistNodeUrl(nodeUrl)
                    startMining(nodeUrl)
                    START_STICKY
                }
            }

            else -> {
                val nodeUrl = intent?.getStringExtra(EXTRA_NODE_URL).orEmpty().trim()
                    .ifBlank { loadNodeUrl() }

                val shouldResume = prefs().getBoolean(KEY_RUNNING, false)

                if (!shouldResume || !isValidNodeUrl(nodeUrl)) {
                    updateNotif(getString(R.string.mining_stopped))
                    stopMining()
                    START_NOT_STICKY
                } else {
                    startMining(nodeUrl)
                    START_STICKY
                }
            }
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // If user enabled mining, and OEM kills after swipe, schedule a soft restart
        if (prefs().getBoolean(KEY_RUNNING, false) && isValidNodeUrl(loadNodeUrl())) {
            scheduleRestart()
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        // If system destroys us unexpectedly while mining is ON, try a soft restart
        val wasRunning = prefs().getBoolean(KEY_RUNNING, false)
        stopMiningInternal(allowRestart = wasRunning)
        scope.cancel()
        super.onDestroy()
    }

    // ---------------------------
    // Foreground
    // ---------------------------
    private fun startInForeground(text: String) {
        val notification = buildNotif(text)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                NOTIF_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun startMining(nodeUrl: String) {
        if (job?.isActive == true) return

        startInForeground(getString(R.string.mining_started))
        persistRunning(true)
        updateNotif(getString(R.string.mining_started))

        // Keep CPU available so we don't miss block windows when app is closed / screen off
        ensureHoldWakeLock()

        job = scope.launch {
            runLoop(nodeUrl)
        }
    }

    private fun stopMining() {
        stopMiningInternal(allowRestart = false)
    }

    private fun stopMiningInternal(allowRestart: Boolean) {
        job?.cancel()
        job = null

        releasePollWakeLock()
        releaseAttemptWakeLock()
        releaseHoldWakeLock()

        persistRunning(false)

        updateNotif(getString(R.string.mining_stopped))
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()

        if (allowRestart && isValidNodeUrl(loadNodeUrl())) {
            scheduleRestart()
        }
    }

    // ---------------------------
    // Loop
    // ---------------------------
    private suspend fun runLoop(nodeUrl: String) {
        val store = SecureStore(applicationContext)
        val seed = store.getSeed()

        if (seed == null) {
            persistRunning(false)
            updateNotif("Wallet not initialized")
            stopMining()
            return
        }

        val pub = KeyDerivation.deriveFromSeed(seed).publicKeyHex
        val fp = DeviceFingerprint.get(applicationContext)
        val (host, port) = parseHostPort(nodeUrl)

        var accepted = prefs().getInt(KEY_ACCEPTED, 0)
        var rejected = prefs().getInt(KEY_REJECTED, 0)
        var lastReason = prefs().getString(KEY_LAST_REASON, "—") ?: "—"

        // Mainnet params
        val blockMs = 300_000L
        val hbRateMs = 180_000L
        val hbGuardMs = hbRateMs + 1_500L

        val basePollMs = 12_000L
        val pollJitterMs = 3_000L
        val emergencyPollMs = 4_000L
        val offlineRetryMs = 20_000L

        var lastAttemptMs = prefs().getLong(KEY_LAST_ATTEMPT_MS, 0L)
        fun persistLastAttempt(ms: Long) {
            lastAttemptMs = ms
            prefs().edit().putLong(KEY_LAST_ATTEMPT_MS, ms).apply()
        }

        // Block start estimator (anti-drift)
        var estHeight = prefs().getInt(KEY_EST_HEIGHT, -1)
        var estStartMs = prefs().getLong(KEY_EST_START_MS, 0L)

        fun persistEstimator() {
            prefs().edit()
                .putInt(KEY_EST_HEIGHT, estHeight)
                .putLong(KEY_EST_START_MS, estStartMs)
                .apply()
        }

        fun updateEstimator(h: Int, now: Long) {
            if (estHeight < 0 || estStartMs <= 0L) {
                estHeight = h
                estStartMs = now
                persistEstimator()
                return
            }
            if (h == estHeight) return

            if (h > estHeight) {
                val delta = (h - estHeight).toLong()
                val expected = estStartMs + delta * blockMs
                val drift = now - expected
                val correction = drift.coerceIn(-20_000L, 20_000L)
                estStartMs = expected + correction
                estHeight = h
                persistEstimator()
            } else {
                estHeight = h
                estStartMs = now
                persistEstimator()
            }
        }

        fun makeOffsetsWorldScale(): LongArray {
            val a1 = Random.nextLong(60_000L, 90_001L)
            val a2 = Random.nextLong(200_000L, 230_001L)
            val a3 = Random.nextLong(275_000L, 295_001L)
            return longArrayOf(a1, a2, a3)
        }

        var attemptOffsetsMs = makeOffsetsWorldScale()

        var lastObservedHeight = -1
        var heightStartMs = 0L
        var attemptsThisHeight = 0
        var nextAttemptAtMs = Long.MAX_VALUE
        var acceptedThisHeight = false
        var quickRetryUsedThisHeight = false

        fun scheduleAttempt(now: Long) {
            if (attemptsThisHeight >= attemptOffsetsMs.size) {
                nextAttemptAtMs = Long.MAX_VALUE
                return
            }
            val jitter = Random.nextLong(-4_000L, 4_001L)
            var candidate = heightStartMs + attemptOffsetsMs[attemptsThisHeight] + jitter

            val minAllowed = lastAttemptMs + hbGuardMs
            if (candidate < minAllowed) candidate = minAllowed
            if (candidate < now) candidate = now

            nextAttemptAtMs = candidate
        }

        while (currentCoroutineContext().isActive) {
            ensureHoldWakeLock()

            try {
                acquirePollWakeLock(timeoutMs = 6_000L)
                try {
                    val net = withContext(Dispatchers.IO) { PhoncoinApi.getNetworkInfoParsed(nodeUrl) }
                    val h = net?.height ?: 0

                    if (h <= 0) {
                        lastReason = "offline"
                        persistLastReason(lastReason)
                        updateNotif(getString(R.string.mining_status_fmt, accepted, rejected, 0, lastReason))
                        delay(offlineRetryMs)
                        continue
                    }

                    val nowMs = System.currentTimeMillis()
                    updateEstimator(h, nowMs)

                    if (h != lastObservedHeight) {
                        lastObservedHeight = h
                        heightStartMs = estStartMs
                        attemptsThisHeight = 0
                        acceptedThisHeight = false
                        quickRetryUsedThisHeight = false
                        attemptOffsetsMs = makeOffsetsWorldScale()
                        scheduleAttempt(nowMs)
                    }

                    val phaseMs = (nowMs - heightStartMs).coerceAtLeast(0L).coerceAtMost(blockMs)
                    val inEmergency = !acceptedThisHeight && phaseMs >= 240_000L

                    val shouldAttemptNow =
                        (!acceptedThisHeight) &&
                            (attemptsThisHeight < attemptOffsetsMs.size) &&
                            (nowMs >= nextAttemptAtMs)

                    if (shouldAttemptNow) {
                        acquireAttemptWakeLock(timeoutMs = 45_000L)
                        try {
                            val zeros = max(1, net?.effectiveDifficultyZeros ?: net?.difficultyZeros ?: 2)
                            val ts = (System.currentTimeMillis() / 1000L).toInt()
                            val (nonce, _) = minePow(pub, ts, fp, zeros)

                            val payload = (pub + ts.toString() + nonce + fp).encodeToByteArray()
                            val sigHex = TxSigner.signPayloadHex(seed, payload)

                            val deviceInfoV4 = DeviceInfoV4Factory.build(
                                context = applicationContext,
                                nodeHost = host,
                                nodePort = port
                            ).toJson()

                            // Extra emulator guard (server will reject if is_emulator=true)
                            val (emuDetected, emuSignals) = EmuGuard.evaluate(applicationContext, deviceInfoV4)
                            deviceInfoV4.put("is_emulator", emuDetected)
                            deviceInfoV4.put("emulator_signals", JSONArray(emuSignals))
                            if (emuDetected) {
                                // Force score to 0 so any server-side scoring also rejects
                                deviceInfoV4.put("trust_score", 0)
                                deviceInfoV4.put("score", 0)
                            }

                            val heartbeat = JSONObject().apply {
                                put("pubkey", pub)
                                put("timestamp", ts)
                                put("nonce", nonce)
                                put("signature", sigHex)
                                put("device_fingerprint", fp)
                                put("device_info", deviceInfoV4)
                            }

                            val body = JSONObject().apply { put("heartbeat", heartbeat) }
                            persistLastPayload(body.toString())

                            persistLastAttempt(System.currentTimeMillis())

                            val res = withContext(Dispatchers.IO) { PhoncoinApi.submitProof(nodeUrl, body) }

                            if (res.ok) {
                                attemptsThisHeight++
                                acceptedThisHeight = true
                                accepted++
                                lastReason = "accepted"
                                persistAccepted(accepted)
                                persistLastSubmitTs(System.currentTimeMillis())
                                nextAttemptAtMs = Long.MAX_VALUE
                            } else {
                                lastReason = res.reason.ifBlank { "rejected" }
                                rejected++
                                persistRejected(rejected)

                                val r = lastReason.lowercase()
                                val rateLimited =
                                    r.contains("too soon") || r.contains("rate") || r.contains("cooldown")

                                if (rateLimited) {
                                    nextAttemptAtMs = System.currentTimeMillis() + hbGuardMs
                                } else {
                                    attemptsThisHeight++
                                    scheduleAttempt(System.currentTimeMillis())
                                }
                            }

                            persistLastReason(lastReason)
                            persistLastDiff(zeros)
                            updateNotif(getString(R.string.mining_status_fmt, accepted, rejected, zeros, lastReason))
                        } finally {
                            releaseAttemptWakeLock()
                        }
                    } else {
                        if (lastReason != "waiting_next_block" && !acceptedThisHeight) {
                            lastReason = "waiting_next_block"
                            persistLastReason(lastReason)
                            updateNotif(getString(R.string.mining_status_fmt, accepted, rejected, 0, lastReason))
                        }
                    }

                    val jitter = Random.nextLong(0L, pollJitterMs + 1)
                    val sleepMs = if (inEmergency) emergencyPollMs else (basePollMs + jitter)
                    delay(sleepMs)

                } finally {
                    releasePollWakeLock()
                }
            } catch (e: Exception) {
                lastReason = e.message?.take(48) ?: "error"
                persistLastReason(lastReason)
                updateNotif(getString(R.string.mining_status_fmt, accepted, rejected, 0, lastReason))

                val now = System.currentTimeMillis()
                val phaseMs = (now - heightStartMs).coerceAtLeast(0L)
                val canStillTryThisBlock = !acceptedThisHeight &&
                    attemptsThisHeight < attemptOffsetsMs.size &&
                    phaseMs < (blockMs - 8_000L)

                if (canStillTryThisBlock && !quickRetryUsedThisHeight) {
                    quickRetryUsedThisHeight = true
                    nextAttemptAtMs = max(now + Random.nextLong(8_000L, 12_001L), lastAttemptMs + hbGuardMs)
                    delay(2_000L)
                } else {
                    delay(offlineRetryMs)
                }
            }
        }
    }

    // ---------------------------
    // Soft restart (OEM kill protection)
    // ---------------------------
    private fun scheduleRestart() {
        val nodeUrl = loadNodeUrl()
        if (!isValidNodeUrl(nodeUrl)) return

        val am = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val i = Intent(applicationContext, PopMiningService::class.java).apply {
            action = ACTION_START
            putExtra(EXTRA_NODE_URL, nodeUrl)
        }

        val pi = PendingIntent.getService(
            applicationContext,
            RESTART_REQ,
            i,
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
        )

        val t = System.currentTimeMillis() + 2_000L
        if (Build.VERSION.SDK_INT >= 23) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t, pi)
        } else {
            am.setExact(AlarmManager.RTC_WAKEUP, t, pi)
        }
    }

    // ---------------------------
    // Persistence
    // ---------------------------
    private fun prefs() = getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun persistRunning(v: Boolean) {
        prefs().edit().putBoolean(KEY_RUNNING, v).apply()
    }

    private fun persistNodeUrl(v: String) {
        prefs().edit().putString(KEY_NODE_URL, v).apply()
    }

    private fun loadNodeUrl(): String =
        prefs().getString(KEY_NODE_URL, "")?.trim().orEmpty()

    private fun persistAccepted(v: Int) {
        prefs().edit().putInt(KEY_ACCEPTED, v).apply()
    }

    private fun persistRejected(v: Int) {
        prefs().edit().putInt(KEY_REJECTED, v).apply()
    }

    private fun persistLastReason(v: String) {
        prefs().edit().putString(KEY_LAST_REASON, v).apply()
    }

    private fun persistLastDiff(v: Int) {
        prefs().edit().putInt(KEY_LAST_DIFF, v).apply()
    }

    private fun persistLastSubmitTs(v: Long) {
        prefs().edit().putLong(KEY_LAST_SUBMIT_TS, v).apply()
    }

    private fun persistLastPayload(v: String) {
        prefs().edit().putString(KEY_LAST_PAYLOAD, v).apply()
    }

    // ---------------------------
    // WakeLocks
    // ---------------------------
    private fun acquirePollWakeLock(timeoutMs: Long = 6_000L) {
        if (pollWakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        pollWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PHONCOIN:PoPPoll").apply {
            setReferenceCounted(false)
            acquire(timeoutMs)
        }
    }

    private fun releasePollWakeLock() {
        try {
            if (pollWakeLock?.isHeld == true) pollWakeLock?.release()
        } catch (_: Exception) {
        } finally {
            pollWakeLock = null
        }
    }

    private fun acquireAttemptWakeLock(timeoutMs: Long = 45_000L) {
        if (attemptWakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        attemptWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PHONCOIN:PoPAttempt").apply {
            setReferenceCounted(false)
            acquire(timeoutMs)
        }
    }

    private fun releaseAttemptWakeLock() {
        try {
            if (attemptWakeLock?.isHeld == true) attemptWakeLock?.release()
        } catch (_: Exception) {
        } finally {
            attemptWakeLock = null
        }
    }

    private fun ensureHoldWakeLock() {
        val now = System.currentTimeMillis()
        if (holdWakeLock?.isHeld == true && (now - lastHoldRefreshMs) < 120_000L) return

        try {
            if (holdWakeLock?.isHeld == true) holdWakeLock?.release()
        } catch (_: Exception) {
        }

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        holdWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PHONCOIN:PoPHold").apply {
            setReferenceCounted(false)
            acquire(10 * 60_000L)
        }
        lastHoldRefreshMs = now
    }

    private fun releaseHoldWakeLock() {
        try {
            if (holdWakeLock?.isHeld == true) holdWakeLock?.release()
        } catch (_: Exception) {
        } finally {
            holdWakeLock = null
            lastHoldRefreshMs = 0L
        }
    }

    // ---------------------------
    // Helpers
    // ---------------------------
    private fun isValidNodeUrl(url: String): Boolean {
        val u = url.trim()
        if (u.isBlank()) return false
        if (!(u.startsWith("http://") || u.startsWith("https://"))) return false
        return true
    }

    private fun parseHostPort(nodeUrl: String): Pair<String, Int> {
        val trimmed = nodeUrl.trim()
        val isHttps = trimmed.startsWith("https://")
        val u = trimmed.removePrefix("http://").removePrefix("https://")
        val hostPort = u.substringBefore("/")
        val host = hostPort.substringBefore(":")
        val defaultPort = if (isHttps) 443 else 80
        val port = hostPort.substringAfter(":", defaultPort.toString()).toIntOrNull() ?: defaultPort
        return host to port
    }

    private fun minePow(pub: String, ts: Int, fp: String, zeros: Int): Pair<String, String> {
        val prefix = "0".repeat(zeros)
        while (true) {
            val nonce = Random.nextLong().toString(16)
            val h = sha256Hex((pub + ts.toString() + nonce + fp).toByteArray())
            if (h.startsWith(prefix)) return nonce to h
        }
    }

    private fun sha256Hex(b: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        val d = md.digest(b)
        return d.joinToString("") { "%02x".format(it) }
    }

    // ---------------------------
    // Notifications
    // ---------------------------
    private fun buildNotif(text: String): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.mining_notif_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "PoP Mining foreground service"
                setShowBadge(false)
                enableVibration(false)
                setSound(null, null)
            }
            nm.createNotificationChannel(ch)
        }

        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        val pendingIntent = if (launchIntent != null) {
            PendingIntent.getActivity(
                this,
                0,
                launchIntent,
                (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0) or
                    PendingIntent.FLAG_UPDATE_CURRENT
            )
        } else null

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.mining_notif_title))
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(pendingIntent)
            .build()
    }

    private fun updateNotif(text: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotif(text))
    }

    companion object {
        private const val CHANNEL_ID = "phoncoin_mining"
        private const val NOTIF_ID = 7001
        private const val RESTART_REQ = 9909

        const val EXTRA_NODE_URL = "nodeUrl"
        const val ACTION_START = "com.phoncoin.wallet.mining.START"
        const val ACTION_STOP = "com.phoncoin.wallet.mining.STOP"

        private const val PREFS = "pop_mining_prefs"
        private const val KEY_RUNNING = "running"
        private const val KEY_NODE_URL = "nodeUrl"

        private const val KEY_ACCEPTED = "accepted"
        private const val KEY_REJECTED = "rejected"
        private const val KEY_LAST_REASON = "lastReason"
        private const val KEY_LAST_DIFF = "lastDiff"
        private const val KEY_LAST_SUBMIT_TS = "lastSubmitTs"
        private const val KEY_LAST_PAYLOAD = "lastPayload"

        private const val KEY_LAST_ATTEMPT_MS = "lastAttemptMs"
        private const val KEY_EST_HEIGHT = "estHeight"
        private const val KEY_EST_START_MS = "estStartMs"
    }
}