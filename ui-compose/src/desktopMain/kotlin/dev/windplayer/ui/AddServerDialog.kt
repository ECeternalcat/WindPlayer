package dev.windplayer.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.windplayer.vfs.ServerConfig
import dev.windplayer.vfs.VfsProtocol

@Composable
fun AddServerDialog(
    onDismiss: () -> Unit,
    onSave: (ServerConfig) -> Unit,
    initialConfig: ServerConfig? = null,
    modifier: Modifier = Modifier
) {
    var name by remember { mutableStateOf(initialConfig?.name ?: "") }
    var protocol by remember { mutableStateOf(initialConfig?.protocol ?: VfsProtocol.SFTP) }
    var host by remember { mutableStateOf(initialConfig?.host ?: "") }
    var port by remember { mutableStateOf(initialConfig?.port?.toString() ?: "") }
    var username by remember { mutableStateOf(initialConfig?.username ?: "") }
    var password by remember { mutableStateOf(initialConfig?.password ?: "") }
    var basePath by remember { mutableStateOf(initialConfig?.basePath ?: "/") }
    // H18: default FTP to FTPS for new servers; preserve stored value when editing.
    var useTls by remember { mutableStateOf(initialConfig?.useTls ?: (initialConfig?.protocol == VfsProtocol.FTP)) }
    var portError by remember { mutableStateOf(false) }
    var protocolExpanded by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = if (initialConfig != null) "Edit Server" else "Add Server") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(text = "Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = TextStyle(color = Color.Unspecified)
                )

                Box {
                    OutlinedTextField(
                        value = protocol.name,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text(text = "Protocol") },
                        modifier = Modifier.fillMaxWidth().clickable { protocolExpanded = true },
                        trailingIcon = {
                            TextButton(onClick = { protocolExpanded = true }) {
                                Text(text = "v", fontSize = 10.sp, color = Color.Gray)
                            }
                        }
                    )
                    DropdownMenu(
                        expanded = protocolExpanded,
                        onDismissRequest = { protocolExpanded = false }
                    ) {
                        VfsProtocol.entries.filter { it != VfsProtocol.LOCAL }.forEach { p ->
                            DropdownMenuItem(
                                text = { Text(text = p.name) },
                                onClick = {
                                    protocol = p
                                    protocolExpanded = false
                                    if (port.isBlank()) {
                                        port = when (p) {
                                            VfsProtocol.SFTP -> "22"
                                            VfsProtocol.WEBDAV -> "80"
                                            VfsProtocol.FTP -> "21"
                                            else -> ""
                                        }
                                    }
                                }
                            )
                        }
                    }
                }

                OutlinedTextField(
                    value = host,
                    onValueChange = { host = it },
                    label = { Text(text = "Host") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = TextStyle(color = Color.Unspecified)
                )

                OutlinedTextField(
                    value = port,
                    onValueChange = {
                        port = it
                        portError = it.isNotBlank() && it.toIntOrNull() == null
                    },
                    label = { Text(text = "Port (leave empty for default)") },
                    singleLine = true,
                    isError = portError,
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = TextStyle(color = Color.Unspecified)
                )

                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text(text = "Username") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = TextStyle(color = Color.Unspecified)
                )

                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(text = "Password") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = TextStyle(color = Color.Unspecified)
                )

                OutlinedTextField(
                    value = basePath,
                    onValueChange = { basePath = it },
                    label = { Text(text = "Base Path") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = TextStyle(color = Color.Unspecified)
                )

                // H18: TLS toggle for FTP (FTPS). Default ON for new FTP servers.
                if (protocol == VfsProtocol.FTP) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Use TLS (FTPS)",
                            fontSize = 13.sp
                        )
                        Switch(
                            checked = useTls,
                            onCheckedChange = { useTls = it }
                        )
                    }
                    if (!useTls) {
                        Text(
                            text = "⚠ Plain FTP sends your password in cleartext. Anyone on your network can intercept it. Enable TLS whenever the server supports it.",
                            color = Color(0xFFFFB74D),
                            fontSize = 11.sp
                        )
                    }
                }

                // H18: warn when WebDAV is configured for cleartext HTTP.
                if (protocol == VfsProtocol.WEBDAV && host.isNotBlank() &&
                    !host.startsWith("https://", ignoreCase = true)
                ) {
                    Text(
                        text = "⚠ HTTP WebDAV sends your password in cleartext. Prefix the host with https:// to encrypt the connection.",
                        color = Color(0xFFFFB74D),
                        fontSize = 11.sp
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (name.isNotBlank() && host.isNotBlank()) {
                        onSave(ServerConfig(
                            id = initialConfig?.id ?: "",
                            name = name,
                            protocol = protocol,
                            host = host,
                            port = port.toIntOrNull() ?: 0,
                            username = username,
                            password = password,
                            basePath = basePath.ifBlank { "/" },
                            useTls = useTls
                        ))
                    }
                },
                enabled = name.isNotBlank() && host.isNotBlank() && !portError
            ) {
                Text(text = "Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = "Cancel")
            }
        }
    )
}
