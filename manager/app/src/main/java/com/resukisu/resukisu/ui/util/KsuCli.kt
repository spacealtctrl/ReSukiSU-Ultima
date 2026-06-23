package com.resukisu.resukisu.ui.util

import android.annotation.SuppressLint
import android.net.Uri
import android.os.Environment
import android.os.Parcelable
import android.os.SystemClock
import android.system.Os
import android.util.Log
import com.resukisu.resukisu.BuildConfig
import com.resukisu.resukisu.Natives
import android.content.Context
import com.resukisu.resukisu.ksuApp
import com.topjohnwu.superuser.CallbackList
import com.topjohnwu.superuser.Shell
import com.topjohnwu.superuser.ShellUtils
import com.topjohnwu.superuser.io.SuFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.parcelize.Parcelize
import org.json.JSONArray
import java.io.File
import java.util.Properties

/**
 * @author weishu
 * @date 2023/1/1.
 */
private const val TAG = "KsuCli"

private fun getKsuDaemonPath(): String {
    return ksuApp.applicationInfo.nativeLibraryDir + File.separator + "libksud.so"
}

@SuppressLint("RestrictedApi")
object KsuCli {
    var SHELL: Shell = createRootShell()
    val GLOBAL_MNT_SHELL: Shell = createRootShell(true)
}

fun getRootShell(globalMnt: Boolean = false): Shell {
    return if (globalMnt) KsuCli.GLOBAL_MNT_SHELL else {
        KsuCli.SHELL
    }
}

fun generateMainShellBuilder(): Shell.Builder {
    val builder = Shell.Builder.create()
    try {
        builder.setCommands(getKsuDaemonPath(), "debug", "su")
        builder.build()
    } catch (e: Throwable) {
        Log.w(TAG, "ksu failed: ", e)
        try {
            builder.setCommands("su")
            builder.build()
        } catch (e: Throwable) {
            Log.e(TAG, "su failed: ", e)
            builder.setCommands("sh")
            builder.build()
        }
    }

    return builder
}

inline fun <T> withNewRootShell(
    globalMnt: Boolean = false,
    block: Shell.() -> T
): T {
    return createRootShell(globalMnt).use(block)
}

fun createRootShell(globalMnt: Boolean = false): Shell {
    Shell.enableVerboseLogging = BuildConfig.DEBUG
    val builder = Shell.Builder.create()
    return try {
        if (globalMnt) {
            builder.build(getKsuDaemonPath(), "debug", "su", "-g")
        } else {
            builder.build(getKsuDaemonPath(), "debug", "su")
        }
    } catch (e: Throwable) {
        Log.w(TAG, "ksu failed: ", e)
        try {
            if (globalMnt) {
                builder.build("su", "-mm")
            } else {
                builder.build("su")
            }
        } catch (e: Throwable) {
            Log.e(TAG, "su failed: ", e)
            builder.build("sh")
        }
    }
}

fun execKsud(args: String, newShell: Boolean = false): Boolean {
    return if (newShell) {
        withNewRootShell {
            ShellUtils.fastCmdResult(this, "${getKsuDaemonPath()} $args")
        }
    } else {
        ShellUtils.fastCmdResult(getRootShell(), "${getKsuDaemonPath()} $args")
    }
}

suspend fun isOfficialSignature(): Boolean = withContext(Dispatchers.IO) {
    val shell = getRootShell()
    val out = shell.newJob()
        .add("${getKsuDaemonPath()} debug get-sign ${ksuApp.packageResourcePath}")
        .to(ArrayList<String>(), null).exec().out
    out.firstOrNull()?.trim()
        .orEmpty() == "size: 0x30a, hash: d73fecfae51cbcbd00b3bac283809f0d5aed863a9f8604f1d5bd6bba5fe0c435"
}

suspend fun getFeatureStatus(feature: String): String = withContext(Dispatchers.IO) {
    val shell = getRootShell()
    val out = shell.newJob()
        .add("${getKsuDaemonPath()} feature check $feature").to(ArrayList<String>(), null).exec().out
    out.firstOrNull()?.trim().orEmpty()
}

suspend fun getFeaturePersistValue(feature: String): Long? = withContext(Dispatchers.IO) {
    val shell = getRootShell()
    val out = shell.newJob()
        .add("${getKsuDaemonPath()} feature get --config $feature").to(ArrayList<String>(), null)
        .exec().out
    val valueLine = out.firstOrNull { it.trim().startsWith("Value:") } ?: return@withContext null
    valueLine.substringAfter("Value:").trim().toLongOrNull()
}

// ---- Sentinel (root-probe detector) ----

data class SentinelProbe(val uid: Int, val count: Int, val path: String)

fun setSentinel(enable: Boolean): Boolean = execKsud("sentinel " + if (enable) "on" else "off")

fun setSentinelAuto(enable: Boolean): Boolean = execKsud("sentinel auto " + if (enable) "on" else "off")

fun sentinelCloak(uid: Int): Boolean = execKsud("sentinel cloak $uid")

fun sentinelUncloak(uid: Int): Boolean = execKsud("sentinel uncloak $uid")

