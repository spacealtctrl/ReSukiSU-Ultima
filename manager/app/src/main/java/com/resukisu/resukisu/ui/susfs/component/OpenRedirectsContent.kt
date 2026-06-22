package com.resukisu.resukisu.ui.susfs.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.resukisu.resukisu.R

@Composable
fun OpenRedirectsContent(
    openRedirects: Set<String>,
    isLoading: Boolean,
    onAddOpenRedirect: (String, String) -> Unit,
    onRemoveOpenRedirect: (String) -> Unit
) {
    var showAdd by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.susfs_open_redirect_desc_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = stringResource(R.string.susfs_open_redirect_desc_text),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            if (openRedirects.isEmpty()) {
                item { EmptyStateCard(message = stringResource(R.string.susfs_no_open_redirects)) }
            } else {
                item {
                    SectionHeader(
                        title = stringResource(R.string.susfs_open_redirect_section),
                        subtitle = null,
                        icon = Icons.Default.SwapHoriz,
                        count = openRedirects.size
                    )
                }
                items(openRedirects.toList()) { rule ->
                    val parts = rule.split("|", limit = 2)
                    val display = if (parts.size == 2) "${parts[0]}  →  ${parts[1]}" else rule
                    PathItemCard(
                        path = display,
                        icon = Icons.Default.SwapHoriz,
                        onDelete = { onRemoveOpenRedirect(rule) },
                        onEdit = null,
                        isLoading = isLoading
                    )
                }
            }

            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        onClick = { showAdd = true },
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp),
                        enabled = !isLoading
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.susfs_add_open_redirect))
                    }
                }
            }
        }
    }

    if (showAdd) {
        OpenRedirectAddDialog(
            onDismiss = { showAdd = false },
            onConfirm = { orig, redir ->
                onAddOpenRedirect(orig, redir)
                showAdd = false
            }
        )
    }
}

@Composable
private fun OpenRedirectAddDialog(
    onDismiss: () -> Unit,
    onConfirm: (String, String) -> Unit
) {
    var original by remember { mutableStateOf("") }
    var redirected by remember { mutableStateOf("") }
    val valid = original.isNotBlank() && redirected.isNotBlank()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.susfs_add_open_redirect)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = original,
                    onValueChange = { original = it },
                    label = { Text(stringResource(R.string.susfs_open_redirect_original)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = redirected,
                    onValueChange = { redirected = it },
                    label = { Text(stringResource(R.string.susfs_open_redirect_redirected)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (valid) onConfirm(original.trim(), redirected.trim()) },
                enabled = valid
            ) { Text(stringResource(android.R.string.ok)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
        }
    )
}
