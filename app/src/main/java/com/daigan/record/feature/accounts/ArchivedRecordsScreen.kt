package com.daigan.record.feature.accounts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.daigan.record.data.model.ArchivedAccount
import com.daigan.record.data.model.Money
import com.daigan.record.data.model.OwnerSummary
import com.daigan.record.data.repository.OwnerDeletionImpact
import com.daigan.record.ui.components.EmptyStateCard
import com.daigan.record.ui.components.PageHeader
import com.daigan.record.ui.components.StatusTag

/** Archived owners and accounts with an explicit restore action. Nothing is deleted. */
@Composable
fun ArchivedRecordsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ArchivedRecordsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val deletionImpact by viewModel.deletionImpact.collectAsStateWithLifecycle()
    val deletionInFlight by viewModel.deletionInFlight.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        viewModel.messages.collect { message -> snackbarHostState.showSnackbar(message) }
    }

    ArchivedRecordsScreen(
        uiState = uiState,
        onBack = onBack,
        onRestoreOwner = viewModel::restoreOwner,
        onRestoreAccount = viewModel::restoreAccount,
        snackbarHostState = snackbarHostState,
        modifier = modifier,
        onDeleteOwner = viewModel::requestOwnerDeletion,
        deletionImpact = deletionImpact,
        deletionInFlight = deletionInFlight,
        onConfirmDeleteOwner = viewModel::confirmOwnerDeletion,
        onCancelDeleteOwner = viewModel::cancelOwnerDeletion,
    )
}

@Composable
internal fun ArchivedRecordsScreen(
    uiState: ArchivedRecordsUiState,
    onBack: () -> Unit,
    onRestoreOwner: (String) -> Unit,
    onRestoreAccount: (String) -> Unit,
    modifier: Modifier = Modifier,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
    onDeleteOwner: (String) -> Unit = {},
    deletionImpact: OwnerDeletionImpact? = null,
    deletionInFlight: Boolean = false,
    onConfirmDeleteOwner: () -> Unit = {},
    onCancelDeleteOwner: () -> Unit = {},
) {
    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            PageHeader(title = "归档记录", subtitle = "归档不会删除资料，可随时恢复", onBack = onBack)
            when {
                uiState.isLoading -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }

                uiState.error != null -> Box(modifier = Modifier.padding(16.dp)) {
                    EmptyStateCard(title = "无法读取归档记录", description = uiState.error.orEmpty())
                }

                uiState.archivedOwners.isEmpty() && uiState.archivedAccounts.isEmpty() -> Box(
                    modifier = Modifier.padding(16.dp),
                ) {
                    EmptyStateCard(
                        title = "归档记录为空",
                        description = "归档的老板或游戏账号会出现在这里，恢复后重新回到账号信息。",
                    )
                }

                else -> ArchivedList(
                    owners = uiState.archivedOwners,
                    accounts = uiState.archivedAccounts,
                    onRestoreOwner = onRestoreOwner,
                    onRestoreAccount = onRestoreAccount,
                    onDeleteOwner = onDeleteOwner,
                )
            }
        }
    }

    deletionImpact?.let { impact ->
        AlertDialog(
            onDismissRequest = { if (!deletionInFlight) onCancelDeleteOwner() },
            title = { Text("删除老板「${impact.ownerNickname}」？") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("将永久删除该老板及其全部关联数据，无法撤销：")
                    Text("· 游戏账号 ${impact.accountCount} 个", style = MaterialTheme.typography.bodySmall)
                    Text(
                        "· 订单 ${impact.orderCount} 个（其中未作废 ${impact.activeOrderCount} 个）",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        "· 打卡记录 ${impact.occurrenceCount} 条" +
                            if (impact.sessionCount > 0) "、打卡轮次 ${impact.sessionCount} 轮" else "",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        "· 收款 ${impact.receiptCount} 笔，有效合计 " +
                            Money.formatCents(impact.receiptSumCents) + "（删除后不再计入收入）",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        "· 未结清欠款 " + Money.formatCents(impact.outstandingCents) +
                            "（删除后不再计入账单）",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        "· 截图 ${impact.attachmentCount} 张",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        "其他老板、账号与订单不受影响。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            },
            confirmButton = {
                TextButton(enabled = !deletionInFlight, onClick = onConfirmDeleteOwner) {
                    Text(if (deletionInFlight) "删除中…" else "确认删除")
                }
            },
            dismissButton = {
                TextButton(enabled = !deletionInFlight, onClick = onCancelDeleteOwner) {
                    Text("取消")
                }
            },
        )
    }
}

@Composable
private fun ArchivedList(
    owners: List<OwnerSummary>,
    accounts: List<ArchivedAccount>,
    onRestoreOwner: (String) -> Unit,
    onRestoreAccount: (String) -> Unit,
    onDeleteOwner: (String) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (owners.isNotEmpty()) {
            item {
                Text(text = "已归档老板", style = MaterialTheme.typography.titleMedium)
            }
            items(owners, key = { "owner-${it.owner.id}" }) { summary ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = summary.owner.nickname,
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.weight(1f),
                            )
                            StatusTag(text = summary.owner.displayNumber)
                        }
                        Text(
                            text = archivedOwnerAccountSummary(summary),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(top = 8.dp),
                        ) {
                            Button(onClick = { onRestoreOwner(summary.owner.id) }) {
                                Text("恢复老板")
                            }
                            // UF-031: deletion does not require restoring first; the
                            // confirmation lists the real affected records and money.
                            TextButton(
                                onClick = { onDeleteOwner(summary.owner.id) },
                                modifier = Modifier
                                    .padding(start = 8.dp)
                                    .testTag("archived-owner-delete"),
                            ) {
                                Text("删除")
                            }
                        }
                    }
                }
            }
        }

        if (accounts.isNotEmpty()) {
            item {
                Text(
                    text = "已归档游戏账号",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            items(accounts, key = { "account-${it.account.id}" }) { archived ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = archived.account.gameName,
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text = "${archived.ownerNickname} · ${archived.ownerNumber} · " +
                                "UID ${archived.account.uid}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                        Button(
                            onClick = { onRestoreAccount(archived.account.id) },
                            modifier = Modifier.padding(top = 8.dp),
                        ) {
                            Text("恢复账号")
                        }
                    }
                }
            }
        }
    }
}

/**
 * Describes only what is known: the total count and how many accounts belong to the
 * current owner archive batch. Accounts archived separately (or archived by a legacy
 * version whose provenance cannot be recovered) are not claimed as "archived with the
 * owner", and they stay restorable one by one after the owner is restored.
 */
internal fun archivedOwnerAccountSummary(summary: OwnerSummary): String {
    val total = summary.accounts.size
    val withOwner = summary.accountsArchivedWithOwnerCount
    return when {
        total == 0 -> "归档时没有游戏账号"
        withOwner == total -> "$total 个随老板归档的游戏账号"
        else -> "共 $total 个游戏账号，其中 $withOwner 个随老板归档"
    }
}