/** Returns (enabled, autoCloak). */
suspend fun getSentinelStatus(): Pair<Boolean, Boolean> = withContext(Dispatchers.IO) {
    val out = getRootShell().newJob()
        .add("${getKsuDaemonPath()} sentinel status").to(ArrayList<String>(), null).exec().out
        .joinToString("")
    Pair(out.contains("\"enabled\":true"), out.contains("\"auto\":true"))
}

suspend fun getSentinelCloaked(): List<Int> = withContext(Dispatchers.IO) {
    getRootShell().newJob()
        .add("${getKsuDaemonPath()} sentinel cloaked").to(ArrayList<String>(), null).exec().out
        .mapNotNull { it.trim().toIntOrNull() }
}

/** Blocking cloak check (call off the main thread). A cloaked app must not be
 *  able to raise root requests, so the notification path consults this. */
fun isSentinelCloaked(uid: Int): Boolean = runCatching {
    getRootShell().newJob()
        .add("${getKsuDaemonPath()} sentinel cloaked").to(ArrayList<String>(), null).exec().out
        .any { it.trim().toIntOrNull() == uid }
}.getOrDefault(false)

suspend fun drainSentinelProbes(): List<SentinelProbe> = withContext(Dispatchers.IO) {
    val out = getRootShell().newJob()
        .add("${getKsuDaemonPath()} sentinel drain").to(ArrayList<String>(), null).exec().out
        .joinToString("")
    val probes = mutableListOf<SentinelProbe>()
    runCatching {
        val arr = JSONArray(out.trim().ifEmpty { "[]" })
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            probes.add(SentinelProbe(o.optInt("uid"), o.optInt("count"), o.optString("path")))
        }
    }
    probes
}

/** A probing app from the kernel's persistent history (survives until reboot). */
data class SentinelHistEntry(val uid: Int, val count: Int, val kinds: Int, val lastNs: Long)

suspend fun getSentinelHistory(): List<SentinelHistEntry> = withContext(Dispatchers.IO) {
    val out = getRootShell().newJob()
        .add("${getKsuDaemonPath()} sentinel history").to(ArrayList<String>(), null).exec().out
        .joinToString("")
    val list = mutableListOf<SentinelHistEntry>()
    runCatching {
        val arr = JSONArray(out.trim().ifEmpty { "[]" })
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            list.add(SentinelHistEntry(o.optInt("uid"), o.optInt("count"), o.optInt("kinds"), o.optLong("last_ns")))
        }
    }
    list
}

/** Disable (freeze) or re-enable an app; records the change for uncloak-restore. */
fun setAppEnabled(pkg: String, enabled: Boolean): Boolean {
    val ok = ShellUtils.fastCmdResult(
        getRootShell(),
        if (enabled) "pm enable $pkg" else "pm disable-user --user 0 $pkg"
    )
    managedPrefs().edit().putBoolean("disabled_$pkg", !enabled).apply()
    return ok
}

// ---- per-permission control + per-app manage tracking (so uncloak can revert) ----

private fun managedPrefs() =
    ksuApp.getSharedPreferences("sentinel_managed", Context.MODE_PRIVATE)

/**
 * Map a permission to its appop name, or null if it has no appop. Curated (not a
 * blind prefix strip) so callers can tell a genuinely appop-controllable perm
 * from a plain install-time one (e.g. INTERNET) that cannot be blocked at all.
 */
internal fun opForPermission(perm: String): String? = when (perm) {
    "android.permission.ACCESS_FINE_LOCATION" -> "FINE_LOCATION"
    "android.permission.ACCESS_COARSE_LOCATION" -> "COARSE_LOCATION"
    "android.permission.ACCESS_BACKGROUND_LOCATION" -> "FINE_LOCATION"
    "android.permission.ACCESS_MEDIA_LOCATION" -> "ACCESS_MEDIA_LOCATION"
    "android.permission.CAMERA" -> "CAMERA"
    "android.permission.RECORD_AUDIO" -> "RECORD_AUDIO"
    "android.permission.READ_CONTACTS" -> "READ_CONTACTS"
    "android.permission.WRITE_CONTACTS" -> "WRITE_CONTACTS"
    "android.permission.READ_CALENDAR" -> "READ_CALENDAR"
    "android.permission.WRITE_CALENDAR" -> "WRITE_CALENDAR"
    "android.permission.READ_SMS" -> "READ_SMS"
    "android.permission.SEND_SMS" -> "SEND_SMS"
    "android.permission.RECEIVE_SMS" -> "RECEIVE_SMS"
    "android.permission.READ_PHONE_STATE" -> "READ_PHONE_STATE"
    "android.permission.READ_PHONE_NUMBERS" -> "READ_PHONE_NUMBERS"
    "android.permission.CALL_PHONE" -> "CALL_PHONE"
    "android.permission.READ_CALL_LOG" -> "READ_CALL_LOG"
    "android.permission.WRITE_CALL_LOG" -> "WRITE_CALL_LOG"
    "android.permission.READ_EXTERNAL_STORAGE" -> "READ_EXTERNAL_STORAGE"
    "android.permission.WRITE_EXTERNAL_STORAGE" -> "WRITE_EXTERNAL_STORAGE"
    "android.permission.READ_MEDIA_IMAGES" -> "READ_MEDIA_IMAGES"
    "android.permission.READ_MEDIA_VIDEO" -> "READ_MEDIA_VIDEO"
    "android.permission.READ_MEDIA_AUDIO" -> "READ_MEDIA_AUDIO"
    "android.permission.BODY_SENSORS" -> "BODY_SENSORS"
    "android.permission.ACTIVITY_RECOGNITION" -> "ACTIVITY_RECOGNITION"
    "android.permission.POST_NOTIFICATIONS" -> "POST_NOTIFICATION"
    "android.permission.SYSTEM_ALERT_WINDOW" -> "SYSTEM_ALERT_WINDOW"
    "android.permission.WRITE_SETTINGS" -> "WRITE_SETTINGS"
    "android.permission.PACKAGE_USAGE_STATS" -> "GET_USAGE_STATS"
    "android.permission.REQUEST_INSTALL_PACKAGES" -> "REQUEST_INSTALL_PACKAGES"
    "android.permission.SCHEDULE_EXACT_ALARM" -> "SCHEDULE_EXACT_ALARM"
    else -> null
}

