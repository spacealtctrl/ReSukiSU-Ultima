package com.resukisu.resukisu.ui.screen

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
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
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.resukisu.resukisu.R
import com.resukisu.resukisu.ui.component.settings.AppBackButton
import com.resukisu.resukisu.ui.navigation.LocalNavigator
import com.resukisu.resukisu.ui.util.SentinelHistEntry
import com.resukisu.resukisu.ui.util.getSentinelCloaked
import com.resukisu.resukisu.ui.util.getSentinelHistory
import com.resukisu.resukisu.ui.util.getSentinelStatus
import com.resukisu.resukisu.ui.util.sentinelCloak
import com.resukisu.resukisu.ui.util.sentinelUncloak
import com.resukisu.resukisu.ui.util.setSentinel
import com.resukisu.resukisu.ui.util.setSentinelAuto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SentinelScreen() {
    val navigator = LocalNavigator.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior(rememberTopAppBarState())

    var enabled by remember { mutableStateOf(false) }
    var auto by remember { mutableStateOf(false) }
    var probes by remember { mutableStateOf<List<SentinelHistEntry>>(emptyList()) }
    var cloaked by remember { mutableStateOf<List<Int>>(emptyList()) }

    fun label(uid: Int): String {
        val pm = context.packageManager
        // Prefer the loadable app label of any package sharing this uid.
        pm.getPackagesForUid(uid)?.forEach { pkg ->
            runCatching {
                return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
            }
        }
        // Fall back to the first package name, then the kernel uid name.
        pm.getPackagesForUid(uid)?.firstOrNull()?.let { return it }
        return pm.getNameForUid(uid) ?: "uid $uid"
    }

    // initial load, then poll the probe feed while enabled
    LaunchedEffect(Unit) {
        val (en, au) = getSentinelStatus()
        enabled = en
        auto = au
        cloaked = getSentinelCloaked()
        // Recent probes come from the kernel's persistent history (survives until
        // reboot), so they're not lost when the screen or app is closed.
        while (true) {
            probes = getSentinelHistory().sortedByDescending { it.count }
            cloaked = getSentinelCloaked()
            delay(3000)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.sentinel_title)) },
                navigationIcon = { AppBackButton(onClick = { navigator.pop() }) },
                scrollBehavior = scrollBehavior,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.sentinel_enable)) },
                    supportingContent = { Text(stringResource(R.string.sentinel_summary)) },
                    trailingContent = {
                        Switch(checked = enabled, onCheckedChange = { on ->
                            enabled = on
                            scope.launch { withContext(Dispatchers.IO) { setSentinel(on) } }
                        })
                    },
                )
            }
            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.sentinel_auto)) },
                    supportingContent = { Text(stringResource(R.string.sentinel_auto_summary)) },
                    trailingContent = {
                        Switch(checked = auto, enabled = enabled, onCheckedChange = { on ->
                            auto = on
                            scope.launch { withContext(Dispatchers.IO) { setSentinelAuto(on) } }
                        })
                    },
                )
            }

            item { SectionHeader(stringResource(R.string.sentinel_recent_probes)) }
            if (probes.isEmpty()) {
                item { EmptyHint(stringResource(R.string.sentinel_no_probes)) }
            } else {
                items(probes) { p ->
                    val isCloaked = cloaked.contains(p.uid)
                    ListItem(
                        headlineContent = { Text(label(p.uid)) },
                        supportingContent = {
                            Text("${kindsLabel(p.kinds)}  ·  ×${p.count}")
                        },
                        trailingContent = {
                            if (isCloaked) {
                                Text(
                                    stringResource(R.string.sentinel_cloaked_label),
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            } else {
                                OutlinedButton(onClick = {
                                    scope.launch {
                                        withContext(Dispatchers.IO) { sentinelCloak(p.uid) }
                                        cloaked = getSentinelCloaked()
                                    }
                                }) { Text(stringResource(R.string.sentinel_cloak)) }
                            }
                        },
                    )
                }
            }

            item { SectionHeader(stringResource(R.string.sentinel_cloaked_apps)) }
            if (cloaked.isEmpty()) {
                item { EmptyHint(stringResource(R.string.sentinel_no_cloaked)) }
            } else {
                items(cloaked) { uid ->
                    ListItem(
                        leadingContent = { Icon(Icons.Filled.VisibilityOff, contentDescription = null) },
                        headlineContent = { Text(label(uid)) },
                        supportingContent = { Text("uid $uid") },
                        trailingContent = {
                            OutlinedButton(onClick = {
                                scope.launch {
                                    withContext(Dispatchers.IO) { sentinelUncloak(uid) }
                                    cloaked = getSentinelCloaked()
                                }
                            }) { Text(stringResource(R.string.sentinel_uncloak)) }
                        },
                    )
                }
            }
        }
    }
}

/* Decode the probe-kinds bitmap (bit n = enum kind n+1) into a readable list. */
private fun kindsLabel(kinds: Int): String {
    val names = buildList {
        if (kinds and 0x01 != 0) add("su")
        if (kinds and 0x02 != 0) add("magisk")
        if (kinds and 0x04 != 0) add("ksu")
        if (kinds and 0x08 != 0) add("modules")
        if (kinds and 0x10 != 0) add("app list")
        if (kinds and 0x20 != 0) add("busybox")
    }
    return names.joinToString(", ").ifEmpty { "root" }
}

@Composable
private fun SectionHeader(text: String) {
    HorizontalDivider()
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
private fun EmptyHint(text: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(24.dp),
        contentAlignment = androidx.compose.ui.Alignment.Center,
    ) {
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
