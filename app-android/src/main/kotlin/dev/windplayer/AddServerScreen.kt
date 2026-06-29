package dev.windplayer

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.windplayer.ui.I18n
import dev.windplayer.vfs.ServerConfig
import dev.windplayer.vfs.VfsProtocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddServerScreen(
    onBack: () -> Unit,
    onSave: (ServerConfig) -> Unit,
    initialConfig: ServerConfig? = null
) {
    var name by remember { mutableStateOf(initialConfig?.name ?: "") }
    var protocol by remember { mutableStateOf(initialConfig?.protocol ?: VfsProtocol.SFTP) }
    var host by remember { mutableStateOf(initialConfig?.host ?: "") }
    var port by remember { mutableStateOf((initialConfig?.port ?: 22).toString()) }
    var username by remember { mutableStateOf(initialConfig?.username ?: "") }
    var password by remember { mutableStateOf(initialConfig?.password ?: "") }
    var basePath by remember { mutableStateOf(initialConfig?.basePath ?: "/") }
    // H18: default FTP to FTPS for new servers (prefer security). Existing
    // servers inherit their stored value via initialConfig.
    var useTls by remember { mutableStateOf(initialConfig?.useTls ?: (initialConfig?.protocol == VfsProtocol.FTP)) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }
    // H20: track success as a Boolean rather than parsing the localized
    // testResult string. Previously the check was result.startsWith("Connected"),
    // which broke in any non-English locale (a successful connection was
    // rendered as a failure with orange border).
    var testSuccess by remember { mutableStateOf<Boolean?>(null) }

    BackHandler { onBack() }

    fun buildConfig(): ServerConfig? {
        if (host.isBlank()) {
            Toast.makeText(context, I18n.get("host_required"), Toast.LENGTH_SHORT).show()
            return null
        }
        return ServerConfig(
            id = initialConfig?.id ?: UUID.randomUUID().toString(),
            name = name.ifBlank { host }.trim(),
            protocol = protocol,
            host = host.trim(),
            port = port.toIntOrNull() ?: 0,
            username = username.trim(),
            password = password,
            basePath = basePath.trim().ifBlank { "/" },
            useTls = useTls
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (initialConfig != null) I18n.get("edit_server") else I18n.get("add_server"),
                        color = WindColors.Ink,
                        fontWeight = FontWeight.Medium,
                        fontSize = 20.sp
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        PhosphorIcon(Phosphor.ARROW_LEFT, "Back", tint = WindColors.Ink, size = 22.dp)
                    }
                },
                actions = {
                    TextButton(onClick = { buildConfig()?.let { onSave(it) } }) {
                        Text(I18n.get("save"), color = WindColors.Ink, fontWeight = FontWeight.Bold)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = WindColors.LiftedCream)
            )
        },
        containerColor = WindColors.CanvasCream
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp, vertical = 12.dp)
                .verticalScroll(rememberScrollState())
        ) {
            EyebrowLabel(I18n.get("protocol"))
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                VfsProtocol.entries.forEach { p ->
                    FilterChip(
                        selected = protocol == p,
                        onClick = {
                            protocol = p
                            port = when (p) {
                                VfsProtocol.SFTP -> "22"
                                VfsProtocol.WEBDAV -> "443"
                                VfsProtocol.FTP -> "21"
                                else -> "0"
                            }
                        },
                        label = { Text(p.name, fontSize = 11.sp) },
                        shape = WindRadius.Pill,
                        colors = FilterChipDefaults.filterChipColors(
                            containerColor = WindColors.White,
                            labelColor = WindColors.Ink,
                            selectedContainerColor = WindColors.Ink,
                            selectedLabelColor = WindColors.CanvasCream
                        )
                    )
                }
            }

            FieldRow(I18n.get("name"), name) { name = it }
            FieldRow(I18n.get("host"), host) { host = it }
            FieldRow(I18n.get("port"), port) { port = it.filter { c -> c.isDigit() } }
            FieldRow(I18n.get("username"), username) { username = it }
            FieldRow(I18n.get("password"), password, isPassword = true) { password = it }
            FieldRow(I18n.get("base_path"), basePath) { basePath = it }

            // H18: TLS toggle for FTP (FTPS). SFTP is always encrypted; WebDAV
            // infers TLS from the https:// host prefix — no toggle needed.
            if (protocol == VfsProtocol.FTP) {
                Spacer(Modifier.height(8.dp))
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        I18n.get("use_tls"),
                        color = WindColors.Ink,
                        fontSize = 14.sp,
                        modifier = Modifier.weight(1f)
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

            // H18: warn when WebDAV is configured for cleartext HTTP (no https:// prefix).
            if (protocol == VfsProtocol.WEBDAV && host.isNotBlank() &&
                !host.startsWith("https://", ignoreCase = true)
            ) {
                Spacer(Modifier.height(8.dp))
                WarningText(I18n.get("webdav_warning"))
            }

            Spacer(Modifier.height(16.dp))

            // Test Connection button
            OutlinedButton(
                onClick = {
                    val config = buildConfig() ?: return@OutlinedButton
                    testing = true
                    testResult = null
                    scope.launch {
                        try {
                            val files = withContext(Dispatchers.IO) {
                                MobileVfsManager.listDirectory(config, config.basePath)
                            }
                            testResult = String.format(I18n.get("connected_items"), files.size)
                            testSuccess = true
                        } catch (e: Exception) {
                            testResult = String.format(I18n.get("failed_msg"), e.message ?: I18n.get("connection_error"))
                            testSuccess = false
                        }
                        testing = false
                    }
                },
                enabled = !testing,
                modifier = Modifier.fillMaxWidth(),
                shape = WindRadius.Button,
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = WindColors.White,
                    contentColor = WindColors.Ink
                ),
                border = androidx.compose.foundation.BorderStroke(1.5.dp, WindColors.Ink)
            ) {
                if (testing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = WindColors.Ink
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(I18n.get("testing"), fontSize = 13.sp)
                } else {
                    PhosphorIcon(Phosphor.PLUGS_CONNECTED, contentDescription = null, tint = WindColors.Ink, size = 18.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(I18n.get("test_connection"), fontSize = 13.sp)
                }
            }

            // Test result
            testResult?.let { result ->
                Spacer(Modifier.height(8.dp))
                val isSuccess = testSuccess == true
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = WindColors.White,
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        if (isSuccess) WindColors.Hairline else WindColors.SignalOrange.copy(alpha = 0.4f)
                    ),
                    shape = WindRadius.Consent
                ) {
                    Text(
                        text = result,
                        color = if (isSuccess) WindColors.ClayBrown else WindColors.SignalOrange,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }

            Spacer(Modifier.height(40.dp))
        }
    }
}