/** Whether a permission can actually be blocked (runtime revoke or an appop). */
fun isPermissionBlockable(perm: String, dangerous: Boolean): Boolean =
    dangerous || opForPermission(perm) != null

/** Current appop modes for a package, op-name -> mode (allow/ignore/deny/…). */
suspend fun getAppOpsModes(pkg: String): Map<String, String> = withContext(Dispatchers.IO) {
    val out = getRootShell().newJob()
        .add("cmd appops get $pkg").to(ArrayList<String>(), null).exec().out
    val map = mutableMapOf<String, String>()
    val re = Regex("([A-Z_]+):\\s*(allow|ignore|deny|default|foreground)")
    for (line in out) re.find(line)?.let { map[it.groupValues[1]] = it.groupValues[2] }
    map
}

/**
 * Block or allow a permission. `pm revoke` only works on runtime (dangerous)
 * perms, so also drive the appop (block -> ignore, allow -> allow) which covers
 * appop-backed perms (overlay, usage-stats, …). Records the decision for restore.
 */
fun setPermissionMode(pkg: String, perm: String, block: Boolean) {
    val shell = getRootShell()
    val op = opForPermission(perm)
    if (block) {
        shell.newJob().add("pm revoke $pkg $perm").exec()
        if (op != null) shell.newJob().add("cmd appops set $pkg $op ignore").exec()
    } else {
        shell.newJob().add("pm grant $pkg $perm").exec()
        if (op != null) shell.newJob().add("cmd appops set $pkg $op allow").exec()
    }
    val prefs = managedPrefs()
    val key = "perms_$pkg"
    val set = prefs.getStringSet(key, emptySet())!!.toMutableSet()
    if (block) set.add(perm) else set.remove(perm)
    prefs.edit().putStringSet(key, set).apply()
}

/**
 * Uncloak an app and restore everything Sentinel changed back to default:
 * re-grant any permissions it blocked and re-enable it if it was frozen.
 */
suspend fun uncloakRestore(uid: Int) = withContext(Dispatchers.IO) {
    sentinelUncloak(uid)
    val pkg = ksuApp.packageManager.getPackagesForUid(uid)?.firstOrNull()
    if (pkg != null) {
        val prefs = managedPrefs()
        val shell = getRootShell()
        prefs.getStringSet("perms_$pkg", emptySet())?.forEach { perm ->
            shell.newJob().add("pm grant $pkg $perm").exec()
            opForPermission(perm)?.let { op ->
                shell.newJob().add("cmd appops set $pkg $op allow").exec()
            }
        }
        if (prefs.getBoolean("disabled_$pkg", false)) {
            shell.newJob().add("pm enable $pkg").exec()
        }
        prefs.edit().remove("perms_$pkg").remove("disabled_$pkg").apply()
    }
}

/** Clear the kernel's persistent probe history (empties Recent probes). */
suspend fun clearSentinelHistory(): Boolean = withContext(Dispatchers.IO) {
    ShellUtils.fastCmdResult(getRootShell(), "${getKsuDaemonPath()} sentinel clear")
}

/**
 * Spy log source. Apps log little under their own uid, and the interesting
 * activity lands elsewhere: Play Integrity in GMS, store calls in vending, and
 * hardware key attestation in the keystore/KeyMint HAL (system). So merge the
 * target app + GMS + Play Store (by uid) with the keystore/KeyMint HAL (by tag),
 * time-sorted into one stream.
 */
