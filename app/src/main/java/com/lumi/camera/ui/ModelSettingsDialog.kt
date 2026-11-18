package com.lumi.camera.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicSecureTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.TextObfuscationMode
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import com.lumi.camera.BuildConfig
import com.lumi.camera.shotplan.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun ModelSettingsDialog(current: ModelConfig, store: ModelConfigStore, onDismiss: () -> Unit,
    onTested: (ModelConfig, ConnectionResult) -> Unit, onSaved: (ModelConfig, ConnectionResult?) -> Unit) {
    var address by remember { mutableStateOf(current.address) }
    var model by remember { mutableStateOf(current.model) }
    // Keep the key out of saved instance state, even when the dialog is recreated.
    val key = remember { TextFieldState(current.apiKey) }
    val draft = ModelConfig(address.trim(), model.trim(), key.text.toString())
    val error = draft.validationError(BuildConfig.DEBUG)
    val endpoint = chatEndpoint(draft.address, BuildConfig.DEBUG)
    var reveal by remember { mutableStateOf(false) }
    LaunchedEffect(reveal) { if (reveal) { delay(10_000); reveal = false } }

    var testing by remember(draft) { mutableStateOf(false) }
    var reading by remember(draft) { mutableStateOf<ProbeResult?>(null) }
    var saveError by remember(draft) { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    val resources = LocalContext.current.resources
    val scope = rememberCoroutineScope()
    LaunchedEffect(draft, testing) {
        if (testing) {
            val measured = testConnection(draft, resources)
            reading = measured
            testing = false
            onTested(draft, measured.result)
        }
    }

    Dialog(onDismissRequest = { if (!saving) onDismiss() },
        properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn)) {
        val focus = LocalFocusManager.current
        Surface(shape = MaterialTheme.shapes.large) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("模型设置", style = MaterialTheme.typography.titleLarge)
                OutlinedTextField(address, { address = it }, Modifier.fillMaxWidth(),
                    label = { Text("接口地址") }, singleLine = true, enabled = !saving,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
                if (endpoint?.protocol == "http" && endpoint.host != "127.0.0.1")
                    Text("明文传输，仅限可信网络", color = MaterialTheme.colorScheme.error)
                OutlinedTextField(model, { model = it }, Modifier.fillMaxWidth(),
                    label = { Text("模型名") }, singleLine = true, enabled = !saving)
                Text("API key", style = MaterialTheme.typography.labelMedium)
                Row(Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.outline, MaterialTheme.shapes.small),
                    verticalAlignment = Alignment.CenterVertically) {
                    BasicSecureTextField(key, Modifier.weight(1f).padding(12.dp)
                        .semantics { contentDescription = "API key" }, enabled = !saving,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        textObfuscationMode = if (reveal) TextObfuscationMode.Visible else TextObfuscationMode.Hidden)
                    TextButton({ reveal = !reveal }, enabled = !saving) { Text(if (reveal) "隐藏" else "显示") }
                }
                val feedback = when {
                    saving -> "正在保存…"
                    saveError -> "保存失败，请重新保存。"
                    error != null -> error
                    testing -> "正在测试…"
                    else -> reading?.text
                }
                feedback?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                OutlinedButton({
                    focus.clearFocus()
                    reveal = false
                    testing = true
                }, enabled = error == null && !testing && !saving) { Text("测试连接") }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onDismiss, enabled = !saving) { Text("取消") }
                    Button({
                        saving = true
                        testing = false
                        reveal = false
                        scope.launch {
                            if (withContext(Dispatchers.IO) { store.save(draft) }) onSaved(draft, reading?.result)
                            else { saving = false; saveError = true }
                        }
                    }, enabled = error == null && !saving) { Text("保存") }
                }
            }
        }
    }
}
