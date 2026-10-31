package com.cottonchou.dshphone.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** The task center: every run, its phase, and what it is doing now. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskCenterScreen(onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("任务中心") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            TaskListPlaceholder()
        }
    }
}

@Composable
private fun TaskListPlaceholder() {
    // Live states are pushed by TaskCenter; a static view keeps this screen
    // simple until the host wires a flow. See TaskCenter.allStates().
    LazyColumn(
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Card {
                Column(Modifier.padding(16.dp)) {
                    Text("这里会显示每个任务的运行状态", fontWeight = FontWeight.SemiBold)
                    Text(
                        "排队中 · 执行中 · 等你处理 · 已结束。多个对话的任务可以并行，超过上限自动排队；同一对话同时只有一个任务，新消息会作为插话交给正在跑的任务。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}