suspend fun dumpAppLog(uid: Int, lines: Int = 300): List<String> = withContext(Dispatchers.IO) {
    val pm = ksuApp.packageManager
    fun uidOf(pkg: String) = runCatching { pm.getPackageUid(pkg, 0) }.getOrNull()
    // NOTE: this logcat rejects comma uid-lists, so run one --uid per uid. And -t
    // is applied BEFORE the tag filter, so the keystore tag stream uses no -t
    // (it's sparse anyway) to guarantee attestation logs are never dropped.
    val perUid = listOfNotNull(uid, uidOf("com.google.android.gms"), uidOf("com.android.vending"))
        .distinct()
        .joinToString("; ") { "logcat -d --uid=$it -v threadtime -t $lines" }
    val ksTags = "keystore2 KeyMintDevice KeyMasterHalDevice KeymasterUtils " +
        "Keymaster credstore DroidGuard"
    val cmd = "{ $perUid; logcat -d -s $ksTags -v threadtime; } | sort -k1,2 -s | uniq"
    getRootShell().newJob().add(cmd).to(ArrayList<String>(), null).exec().out
}

// ---- Built-in Zygisk (Zygisk-Ultima). Off by default; deployed under ksud, not
// a module. The launch script in post-fs-data.d is the on/off + kill switch. ----

private const val ZYGISK_DIR = "/data/adb/ksu/zygisk"
private const val ZYGISK_LAUNCH_DST = "/data/adb/post-fs-data.d/zygisk-ultima.sh"

private fun copyAssetToCache(name: String): File {
    val out = File(ksuApp.cacheDir, name.substringAfterLast('/'))
    ksuApp.assets.open(name).use { input -> out.outputStream().use { input.copyTo(it) } }
    return out
}

/** Enabled = engine deployed + launch hook installed. */
suspend fun isZygiskEnabled(): Boolean = withContext(Dispatchers.IO) {
    ShellUtils.fastCmdResult(getRootShell(), "[ -f $ZYGISK_DIR/enable ] && [ -f $ZYGISK_LAUNCH_DST ]")
}

/** Whether the injection monitor is currently alive. */
suspend fun isZygiskRunning(): Boolean = withContext(Dispatchers.IO) {
    ShellUtils.fastCmdResult(getRootShell(), "pgrep -f zygisk-ptrace >/dev/null 2>&1")
}

/** Deploy the universal payload, apply sepolicy, install the boot hook. Needs a reboot to take effect. */
suspend fun enableZygisk(): Boolean = withContext(Dispatchers.IO) {
    val payload = copyAssetToCache("zygisk/payload.zip")
    val setup = copyAssetToCache("zygisk/setup.sh")
    val launch = copyAssetToCache("zygisk/launch.sh")
    val shell = getRootShell()
    val script = """
        set -e
        rm -rf $ZYGISK_DIR
        mkdir -p $ZYGISK_DIR/payload /data/adb/post-fs-data.d
        cp '${payload.absolutePath}' $ZYGISK_DIR/payload.zip
        cp '${setup.absolutePath}' $ZYGISK_DIR/setup.sh
        cp '${launch.absolutePath}' $ZYGISK_DIR/launch.sh
        cd $ZYGISK_DIR/payload && unzip -o $ZYGISK_DIR/payload.zip >/dev/null
        cd $ZYGISK_DIR && sh setup.sh $ZYGISK_DIR
        ${getKsuDaemonPath()} sepolicy apply $ZYGISK_DIR/payload/sepolicy.rule || true
        touch $ZYGISK_DIR/enable
        cp $ZYGISK_DIR/launch.sh $ZYGISK_LAUNCH_DST
        chmod 0755 $ZYGISK_LAUNCH_DST
    """.trimIndent()
    val ok = shell.newJob().add(script).exec().isSuccess
    payload.delete(); setup.delete(); launch.delete()
    ok
}

/** Remove the boot hook + stop the engine (kill switch). Leaves nothing running. */
suspend fun disableZygisk(): Boolean = withContext(Dispatchers.IO) {
    ShellUtils.fastCmdResult(
        getRootShell(),
        "rm -f $ZYGISK_DIR/enable $ZYGISK_LAUNCH_DST; " +
            "pkill -f zygisk-ptrace 2>/dev/null; pkill -f zygiskd 2>/dev/null; true"
    )
}

private const val SU_NOTIFY_FLAG = "/data/adb/ksu/su_notify_enabled"
private const val MANAGER_PACKAGE_FILE = "/data/adb/ksu/manager_package"

/**
 * Enable/disable root-request notifications. Driven entirely by Sentinel via the
 * native su-notifyd daemon (NO SU Log). On enable we publish this manager's
 * (randomized) package + the enable flag, then start the daemon; on disable we
 * clear the flag (the daemon exits) and stop it.
 */
suspend fun setSuNotify(enable: Boolean): Boolean = withContext(Dispatchers.IO) {
    val shell = getRootShell()
    if (enable) {
        // Restart cleanly: kill any old daemon (so a freshly-deployed ksud binary
        // takes over and the flock is free), then start the current one.
        ShellUtils.fastCmdResult(
            shell,
            "echo ${ksuApp.packageName} > $MANAGER_PACKAGE_FILE; " +
                "touch $SU_NOTIFY_FLAG; " +
                "pkill -f 'ksud su-notifyd' 2>/dev/null; sleep 1; " +
                "/data/adb/ksu/bin/ksud debug su-notifyd"
        )
    } else {
        ShellUtils.fastCmdResult(
            shell,
            "rm -f $SU_NOTIFY_FLAG; pkill -f 'ksud su-notifyd' 2>/dev/null; true"
        )
    }
}

