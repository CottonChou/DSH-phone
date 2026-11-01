package com.solace.dshphone.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    initialProvider: String,
    initialBaseUrl: String,
    initialModel: String,
    initialKey: String,
    initialAutonomous: Boolean,
    engineLabel: String,
    safeMode: Boolean,
    onSave: (provider: String, baseUrl: String, model: String, key: String, autonomous: Boolean) -> Unit,
    onBack: () -> Unit,
) {
    var provider by remember { mutableStateOf(initialProvider) }
    var baseUrl by remember { mutableStateOf(initialBaseUrl) }
    var model by remember { mutableStateOf(initialModel) }
    var key by remember { mutableStateOf(initialKey) }
    var autonomous by remember { mutableStateOf(initialAutonomous) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("模型服务", fontWeight = FontWeight.Bold)
                    Text("内置 DeepSeek、通义千问、Kimi、智谱、OpenRouter、Anthropic、Gemini 预设；也可以自定义任何 OpenAI 兼容接口。", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(value = provider, onValueChange = { provider = it }, label = { Text("服务商") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = baseUrl, onValueChange = { baseUrl = it }, label = { Text("接口地址") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = model, onValueChange = { model = it }, label = { Text("模型名") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = key, onValueChange = { key = it }, label = { Text("API Key（加密保存在本机）") }, modifier = Modifier.fillMaxWidth())
                }
            }
            Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("做事方式", fontWeight = FontWeight.Bold)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("自主模式")
                            Text("关闭时，花钱的操作每次都会先问你；开启后自动执行（银行类应用仍然拦截）。", style = MaterialTheme.typography.bodySmall)
                        }
                        Spacer(Modifier.width(8.dp))
                        Switch(checked = autonomous, onCheckedChange = { autonomous = it })
                    }
                }
            }
            Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("执行引擎", fontWeight = FontWeight.Bold)
                    Text("当前：$engineLabel", style = MaterialTheme.typography.bodySmall)
                    if (safeMode) {
                        Text("外部引擎连续异常，已进入安全模式；新对话将使用内置引擎。", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            Button(onClick = { onSave(provider, baseUrl, model, key, autonomous) }, modifier = Modifier.fillMaxWidth()) {
                Text("保存")
            }
            Spacer(Modifier.height(24.dp))
            Divider()
            Text("DSH-phone 没有云端服务器：对话、截图和记忆都留在本机，只有模型请求会发到你配置的服务商。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
        }
    }
}
