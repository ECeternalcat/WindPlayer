package dev.windplayer

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.WifiFind
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
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }

    BackHandler { onBack() }

    fun buildConfig(): ServerConfig? {
        if (host.isBlank()) {
            Toast.makeText(context, "Host required", Toast.LENGTH_SHORT).show()
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
            basePath = basePath.trim().ifBlank { "/" }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (initialConfig != null) "Edit Server" else "Add Server", color = Color.White) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, "Back", tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF1A1A2E))
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    buildConfig()?.let { onSave(it) }
                },
                containerColor = Color(0xFF0F84E4),
                contentColor = Color.White
            ) {
                Icon(Icons.Default.Check, "Save")
            }
        },
        containerColor = Color(0xFF0F0F1A)
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text("Protocol", color = Color(0xFF0F84E4), fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Color(0xFF0F84E4),
                            selectedLabelColor = Color.White
                        )
                    )
                }
            }

            FieldRow("Name", name) { name = it }
            FieldRow("Host", host) { host = it }
            FieldRow("Port", port) { port = it.filter { c -> c.isDigit() } }
            FieldRow("Username", username) { username = it }
            FieldRow("Password", password, isPassword = true) { password = it }
            FieldRow("Base Path", basePath) { basePath = it }

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
                            testResult = "Connected: ${files.size} items found"
                        } catch (e: Exception) {
                            testResult = "Failed: ${e.message ?: "Connection error"}"
                        }
                        testing = false
                    }
                },
                enabled = !testing,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = Color(0xFF0F84E4),
                    disabledContentColor = Color(0xFF888888)
                ),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF333366))
            ) {
                if (testing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = Color(0xFF0F84E4)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Testing...", fontSize = 13.sp)
                } else {
                    Icon(Icons.Default.WifiFind, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Test Connection", fontSize = 13.sp)
                }
            }

            // Test result
            testResult?.let { result ->
                Spacer(Modifier.height(8.dp))
                val isSuccess = result.startsWith("Connected")
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = if (isSuccess) Color(0xFF1A2A1A) else Color(0xFF2A1A1A),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = result,
                        color = if (isSuccess) Color(0xFF66BB6A) else Color(0xFFFF6B6B),
                        fontSize = 12.sp,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }

            Spacer(Modifier.height(80.dp))
        }
    }
}

@Composable
private fun FieldRow(label: String, value: String, isPassword: Boolean = false, onChange: (String) -> Unit) {
    Column(Modifier.padding(vertical = 4.dp)) {
        Text(label, color = Color(0xFF888888), fontSize = 12.sp)
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            visualTransformation = if (isPassword) PasswordVisualTransformation() else VisualTransformation.None,
            textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 14.sp),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color(0xFF1A1A2E),
                unfocusedContainerColor = Color(0xFF1A1A2E),
                cursorColor = Color(0xFF0F84E4),
                focusedIndicatorColor = Color(0xFF0F84E4),
                unfocusedIndicatorColor = Color(0xFF333366)
            )
        )
    }
}