fun install() {
    val start = SystemClock.elapsedRealtime()
    val libadbroot = File(ksuApp.applicationInfo.nativeLibraryDir, "libadbroot.so").absolutePath
    val result = execKsud("install --libadbroot $libadbroot", true)
    Log.w(TAG, "install result: $result, cost: ${SystemClock.elapsedRealtime() - start}ms")
}

fun hasMetaModule(): Boolean {
    return getMetaModuleImplement() != "None"
}

fun listModules(): String {
    val shell = getRootShell()

    val out = shell.newJob()
        .add("${getKsuDaemonPath()} module list").to(ArrayList(), null).exec().out
    return out.joinToString("\n").ifBlank { "[]" }
}

fun getModuleCount(): Int {
    val result = listModules()
    runCatching {
        val array = JSONArray(result)
        return array.length()
    }.getOrElse { return 0 }
}

fun getSuperuserCount(): Int {
    return Natives.getSuperuserCount()
}

fun toggleModule(id: String, enable: Boolean): Boolean {
    val cmd = if (enable) {
        "module enable $id"
    } else {
        "module disable $id"
    }
    val result = execKsud(cmd, true)
    Log.i(TAG, "$cmd result: $result")
    return result
}

fun uninstallModule(id: String): Boolean {
    val cmd = "module uninstall $id"
    val result = execKsud(cmd, true)
    Log.i(TAG, "uninstall module $id result: $result")
    return result
}

fun undoUninstallModule(id: String): Boolean {
    val cmd = "module undo-uninstall $id"
    val result = execKsud(cmd, true)
    Log.i(TAG, "undo uninstall module $id result: $result")
    return result
}

private fun flashWithIO(
    cmd: String,
    onStdout: (String) -> Unit,
    onStderr: (String) -> Unit
): Shell.Result {

    val stdoutCallback: CallbackList<String?> = object : CallbackList<String?>() {
        override fun onAddElement(s: String?) {
            onStdout(s ?: "")
        }
    }

    val stderrCallback: CallbackList<String?> = object : CallbackList<String?>() {
        override fun onAddElement(s: String?) {
            onStderr(s ?: "")
        }
    }

    return withNewRootShell {
        newJob().add(cmd).to(stdoutCallback, stderrCallback).exec()
    }
}

// Zygisk is built-in; reject other Zygisk *implementations* (they conflict).
// NOTE: only implementations are blocked - Zygisk *modules* (e.g. LSPosed,
// id zygisk_lsposed) are NOT in this set and install normally.
private val BLOCKED_ZYGISK_IMPL_IDS = setOf(
    "zygisksu", "rezygisk", "shirokozygisk", "zygisk_next", "zygisknext", "neozygisk",
)

private fun zipModuleId(file: File): String? = runCatching {
    java.util.zip.ZipFile(file).use { zf ->
        val entry = zf.getEntry("module.prop") ?: return null
        Properties().apply { load(zf.getInputStream(entry)) }.getProperty("id")
    }
}.getOrNull()

fun flashModule(
    uri: Uri,
    onFinish: (Boolean, Int) -> Unit,
    onStdout: (String) -> Unit,
    onStderr: (String) -> Unit
): Boolean {
    val resolver = ksuApp.contentResolver
    with(resolver.openInputStream(uri)) {
        val file = File(ksuApp.cacheDir, "module.zip")
        file.outputStream().use { output ->
            this?.copyTo(output)
        }
        val modId = zipModuleId(file)?.lowercase()
        if (modId != null && modId in BLOCKED_ZYGISK_IMPL_IDS) {
            onStderr(
                "Blocked: \"$modId\" is a Zygisk implementation. ReSukiSU Ultima has " +
                    "Zygisk built-in - installing another would conflict. " +
                    "(Zygisk modules like LSPosed still work.)"
            )
            file.delete()
            onFinish(false, 1)
            return false
        }
        val cmd = "module install ${file.absolutePath}"
        val result = flashWithIO("${getKsuDaemonPath()} $cmd", onStdout, onStderr)
        Log.i("KernelSU", "install module $uri result: $result")

        file.delete()

        onFinish(result.isSuccess, result.code)
        return result.isSuccess
    }
}

fun runModuleAction(
    moduleId: String, onStdout: (String) -> Unit, onStderr: (String) -> Unit
): Boolean {
    val stdoutCallback: CallbackList<String?> = object : CallbackList<String?>() {
        override fun onAddElement(s: String?) {
            onStdout(s ?: "")
        }
    }

    val stderrCallback: CallbackList<String?> = object : CallbackList<String?>() {
        override fun onAddElement(s: String?) {
            onStderr(s ?: "")
        }
    }

    val result = withNewRootShell(true) {
        newJob().add("${getKsuDaemonPath()} module action $moduleId")
            .to(stdoutCallback, stderrCallback).exec()
    }

    Log.i("KernelSU", "Module runAction result: $result")

    return result.isSuccess
}

