package com.resukisu.resukisu.ui.screen

import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import com.resukisu.resukisu.ui.util.forceStopApp
import com.resukisu.resukisu.ui.util.getSentinelCloaked
import com.resukisu.resukisu.ui.util.getSentinelHistory
import com.resukisu.resukisu.ui.util.sentinelCloak
import com.resukisu.resukisu.ui.util.sentinelUncloak
import com.resukisu.resukisu.ui.util.setAppEnabled
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    var dangerous by remember { mutableStateOf<List<String>>(emptyList()) }
    var totalPerms by remember { mutableIntStateOf(0) }

    suspend fun refresh() {
        val hist = getSentinelHistory().firstOrNull { it.uid == uid }
        kinds = hist?.kinds ?: 0
        count = hist?.count ?: 0
        isCloaked = getSentinelCloaked().contains(uid)
        if (pkg != null) {
            runCatching {
                enabled = pm.getApplicationInfo(pkg, 0).enabled
                val perms = pm.getPackageInfo(pkg, PackageManager.GET_PERMISSIONS).requestedPermissions
                    ?: emptyArray()
                totalPerms = perms.size
                dangerous = perms.filter { perm ->
                    runCatching {
                        (pm.getPermissionInfo(perm, 0).protectionLevel and
                            PermissionInfo.PROTECTION_DANGEROUS) != 0
                    }.getOrDefault(false)
                }.map { it.substringAfterLast('.') }.sorted()
            }
        }
    }

    LaunchedEffect(uid) { refresh() }

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
                    Text(pkg ?: "uid $uid", style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                ListItem(
                    headlineContent = { Text("${dangerous.size} dangerous of $totalPerms requested") },
                )
            }
            items(dangerous) { p ->
                ListItem(
                    headlineContent = { Text(p) },
                    colors = androidx.compose.material3.ListItemDefaults.colors(
                        headlineColor = MaterialTheme.colorScheme.error,
                    ),
                )
            }

            item { SectionHeader("Actions") }
            item {
                ListItem(
                    leadingContent = { Icon(Icons.Filled.VisibilityOff, contentDescription = null) },
                    headlineContent = { Text("Cloak (hide root from this app)") },
                    trailingContent = {
                        Switch(checked = isCloaked, onCheckedChange = { on ->
                            scope.launch {
                                withContext(Dispatchers.IO) {
                                    if (on) sentinelCloak(uid) else sentinelUncloak(uid)
                                }
                                refresh()
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
