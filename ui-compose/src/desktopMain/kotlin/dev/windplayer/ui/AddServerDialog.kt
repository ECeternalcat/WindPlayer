package dev.windplayer.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
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
        shape = WindRadius.Stadium,
        containerColor = WindColors.White,
        titleContentColor = WindColors.Ink,
        textContentColor = WindColors.Slate,
        title = { Text(text = if (initialConfig != null) I18n.get("edit_server") else I18n.get("add_server")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                DialogTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = I18n.get("name")
                )

                Box {
                    DialogTextField(
                        value = protocol.name,
                        onValueChange = {},
                        readOnly = true,
                        label = I18n.get("protocol"),
                        modifier = Modifier.fillMaxWidth().clickable { protocolExpanded = true },
                        trailingIcon = {
                            IconButton(onClick = { protocolExpanded = true }, modifier = Modifier.size(24.dp)) {
                                Icon(
                                    painter = iconPainter(PhosphorIcons.CARET_DOWN),
                                    contentDescription = "Select protocol",
                                    tint = WindColors.Slate,
                                    modifier = Modifier.size(16.dp)
                                )
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

                DialogTextField(
                    value = host,
                    onValueChange = { host = it },
                    label = I18n.get("host")
                )

                DialogTextField(
                    value = port,
                    onValueChange = {
                        port = it
                        portError = it.isNotBlank() && it.toIntOrNull() == null
                    },
                    label = I18n.get("port_hint"),
                    isError = portError
                )

                DialogTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = I18n.get("username")
                )

                DialogTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = I18n.get("password")
                )

                DialogTextField(
                    value = basePath,
                    onValueChange = { basePath = it },
                    label = I18n.get("base_path")
                )

                // H18: TLS toggle for FTP (FTPS). Default ON for new FTP servers.
                if (protocol == VfsProtocol.FTP) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = I18n.get("use_tls"),
                            color = WindColors.Ink,
                            fontSize = 13.sp
                        )
                        Switch(
                            checked = useTls,
                            onCheckedChange = { useTls = it },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = WindColors.CanvasCream,
                                checkedTrackColor = WindColors.Ink,
                                checkedBorderColor = WindColors.Ink,
                                uncheckedThumbColor = WindColors.White,
                                uncheckedTrackColor = WindColors.DustTaupe,
                                uncheckedBorderColor = WindColors.DustTaupe
                            )
                        )
                    }
                    if (!useTls) {
                        WarningText(I18n.get("ftp_warning"))
                    }
                }

                // H18: warn when WebDAV is configured for cleartext HTTP.
                if (protocol == VfsProtocol.WEBDAV && host.isNotBlank() &&
                    !host.startsWith("https://", ignoreCase = true)
                ) {
                    WarningText(I18n.get("webdav_warning"))
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
                enabled = name.isNotBlank() && host.isNotBlank() && !portError,
                shape = WindRadius.Button,
                colors = ButtonDefaults.buttonColors(
                    containerColor = WindColors.Ink,
                    contentColor = WindColors.CanvasCream
                )
            ) {
                Text(text = I18n.get("save"), fontWeight = FontWeight.Medium)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = I18n.get("cancel"), color = WindColors.Slate)
            }
        }
    )
}

@Composable
private fun DialogTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    readOnly: Boolean = false,
    isError: Boolean = false,
    trailingIcon: @Composable (() -> Unit)? = null
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(text = label, color = WindColors.Slate) },
        singleLine = true,
        readOnly = readOnly,
        isError = isError,
        modifier = modifier.fillMaxWidth(),
        trailingIcon = trailingIcon,
        shape = WindRadius.Pill,
        textStyle = TextStyle(color = WindColors.Ink, fontSize = 14.sp),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = WindColors.CanvasCream,
            unfocusedContainerColor = WindColors.CanvasCream,
            cursorColor = WindColors.Ink,
            focusedIndicatorColor = WindColors.Ink,
            unfocusedIndicatorColor = WindColors.Hairline,
            errorIndicatorColor = WindColors.SignalOrange
        )
    )
}

@Composable
private fun WarningText(message: String) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Icon(
            painter = iconPainter(PhosphorIcons.WARNING),
            contentDescription = null,
            tint = WindColors.SignalOrange,
            modifier = Modifier.size(14.dp)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = message,
            color = WindColors.SignalOrange,
            fontSize = 11.sp
        )
    }
}
