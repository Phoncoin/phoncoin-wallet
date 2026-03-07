package com.phoncoin.wallet.core.device

import android.content.Context
import android.os.Build
import org.json.JSONObject
import java.io.File
import java.util.Locale

data class DeviceInfoV4(
    val schema: String = "v4",
    val androidId: String,
    val manufacturer: String,
    val model: String,
    val brand: String,
    val device: String,
    val hardware: String,
    val product: String,
    val androidVersion: String,
    val sdkInt: Int,
    val isEmulator: Boolean,
    val isRooted: Boolean,
    val latencyMs: Int = -1,
    val playIntegrity: String = "unknown",
    val trustScore: Int = 0
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("schema", schema)
        put("android_id", androidId)
        put("manufacturer", manufacturer)
        put("model", model)
        put("brand", brand)
        put("device", device)
        put("hardware", hardware)
        put("product", product)
        put("android_version", androidVersion)
        put("sdk_int", sdkInt)
        put("is_emulator", isEmulator)
        put("is_rooted", isRooted)
        put("latency_ms", latencyMs)
        put("play_integrity", playIntegrity)
        put("trust_score", trustScore)
    }
}

object DeviceInfoV4Factory {

    fun build(context: Context, nodeHost: String, nodePort: Int): DeviceInfoV4 {
        val appCtx = context.applicationContext

        val androidId = safeAndroidId(appCtx)
        val manufacturer = (Build.MANUFACTURER ?: "").lowercase(Locale.US)
        val model = (Build.MODEL ?: "").trim()
        val brand = (Build.BRAND ?: "").lowercase(Locale.US)
        val device = (Build.DEVICE ?: "").lowercase(Locale.US)
        val hardware = (Build.HARDWARE ?: "").lowercase(Locale.US)
        val product = (Build.PRODUCT ?: "").lowercase(Locale.US)
        val androidVersion = (Build.VERSION.RELEASE ?: "").trim()
        val sdkInt = Build.VERSION.SDK_INT

        val rooted = isDeviceRooted()
        val emulator = isProbablyEmulator(appCtx)

        // Trust score:
        // - Goal: never block real phones by score alone.
        // - Emulator/root triggers hard reject elsewhere.
        var score = 90

        if (rooted) score = minOf(score, 30)
        if (emulator) score = 0

        return DeviceInfoV4(
            androidId = androidId,
            manufacturer = manufacturer,
            model = model,
            brand = brand,
            device = device,
            hardware = hardware,
            product = product,
            androidVersion = androidVersion,
            sdkInt = sdkInt,
            isEmulator = emulator,
            isRooted = rooted,
            latencyMs = -1,
            playIntegrity = "unknown",
            trustScore = score
        )
    }

    private fun safeAndroidId(context: Context): String {
        return try {
            android.provider.Settings.Secure.getString(
                context.contentResolver,
                android.provider.Settings.Secure.ANDROID_ID
            ) ?: ""
        } catch (_: Throwable) {
            ""
        }
    }

    private fun isDeviceRooted(): Boolean {
        // Conservative root checks (won't false-positive normal devices)
        val paths = arrayOf(
            "/system/bin/su",
            "/system/xbin/su",
            "/sbin/su",
            "/system/app/Superuser.apk",
            "/system/app/Magisk.apk",
            "/data/local/xbin/su",
            "/data/local/bin/su",
            "/system/bin/.ext/.su"
        )
        if (paths.any { File(it).exists() }) return true

        val tags = Build.TAGS ?: ""
        if (tags.contains("test-keys")) return true

        return false
    }

    private fun isProbablyEmulator(context: Context): Boolean {
        // 1) Build fingerprints / model / product signals
        val fp = (Build.FINGERPRINT ?: "").lowercase(Locale.US)
        val model = (Build.MODEL ?: "").lowercase(Locale.US)
        val brand = (Build.BRAND ?: "").lowercase(Locale.US)
        val device = (Build.DEVICE ?: "").lowercase(Locale.US)
        val product = (Build.PRODUCT ?: "").lowercase(Locale.US)
        val hardware = (Build.HARDWARE ?: "").lowercase(Locale.US)
        val manufacturer = (Build.MANUFACTURER ?: "").lowercase(Locale.US)

        val buildSignals = listOf(
            fp.contains("generic"),
            fp.contains("unknown"),
            fp.contains("vbox"),
            fp.contains("test-keys"),
            model.contains("google_sdk"),
            model.contains("emulator"),
            model.contains("android sdk built for x86"),
            model.contains("bluestacks"),
            model.contains("nox"),
            model.contains("ldplayer"),
            manufacturer.contains("genymotion"),
            manufacturer.contains("bluestacks"),
            brand.startsWith("generic"),
            device.startsWith("generic"),
            product.contains("sdk"),
            product.contains("emulator"),
            hardware.contains("goldfish"),
            hardware.contains("ranchu"),
            hardware.contains("vbox"),
            hardware.contains("qemu"),
            hardware.contains("ttvm"),     // some emulators
            hardware.contains("nox"),
            hardware.contains("bluestacks")
        )

        // 2) Known emulator packages (very strong)
        val knownPkgs = listOf(
            "com.bluestacks",
            "com.bluestacks.appplayer",
            "com.bluestacks.settings",
            "com.mumu.launcher",
            "com.nox.mopen.app",
            "com.noxgroup.app",
            "com.ldmnq.launcher3",
            "com.microvirt.launcher",
            "com.microvirt.market",
            "com.genymotion.superuser",
            "com.vphone.launcher",
            "com.android.fakedevice"
        )
        val hasEmuPkg = hasAnyPackage(context, knownPkgs)

        // 3) Known emulator files (strong)
        val knownFiles = listOf(
            "/system/bin/bsthelper",
            "/system/bin/bstshutdown",
            "/system/bin/bstmetrics",
            "/system/lib/libbst_vm.so",
            "/system/lib64/libbst_vm.so",
            "/data/data/com.bluestacks.settings",
            "/init.goldfish.rc",
            "/init.ranchu.rc"
        )
        val hasEmuFiles = knownFiles.any { File(it).exists() }

        // 4) Heuristic threshold:
        // - true phone should not hit multiple categories.
        // - emulator usually hits pkg OR files OR many build signals.
        val buildScore = buildSignals.count { it }
        if (hasEmuPkg) return true
        if (hasEmuFiles) return true
        if (buildScore >= 3) return true

        return false
    }

    private fun hasAnyPackage(context: Context, pkgs: List<String>): Boolean {
        return try {
            val pm = context.packageManager
            for (p in pkgs) {
                try {
                    pm.getPackageInfo(p, 0)
                    return true
                } catch (_: Throwable) {
                }
            }
            false
        } catch (_: Throwable) {
            false
        }
    }
}