fun restoreBoot(
    onFinish: (Boolean, Int) -> Unit, onStdout: (String) -> Unit, onStderr: (String) -> Unit
): Boolean {
    val magiskboot = File(ksuApp.applicationInfo.nativeLibraryDir, "libmagiskboot.so")
    val result = flashWithIO(
        "${getKsuDaemonPath()} boot-restore -f",
        onStdout,
        onStderr
    )
    onFinish(result.isSuccess, result.code)
    return result.isSuccess
}

fun uninstallPermanently(
    onFinish: (Boolean, Int) -> Unit, onStdout: (String) -> Unit, onStderr: (String) -> Unit
): Boolean {
    val result =
        flashWithIO("${getKsuDaemonPath()} uninstall --package-name ${BuildConfig.APPLICATION_ID}", onStdout, onStderr)
    onFinish(result.isSuccess, result.code)
    return result.isSuccess
}

@Parcelize
sealed class LkmSelection : Parcelable {
    data class LkmUri(val uri: Uri) : LkmSelection()
    data class KmiString(val value: String) : LkmSelection()
    data object KmiNone : LkmSelection()
}

fun installBoot(
    bootUri: Uri?,
    lkm: LkmSelection,
    ota: Boolean,
    partition: String?,
    onFinish: (Boolean, Int) -> Unit,
    onStdout: (String) -> Unit,
    onStderr: (String) -> Unit,
): Boolean {
    val resolver = ksuApp.contentResolver

    val bootFile = bootUri?.let { uri ->
        with(resolver.openInputStream(uri)) {
            val bootFile = File(ksuApp.cacheDir, "boot.img")
            bootFile.outputStream().use { output ->
                this?.copyTo(output)
            }

            bootFile
        }
    }

    var cmd = "boot-patch"

    cmd += if (bootFile == null) {
        // no boot.img, use -f to flash
        " -f"
    } else {
        " -b ${bootFile.absolutePath}"
    }

    if (ota) {
        cmd += " -u"
    }

    var lkmFile: File? = null
    when (lkm) {
        is LkmSelection.LkmUri -> {
            lkmFile = with(resolver.openInputStream(lkm.uri)) {
                val file = File(ksuApp.cacheDir, "kernelsu-tmp-lkm.ko")
                file.outputStream().use { output ->
                    this?.copyTo(output)
                }

                file
            }
            cmd += " -m ${lkmFile.absolutePath}"
        }

        is LkmSelection.KmiString -> {
            cmd += " --kmi ${lkm.value}"
        }

        LkmSelection.KmiNone -> {
            // do nothing
        }
    }

    // output dir
    if (bootFile != null) {
        val downloadsDir =
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        cmd += " -o $downloadsDir"
    }

    partition?.let { part ->
        cmd += " --partition $part"
    }

    val result = flashWithIO("${getKsuDaemonPath()} $cmd", onStdout, onStderr)
    Log.i("KernelSU", "install boot result: ${result.isSuccess}")

    bootFile?.delete()
    lkmFile?.delete()

    // if boot uri is empty, it is direct install, when success, we should show reboot button
    onFinish(bootUri == null && result.isSuccess, result.code)

    if (bootUri == null && result.isSuccess) {
        install()
    }

    return result.isSuccess
}

fun reboot(reason: String = "") {
    if (reason == "soft_reboot") {
        execKsud("soft-reboot", true)
        return
    }
    val shell = getRootShell()
    if (reason == "recovery") {
        // KEYCODE_POWER = 26, hide incorrect "Factory data reset" message
        ShellUtils.fastCmd(shell, "/system/bin/input keyevent 26")
    }
    ShellUtils.fastCmd(shell, "/system/bin/svc power reboot $reason || /system/bin/reboot $reason")
}

fun rootAvailable(): Boolean {
    val shell = getRootShell()
    return shell.isRoot
}


suspend fun getCurrentKmi(): String = withContext(Dispatchers.IO) {
    val shell = getRootShell()
    val cmd = "boot-info current-kmi"
    ShellUtils.fastCmd(shell, "${getKsuDaemonPath()} $cmd")
}

suspend fun getSupportedKmis(): List<String> = withContext(Dispatchers.IO) {
    val shell = getRootShell()
    val cmd = "boot-info supported-kmis"
    val out = shell.newJob().add("${getKsuDaemonPath()} $cmd").to(ArrayList(), null).exec().out
    out.filter { it.isNotBlank() }.map { it.trim() }
}

suspend fun isAbDevice(): Boolean = withContext(Dispatchers.IO) {
    val shell = getRootShell()
    val cmd = "boot-info is-ab-device"
    ShellUtils.fastCmd(shell, "${getKsuDaemonPath()} $cmd").trim().toBoolean()
}

suspend fun getDefaultPartition(): String = withContext(Dispatchers.IO) {
    val shell = getRootShell()
    if (shell.isRoot) {
        val cmd = "boot-info default-partition"
        ShellUtils.fastCmd(shell, "${getKsuDaemonPath()} $cmd").trim()
    } else {
        if (!Os.uname().release.contains("android12-")) "init_boot" else "boot"
    }
}