@Composable
private fun EyebrowLabel(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(6.dp)
                .background(WindColors.SignalOrange, shape = androidx.compose.foundation.shape.CircleShape)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text.uppercase(),
            color = WindColors.Slate,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.48.sp
        )
    }
}

@Composable
private fun WarningText(message: String) {
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.Top) {
        PhosphorIcon(Phosphor.WARNING, contentDescription = null, tint = WindColors.SignalOrange, size = 14.dp)
        Spacer(Modifier.width(6.dp))
        Text(message, color = WindColors.SignalOrange, fontSize = 11.sp)
    }
}

@Composable
private fun FieldRow(label: String, value: String, isPassword: Boolean = false, onChange: (String) -> Unit) {
    Column(Modifier.padding(vertical = 6.dp)) {
        Text(label, color = WindColors.Slate, fontSize = 12.sp)
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            shape = WindRadius.Pill,
            visualTransformation = if (isPassword) PasswordVisualTransformation() else VisualTransformation.None,
            textStyle = androidx.compose.ui.text.TextStyle(color = WindColors.Ink, fontSize = 14.sp),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = WindColors.White,
                unfocusedContainerColor = WindColors.White,
                cursorColor = WindColors.Ink,
                focusedIndicatorColor = WindColors.Ink,
                unfocusedIndicatorColor = WindColors.Hairline
            )
        )
    }
}
