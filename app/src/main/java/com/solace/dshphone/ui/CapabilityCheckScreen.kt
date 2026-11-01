package com.solace.dshphone.ui

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.solace.dshphone.keepalive.ColorOsKeepAlive
import com.solace.dshphone.phone.AgentAccessibilityService

private data class CheckItem(val label: String, val detail: String, val granted: () -> Boolean, val fix: (Context) -> Unit)

/**
 * 系统能力自检：一个页面集中管理无障碍、通知、悬浮窗和厂商保活入口。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CapabilityCheckScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var refresh by remember { mutableStateOf(0) }

    val items = listOf(
        CheckItem("无障碍服务", "执行手机操作的前提", { AgentAccessibilityService.isAvailable() }, {
            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }),
        CheckItem("悬浮窗权限", "屏幕分享胶囊需要", {
            android.provider.Settings.canDrawOverlays(context)
        }, {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:${context.packageName}")),
            )
        }),
    )

    LaunchedEffect(refresh) { /* re-read on refresh */ }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("系统能力自检") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } },
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
            items.forEach { item ->
                Card {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(item.label, fontWeight = FontWeight.SemiBold)
                            Text(item.detail, style = MaterialTheme.typography.bodySmall)
                        }
                        Spacer(Modifier.width(8.dp))
                        val ok = refresh >= 0 && item.granted()
                        Text(
                            if (ok) "✓ 已开启" else "未开启",
                            color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    if (!item.granted()) {
                        Button(onClick = { item.fix(context); refresh++ }, modifier = Modifier.padding(start = 16.dp, bottom = 12.dp)) {
                            Text("去开启")
                        }
                    }
                }
            }

            if (ColorOsKeepAlive.isColorOs()) {
                Card {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("ColorOS 后台保活", fontWeight = FontWeight.SemiBold)
                        Text("检测到 OPPO / OnePlus / realme 设备。这些开关在厂商页面里，DSH-phone 无法读取实际状态，请逐个打开后手动开启：", style = MaterialTheme.typography.bodySmall)
                        ColorOsKeepAlive.entries.forEachIndexed { i, entry ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(entry.label, style = MaterialTheme.typography.bodyMedium)
                                    Text(entry.explanation, style = MaterialTheme.typography.bodySmall)
                                }
                                Button(onClick = { ColorOsKeepAlive.open(context, i); refresh++ }) { Text("打开") }
                            }
                        }
                    }
                }
            }

            Card {
                Column(Modifier.padding(16.dp)) {
                    Text("任务体验说明", fontWeight = FontWeight.SemiBold)
                    Text(
                        "执行任务时 DSH-phone 在后台替你操作应用和网页，你的屏幕始终归你自己用；需要登录、验证码或付款时会通知你接管，处理完交还即可继续。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}