suspend fun getSlotSuffix(ota: Boolean): String = withContext(Dispatchers.IO) {
    val shell = getRootShell()
    val cmd = if (ota) {
        "boot-info slot-suffix --ota"
    } else {
        "boot-info slot-suffix"
    }
    ShellUtils.fastCmd(shell, "${getKsuDaemonPath()} $cmd").trim()
}

suspend fun getAvailablePartitions(): List<String> = withContext(Dispatchers.IO) {
    val shell = getRootShell()
    val cmd = "boot-info available-partitions"
    val out = shell.newJob().add("${getKsuDaemonPath()} $cmd").to(ArrayList(), null).exec().out
    out.filter { it.isNotBlank() }.map { it.trim() }
}

fun hasMagisk(): Boolean {
    val shell = getRootShell(true)
    val result = shell.newJob().add("which magisk").exec()
    Log.i(TAG, "has magisk: ${result.isSuccess}")
    return result.isSuccess
}

fun isSepolicyValid(rules: String?): Boolean {
    if (rules == null) {
        return true
    }
    val shell = getRootShell()
    val result =
        shell.newJob().add("${getKsuDaemonPath()} sepolicy check '$rules'").to(ArrayList(), null)
            .exec()
    return result.isSuccess
}

fun getSepolicy(pkg: String): String {
    val shell = getRootShell()
    val result =
        shell.newJob().add("${getKsuDaemonPath()} profile get-sepolicy $pkg").to(ArrayList(), null)
            .exec()
    Log.i(TAG, "code: ${result.code}, out: ${result.out}, err: ${result.err}")
    return result.out.joinToString("\n")
}

fun setSepolicy(pkg: String, rules: String): Boolean {
    val shell = getRootShell()
    val result = shell.newJob().add("${getKsuDaemonPath()} profile set-sepolicy $pkg '$rules'")
        .to(ArrayList(), null).exec()
    Log.i(TAG, "set sepolicy result: ${result.code}")
    return result.isSuccess
}

fun listAppProfileTemplates(): List<String> {
    val shell = getRootShell()
    return shell.newJob().add("${getKsuDaemonPath()} profile list-templates").to(ArrayList(), null)
        .exec().out
}

fun getAppProfileTemplate(id: String): String {
    val shell = getRootShell()
    return shell.newJob().add("${getKsuDaemonPath()} profile get-template '${id}'")
        .to(ArrayList(), null).exec().out.joinToString("\n")
}

fun setAppProfileTemplate(id: String, template: String): Boolean {
    val shell = getRootShell()
    val escapedTemplate = template.replace("\"", "\\\"")
    val cmd = """${getKsuDaemonPath()} profile set-template "$id" "$escapedTemplate'""""
    return shell.newJob().add(cmd)
        .to(ArrayList(), null).exec().isSuccess
}

fun deleteAppProfileTemplate(id: String): Boolean {
    val shell = getRootShell()
    return shell.newJob().add("${getKsuDaemonPath()} profile delete-template '${id}'")
        .to(ArrayList(), null).exec().isSuccess
}
// KPM控制
fun loadKpmModule(path: String, args: String? = null): String {
    val shell = getRootShell()
    val cmd = "${getKsuDaemonPath()} kpm load $path ${args ?: ""}"
    return ShellUtils.fastCmd(shell, cmd)
}

fun unloadKpmModule(name: String): String {
    val shell = getRootShell()
    val cmd = "${getKsuDaemonPath()} kpm unload $name"
    return ShellUtils.fastCmd(shell, cmd)
}

fun getKpmModuleCount(): Int {
    val shell = getRootShell()
    val cmd = "${getKsuDaemonPath()} kpm num"
    val result = ShellUtils.fastCmd(shell, cmd)
    return result.trim().toIntOrNull() ?: 0
}

fun runCmd(shell: Shell, cmd: String): String {
    return shell.newJob()
        .add(cmd)
        .to(mutableListOf<String>(), null)
        .exec().out
        .joinToString("\n")
}

fun listKpmModules(): String {
    val shell = getRootShell()
    val cmd = "${getKsuDaemonPath()} kpm list"
    return try {
        runCmd(shell, cmd).trim()
    } catch (e: Exception) {
        Log.e(TAG, "Failed to list KPM modules", e)
        ""
    }
}

fun getKpmModuleInfo(name: String): String {
    val shell = getRootShell()
    val cmd = "${getKsuDaemonPath()} kpm info $name"
    return try {
        runCmd(shell, cmd).trim()
    } catch (e: Exception) {
        Log.e(TAG, "Failed to get KPM module info: $name", e)
        ""
    }
}

fun controlKpmModule(name: String, args: String? = null): Int {
    val shell = getRootShell()
    val cmd = """${getKsuDaemonPath()} kpm control $name "${args ?: ""}""""
    val result = runCmd(shell, cmd)
    return result.trim().toIntOrNull() ?: -1
}

