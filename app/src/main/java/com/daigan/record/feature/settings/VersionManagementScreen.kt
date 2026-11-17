package com.daigan.record.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import com.daigan.record.BuildConfig
import com.daigan.record.update.ReleaseInfo
import com.daigan.record.update.UpdateClient
import com.daigan.record.ui.components.PageHeader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.content.Intent
import android.net.Uri
import android.widget.Toast

private const val DOWNLOAD_PAGE_URL = "https://www.pgyer.com/daiganjilu"

@Composable
fun VersionManagementScreen(onBack: () -> Unit) {
    var info by remember { mutableStateOf<ReleaseInfo?>(null) }
    var message by remember { mutableStateOf(if (BuildConfig.UPDATE_SERVICE_URL.isBlank()) "更新服务尚未配置" else "尚未检查更新") }
    var busy by remember { mutableStateOf(false) }
    var showUpdateDialog by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    val openDownloadPage = {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(DOWNLOAD_PAGE_URL)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }
            .onSuccess { message = "已打开下载页面，请下载安装包后手动安装" }
            .onFailure {
                message = "未找到可打开下载页面的浏览器，请安装浏览器后重试"
                Toast.makeText(context, "未找到可打开下载页面的浏览器", Toast.LENGTH_SHORT).show()
            }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        PageHeader(title = "版本管理", onBack = onBack)
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("当前版本 ${BuildConfig.VERSION_NAME}（${BuildConfig.VERSION_CODE}）", style = MaterialTheme.typography.titleMedium)
                    info?.let { Text("发现版本 ${it.versionName}（${it.versionCode}）\n${it.notes}") }
                    Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(enabled = !busy && BuildConfig.UPDATE_SERVICE_URL.isNotBlank(), onClick = {
                        busy = true
                        message = "正在检查更新…"
                        scope.launch {
                            runCatching { withContext(Dispatchers.IO) { UpdateClient().check() } }
                                .onSuccess { release ->
                                    info = release
                                    message = if (release.versionCode > BuildConfig.VERSION_CODE) "发现新版本" else "当前已是最新版"
                                    showUpdateDialog = release.versionCode > BuildConfig.VERSION_CODE
                                }
                                .onFailure { message = it.message ?: "检查更新失败" }
                            busy = false
                        }
                    }) { Text("检查更新") }
                    if ((info?.versionCode ?: 0) > BuildConfig.VERSION_CODE) {
                        Button(enabled = !busy, onClick = { showUpdateDialog = true }) { Text("下载更新") }
                    }
                }
            }
        }
    }

    if (showUpdateDialog) {
        val release = info
        AlertDialog(
            onDismissRequest = { showUpdateDialog = false },
            title = { Text("发现新版本") },
            text = {
                val notes = release?.notes?.takeIf { it.isNotBlank() }
                val versionLine = release?.let { "版本 ${it.versionName}（${it.versionCode}）" }.orEmpty()
                Text(if (notes != null) "$versionLine\n\n$notes" else versionLine)
            },
            confirmButton = {
                TextButton(onClick = {
                    showUpdateDialog = false
                    openDownloadPage()
                }) { Text("下载更新") }
            },
            dismissButton = {
                TextButton(onClick = { showUpdateDialog = false }) { Text("稍后") }
            },
        )
    }
}
