package com.resukisu.resukisu.ui.screen

import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.resukisu.resukisu.ui.component.settings.AppBackButton
import com.resukisu.resukisu.ui.navigation.LocalNavigator
import com.resukisu.resukisu.ui.navigation.Route
import com.resukisu.resukisu.ui.util.forceStopApp
import com.resukisu.resukisu.ui.util.getAppOpsModes
import com.resukisu.resukisu.ui.util.getSentinelCloaked
import com.resukisu.resukisu.ui.util.getSentinelHistory
import com.resukisu.resukisu.ui.util.opForPermission
import com.resukisu.resukisu.ui.util.sentinelCloak
import com.resukisu.resukisu.ui.util.setAppEnabled
import com.resukisu.resukisu.ui.util.setPermissionMode
import com.resukisu.resukisu.ui.util.uncloakRestore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class PermRow(
    val perm: String,
    val name: String,
    val dangerous: Boolean,
    val granted: Boolean,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SentinelAppDetailScreen(uid: Int) {
    val navigator = LocalNavigator.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pm = context.packageManager

    val pkg = remember(uid) { pm.getPackagesForUid(uid)?.firstOrNull() }
    val label = remember(uid) {
        pkg?.let {
            runCatching { pm.getApplicationLabel(pm.getApplicationInfo(it, 0)).toString() }.getOrNull()
        } ?: (pm.getNameForUid(uid) ?: "uid $uid")
    }

    var kinds by remember { mutableIntStateOf(0) }
    var count by remember { mutableIntStateOf(0) }
    var isCloaked by remember { mutableStateOf(false) }
    var enabled by remember { mutableStateOf(true) }
    var perms by remember { mutableStateOf<List<PermRow>>(emptyList()) }
    var showAll by remember { mutableStateOf(false) }

    suspend fun refresh() {
        val hist = getSentinelHistory().firstOrNull { it.uid == uid }
        kinds = hist?.kinds ?: 0
        count = hist?.count ?: 0
        isCloaked = getSentinelCloaked().contains(uid)
        if (pkg != null) {
            runCatching {
                enabled = pm.getApplicationInfo(pkg, 0).enabled
                val pi = pm.getPackageInfo(pkg, PackageManager.GET_PERMISSIONS)
                val req = pi.requestedPermissions ?: emptyArray()
                val flags = pi.requestedPermissionsFlags ?: IntArray(req.size)
                val ops = getAppOpsModes(pkg)
                perms = req.mapIndexed { i, perm ->
                    val dangerous = runCatching {
                        (pm.getPermissionInfo(perm, 0).protectionLevel and
                            PermissionInfo.PROTECTION_DANGEROUS) != 0
                    }.getOrDefault(false)
                    val pmGranted = (flags.getOrElse(i) { 0 } and
                        PackageInfo.REQUESTED_PERMISSION_GRANTED) != 0
                    // Treat an appop set to ignore/deny as blocked too, so the
                    // state reflects blocks applied to non-runtime perms.
                    val opMode = opForPermission(perm)?.let { ops[it] }
                    val granted = pmGranted && opMode != "ignore" && opMode != "deny"
                    PermRow(perm, perm.substringAfterLast('.'), dangerous, granted)
                }.sortedWith(compareByDescending<PermRow> { it.dangerous }.thenBy { it.name })
            }
        }
    }

    fun applyMode(p: PermRow, block: Boolean) {
        if (pkg != null) {
            scope.launch {
                withContext(Dispatchers.IO) { setPermissionMode(pkg, p.perm, block) }
                refresh()
            }
        }
    }

    LaunchedEffect(uid) { refresh() }

    val dangerousPerms = perms.filter { it.dangerous }
    val otherPerms = perms.filter { !it.dangerous }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(label) },
                navigationIcon = { AppBackButton(onClick = { navigator.pop() }) },
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            item {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(label, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(
                        pkg ?: "uid $uid",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            item { SectionHeader("Probing for") }
            item {
                ListItem(
                    headlineContent = { Text(if (kinds == 0) "Nothing recorded" else kindsLabel(kinds)) },
                    supportingContent = { Text("$count probe(s) this boot") },
                )
            }

            item { SectionHeader("Permissions") }
            item {
                ListItem(headlineContent = {
                    Text("${dangerousPerms.size} dangerous of ${perms.size} requested")
                })
            }
            items(dangerousPerms, key = { it.perm }) { p ->
                PermRow(p) { block -> applyMode(p, block) }
            }
            if (otherPerms.isNotEmpty()) {
                item {
                    TextButton(onClick = { showAll = !showAll }) {
                        Text(
                            if (showAll) "Hide other permissions"
                            else "Show all ${otherPerms.size} other permissions"
                        )
                    }
                }
                if (showAll) {
                    // White (non-dangerous) perms are info-only - no Manage button.
                    items(otherPerms, key = { it.perm }) { p ->
                        PermRow(p, onMode = null)
                    }
                }
            }

            item { SectionHeader("Actions") }
            item {
                ListItem(
                    modifier = Modifier.clickable { navigator.push(Route.SentinelSpy(uid)) },
                    leadingContent = { Icon(Icons.Filled.Visibility, contentDescription = null) },
                    headlineContent = { Text("Spy - live activity log") },
                    supportingContent = { Text("Everything it logs: Play Store, attestation, failures") },
                )
            }
            item {
                ListItem(
                    leadingContent = { Icon(Icons.Filled.VisibilityOff, contentDescription = null) },
                    headlineContent = { Text("Cloak (hide root from this app)") },
                    supportingContent = { Text("Uncloaking restores its permissions to default") },
                    trailingContent = {
                        Switch(checked = isCloaked, onCheckedChange = { on ->
                            scope.launch {
                                if (on) {
                                    withContext(Dispatchers.IO) { sentinelCloak(uid) }
                                    refresh()
                                } else {
                                    // Reset to default and leave: an uncloaked app
                                    // can't be managed here, so return to the list.
                                    uncloakRestore(uid)
                                    navigator.pop()
                                }
                            }
                        })
                    },
                )
            }
            if (pkg != null) {
                item {
                    ListItem(
                        leadingContent = { Icon(Icons.Filled.Stop, contentDescription = null) },
                        headlineContent = { Text("Force-stop") },
                        trailingContent = {
                            OutlinedButton(onClick = {
                                scope.launch { withContext(Dispatchers.IO) { forceStopApp(pkg) } }
                            }) { Text("Stop") }
                        },
                    )
                }
                item {
                    ListItem(
                        leadingContent = {
                            Icon(
                                if (enabled) Icons.Filled.Block else Icons.Filled.PlayArrow,
                                contentDescription = null,
                            )
                        },
                        headlineContent = { Text(if (enabled) "Disable (freeze) app" else "Enable app") },
                        trailingContent = {
                            OutlinedButton(onClick = {
                                scope.launch {
                                    withContext(Dispatchers.IO) { setAppEnabled(pkg, !enabled) }
                                    refresh()
                                }
                            }) { Text(if (enabled) "Disable" else "Enable") }
                        },
                    )
                }
            }
            item { HorizontalDivider() }
        }
    }
}

@Composable
private fun PermRow(p: PermRow, onMode: ((Boolean) -> Unit)?) {
    var menu by remember { mutableStateOf(false) }
    ListItem(
        headlineContent = {
            Text(
                p.name,
                color = if (p.dangerous) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurface,
            )
        },
        supportingContent = { Text(if (p.granted) "granted" else "denied") },
        trailingContent = if (onMode == null) null else {
            {
                Box {
                    OutlinedButton(onClick = { menu = true }) { Text("Manage") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Allow") }, onClick = { menu = false; onMode(false) })
                        DropdownMenuItem(text = { Text("Block") }, onClick = { menu = false; onMode(true) })
                    }
                }
            }
        },
    )
}