fun getKpmVersion(): String {
    val shell = getRootShell()
    val cmd = "${getKsuDaemonPath()} kpm version"
    val result = ShellUtils.fastCmd(shell, cmd)
    return result.trim()
}

fun forceStopApp(packageName: String) {
    val shell = getRootShell()
    val result = shell.newJob().add("am force-stop $packageName").exec()
    Log.i(TAG, "force stop $packageName result: $result")
}

fun launchApp(packageName: String) {

    val shell = getRootShell()
    val result =
        shell.newJob()
            .add("cmd package resolve-activity --brief $packageName | tail -n 1 | xargs cmd activity start-activity -n")
            .exec()
    Log.i(TAG, "launch $packageName result: $result")
}

fun restartApp(packageName: String) {
    forceStopApp(packageName)
    launchApp(packageName)
}

fun getSuSFSStatus(): String {
    val shell = getRootShell()
    return ShellUtils.fastCmd(shell, "${getKsuDaemonPath()} susfs status").trim()
}

fun getSuSFSVersion(): String {
    val shell = getRootShell()
    val result = ShellUtils.fastCmd(shell, "${getKsuDaemonPath()} susfs version")
    return result
}

fun getSuSFSFeatures(): String {
    val shell = getRootShell()
    val cmd = "${getKsuDaemonPath()} susfs features"
    return runCmd(shell, cmd)
}

fun getMetaModuleImplement(): String {
    try {
        val metaModuleProp = SuFile.open("/data/adb/metamodule/module.prop")
        if (!metaModuleProp.isFile) {
            Log.i(TAG, "Meta module implement: None")
            return "None"
        }

        val prop = Properties()
        prop.load(metaModuleProp.newInputStream())

        val name = prop.getProperty("name")
        Log.i(TAG, "Meta module implement: $name")
        return name
    } catch (_ : Throwable) {
        Log.i(TAG, "Meta module implement: None")
        return "None"
    }
}

fun getZygiskImplement(): String {
    // Built-in Zygisk (Zygisk-Ultima): active when deployed + the boot hook is in
    // place. Shown as "Active" on the home screen; hidden entirely when off.
    if (SuFile.open("$ZYGISK_DIR/enable").isFile &&
        SuFile.open(ZYGISK_LAUNCH_DST).isFile
    ) {
        return "Active"
    }

    val zygiskModuleIds = listOf(
        "zygisksu",
        "rezygisk",
        "shirokozygisk"
    )

    for (moduleId in zygiskModuleIds) {
        // 忽略禁用/即将删除
        if (SuFile.open("/data/adb/modules/$moduleId/disable").isFile || SuFile.open("/data/adb/modules/$moduleId/remove").isFile) continue

        // 读取prop
        val propFile = SuFile.open("/data/adb/modules/$moduleId/module.prop")
        if (!propFile.isFile) continue

        val prop = Properties()
        prop.load(propFile.newInputStream())

        val name = prop.getProperty("name")
        Log.i(TAG, "Zygisk implement: $name")
        return name
    }

    Log.i(TAG, "Zygisk implement: None")
    return "None"
}

fun addKernelUmountPath(path: String, flags: Int): Boolean {
    val shell = getRootShell()
    val flagsArg = if (flags >= 0) "--flags $flags" else ""
    val cmd = "${getKsuDaemonPath()} kernel umount add $path $flagsArg"
    val result = ShellUtils.fastCmdResult(shell, cmd)
    Log.i(TAG, "add umount path $path result: $result")
    return result
}

fun removeKernelUmountPath(path: String): Boolean {
    val shell = getRootShell()
    val cmd = "${getKsuDaemonPath()} kernel umount del $path"
    val result = ShellUtils.fastCmdResult(shell, cmd)
    Log.i(TAG, "remove umount path $path result: $result")
    return result
}

fun listKernelUmountPaths(): String {
    val shell = getRootShell()
    val cmd = "${getKsuDaemonPath()} kernel umount list"
    return try {
        runCmd(shell, cmd).trim()
    } catch (e: Exception) {
        Log.e(TAG, "Failed to list umount paths", e)
        ""
    }
}

fun addUmountConfigUmountPath(path: String, flags: Int): Boolean {
    val shell = getRootShell()
    val flagsArg = if (flags >= 0) "--flags $flags" else ""
    val cmd = "${getKsuDaemonPath()} umount-config add $path $flagsArg"
    val result = ShellUtils.fastCmdResult(shell, cmd)
    Log.i(TAG, "add umount path $path result: $result")
    return result
}

fun removeUmountConfigUmountPath(path: String): Boolean {
    val shell = getRootShell()
    val cmd = "${getKsuDaemonPath()} umount-config del $path"
    val result = ShellUtils.fastCmdResult(shell, cmd)
    Log.i(TAG, "remove umount path $path result: $result")
    return result
}

fun listUmountConfigUmountPaths(): String {
    val shell = getRootShell()
    val cmd = "${getKsuDaemonPath()} umount-config list"
    return try {
        runCmd(shell, cmd).trim()
    } catch (e: Exception) {
        Log.e(TAG, "Failed to list umount paths", e)
        ""
    }
}
