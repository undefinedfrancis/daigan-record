package com.daigan.record.feature.accounts

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.PendingActions
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.daigan.record.data.model.OwnerSummary
import com.daigan.record.data.preferences.SharedPreferencesUiPreferenceStore
import com.daigan.record.ui.components.DisclosureHeader
import com.daigan.record.ui.components.EmptyStateCard
import com.daigan.record.ui.components.PageHeader
import com.daigan.record.ui.components.SecondaryText
import com.daigan.record.ui.components.StatusTag
import com.daigan.record.ui.components.rememberExpansionState

@Composable
fun AccountsScreen(
    onOpenSettings: () -> Unit,
    onOpenOwner: (String) -> Unit,
    onCreateOwner: () -> Unit,
    onOpenArchived: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AccountsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val viewMode by viewModel.viewMode.collectAsStateWithLifecycle()
    val categoryFilter by viewModel.categoryFilter.collectAsStateWithLifecycle()
    val categoryMessage by viewModel.categoryMessage.collectAsStateWithLifecycle()
    AccountsScreen(
        uiState = uiState,
        viewMode = viewMode,
        onViewModeChange = viewModel::setViewMode,
        categoryFilter = categoryFilter,
        onCategoryFilterChange = viewModel::setCategoryFilter,
        categoryMessage = categoryMessage,
        onRenameCategory = viewModel::renameCategory,
        onDeleteCategory = viewModel::deleteCategory,
        onClearCategoryMessage = viewModel::clearCategoryMessage,
        onOpenSettings = onOpenSettings,
        onOpenOwner = onOpenOwner,
        onCreateOwner = onCreateOwner,
        onOpenArchived = onOpenArchived,
        modifier = modifier,
    )
}

@Composable
internal fun AccountsScreen(
    uiState: OwnersUiState,
    onOpenSettings: () -> Unit,
    onOpenOwner: (String) -> Unit,
    onCreateOwner: () -> Unit,
    onOpenArchived: () -> Unit,
    modifier: Modifier = Modifier,
    viewMode: String = SharedPreferencesUiPreferenceStore.ACCOUNT_VIEW_OWNER,
    onViewModeChange: (String) -> Unit = {},
    categoryFilter: String? = null,
    onCategoryFilterChange: (String?) -> Unit = {},
    categoryMessage: String? = null,
    onRenameCategory: (String, String) -> Unit = { _, _ -> },
    onDeleteCategory: (String) -> Unit = {},
    onClearCategoryMessage: () -> Unit = {},
) {
    var query by remember { mutableStateOf("") }
    Column(modifier = modifier.fillMaxSize()) {
        PageHeader(
            title = "账号信息",
            actions = {
                IconButton(onClick = onOpenSettings) {
                    Icon(Icons.Filled.Settings, contentDescription = "设置")
                }
            },
        )
        when (uiState) {
            OwnersUiState.Loading -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }

            is OwnersUiState.Error -> Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
            ) {
                EmptyStateCard(title = "无法读取资料", description = uiState.message)
            }

            is OwnersUiState.Success -> Column(modifier = Modifier.fillMaxSize()) {
                AccountViewSwitch(
                    viewMode = viewMode,
                    onViewModeChange = onViewModeChange,
                )
                if (viewMode == SharedPreferencesUiPreferenceStore.ACCOUNT_VIEW_ACCOUNT) {
                    AccountDirectory(
                        rows = uiState.accounts.filter { row -> matchesDirectorySearch(row, query) },
                        categories = uiState.categories,
                        categoryFilter = categoryFilter,
                        onCategoryFilterChange = onCategoryFilterChange,
                        categoryMessage = categoryMessage,
                        onRenameCategory = onRenameCategory,
                        onDeleteCategory = onDeleteCategory,
                        onClearCategoryMessage = onClearCategoryMessage,
                        onOpenOwner = onOpenOwner,
                        query = query,
                        onQueryChange = { query = it },
                    )
                } else {
                    OwnerList(
                        owners = uiState.owners.filter { summary ->
                            matchesAccountSearch(summary, query)
                        },
                        archivedCount = uiState.archivedCount,
                        onOpenOwner = onOpenOwner,
                        onCreateOwner = onCreateOwner,
                        onOpenArchived = onOpenArchived,
                        query = query,
                        onQueryChange = { query = it },
                    )
                }
            }
        }
    }
}

/** 老板视图 ↔ 跨老板账号视图（UF-037）；选择本机保存。 */
@Composable
private fun AccountViewSwitch(
    viewMode: String,
    onViewModeChange: (String) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FilterChip(
            selected = viewMode != SharedPreferencesUiPreferenceStore.ACCOUNT_VIEW_ACCOUNT,
            onClick = { onViewModeChange(SharedPreferencesUiPreferenceStore.ACCOUNT_VIEW_OWNER) },
            label = { Text("按老板") },
        )
        FilterChip(
            selected = viewMode == SharedPreferencesUiPreferenceStore.ACCOUNT_VIEW_ACCOUNT,
            onClick = { onViewModeChange(SharedPreferencesUiPreferenceStore.ACCOUNT_VIEW_ACCOUNT) },
            label = { Text("按账号") },
        )
    }
}

/**
 * 跨老板账号视图：所有在用账号平铺，行内显示老板身份与分类；操作始终按 accountId，
 * 因此不同老板下同名的账号也不会被混淆。分类筛选只影响显示，不改变归属。
 */
@Composable
private fun AccountDirectory(
    rows: List<AccountDirectoryRow>,
    categories: List<com.daigan.record.data.model.AccountCategory>,
    categoryFilter: String?,
    onCategoryFilterChange: (String?) -> Unit,
    categoryMessage: String?,
    onRenameCategory: (String, String) -> Unit,
    onDeleteCategory: (String) -> Unit,
    onClearCategoryMessage: () -> Unit,
    onOpenOwner: (String) -> Unit,
    query: String,
    onQueryChange: (String) -> Unit,
) {
    var managing by remember { mutableStateOf(false) }
    val visible = rows.filter { row ->
        when {
            categoryFilter == UNCATEGORIZED_FILTER -> row.account.categoryId == null
            categoryFilter != null -> row.account.categoryId == categoryFilter
            else -> true
        }
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("搜索老板、UID、游戏、区服或登录账号") },
            )
        }
        item {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = "分类",
                    style = MaterialTheme.typography.labelLarge,
                )
                TextButton(onClick = { managing = true }) { Text("管理分类") }
            }
        }
        item {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
                    .testTag("account-category-filter"),
            ) {
                FilterChip(
                    selected = categoryFilter == null,
                    onClick = { onCategoryFilterChange(null) },
                    label = { Text("全部") },
                )
                FilterChip(
                    selected = categoryFilter == UNCATEGORIZED_FILTER,
                    onClick = { onCategoryFilterChange(UNCATEGORIZED_FILTER) },
                    label = { Text("未分类") },
                )
                categories.forEach { category ->
                    FilterChip(
                        selected = categoryFilter == category.id,
                        onClick = { onCategoryFilterChange(category.id) },
                        label = { Text(category.name) },
                    )
                }
            }
        }
        categoryMessage?.let { message ->
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onClearCategoryMessage) { Text("知道了") }
                }
            }
        }
        if (visible.isEmpty()) {
            item {
                EmptyStateCard(
                    title = if (query.isNotBlank()) "没有匹配的账号" else "该分类下还没有账号",
                    description = if (query.isNotBlank()) {
                        "请更换关键词或清空搜索。"
                    } else {
                        "可在账号表单里为账号选择或新建分类。"
                    },
                )
            }
        }
        items(visible, key = { it.account.id }) { row ->
            AccountDirectoryCard(row = row, onOpenOwner = { onOpenOwner(row.ownerId) })
        }
    }
    if (managing) {
        CategoryManageDialog(
            categories = categories,
            onRename = onRenameCategory,
            onDelete = onDeleteCategory,
            onDismiss = { managing = false },
        )
    }
}

@Composable
private fun AccountDirectoryCard(
    row: AccountDirectoryRow,
    onOpenOwner: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().testTag("account-card-${row.account.id}"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = row.account.gameName,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                StatusTag(text = row.categoryName ?: "未分类")
            }
            SecondaryText(
                text = "${row.ownerNickname} · ${row.ownerNumberLabel}",
            )
            SecondaryText(
                text = buildString {
                    append(row.account.server)
                    append(" · UID ${row.account.uid}")
                    if (row.account.archived) append(" · 已归档")
                },
            )
            TextButton(onClick = onOpenOwner, modifier = Modifier.heightIn(min = 48.dp)) {
                Text("查看账号详情")
            }
        }
    }
}

/** 分类管理：重命名与删除；删除只把账号置为未分类，不删除任何业务数据。 */
@Composable
private fun CategoryManageDialog(
    categories: List<com.daigan.record.data.model.AccountCategory>,
    onRename: (String, String) -> Unit,
    onDelete: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
        title = { Text("管理分类") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (categories.isEmpty()) {
                    Text("还没有分类。可在账号表单里新建分类。")
                }
                categories.forEach { category ->
                    var editing by remember(category.id) { mutableStateOf(false) }
                    var name by remember(category.id) { mutableStateOf(category.name) }
                    if (editing) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = name,
                                onValueChange = { name = it },
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(
                                onClick = {
                                    onRename(category.id, name)
                                    editing = false
                                },
                                enabled = name.isNotBlank(),
                            ) { Text("保存") }
                            TextButton(onClick = { editing = false }) { Text("取消") }
                        }
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = category.name,
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = { editing = true }) { Text("重命名") }
                            TextButton(onClick = { onDelete(category.id) }) { Text("删除") }
                        }
                    }
                }
            }
        },
    )
}

private fun matchesDirectorySearch(row: AccountDirectoryRow, query: String): Boolean {
    if (query.isBlank()) return true
    val keyword = query.trim()
    return listOf(
        row.ownerNickname,
        row.account.gameName,
        row.account.server,
        row.account.uid,
        row.account.loginAccount,
        row.categoryName.orEmpty(),
    ).any { it.contains(keyword, ignoreCase = true) }
}

/** 分类筛选用的哨兵值：只表示「未分类」，不会与真实分类 id 冲突。 */
private const val UNCATEGORIZED_FILTER = "__uncategorized__"

@Composable
private fun OwnerList(
    owners: List<OwnerSummary>,
    archivedCount: Int,
    onOpenOwner: (String) -> Unit,
    onCreateOwner: () -> Unit,
    onOpenArchived: () -> Unit,
    query: String = "",
    onQueryChange: (String) -> Unit = {},
) {
    // Expansion is keyed by owner id (UF-029): re-sorting or refreshing the list can
    // never move one owner's open details onto another owner.
    val expansion = rememberExpansionState()
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("搜索老板、UID、游戏、区服或登录账号") },
            )
        }
        item {
            SecondaryText("仅搜索未归档老板的在用账号；归档内容请进入归档记录。")
            if (query.isNotBlank()) TextButton(onClick = { onQueryChange("") }) { Text("清空搜索") }
        }
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // The only top-level creation action in this section.
                Button(onClick = onCreateOwner) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Text(text = "新增", modifier = Modifier.padding(start = 4.dp))
                }
                Box(modifier = Modifier.weight(1f))
                TextButton(onClick = onOpenArchived) {
                    Text(
                        text = if (archivedCount > 0) "归档记录 ($archivedCount)" else "归档记录",
                    )
                }
            }
        }
        if (owners.isEmpty()) {
            item {
                EmptyStateCard(
                    title = if (query.isNotBlank()) "没有匹配的老板或账号" else "还没有老板资料",
                    description = if (query.isNotBlank()) "请更换关键词或清空搜索。" else "新增老板资料并添加游戏账号。",
                )
            }
        }
        items(owners, key = { it.owner.id }) { summary ->
            OwnerCard(
                summary = summary,
                expanded = expansion.isExpanded(summary.owner.id),
                onToggle = { expansion.toggle(summary.owner.id) },
                onOpen = { onOpenOwner(summary.owner.id) },
            )
        }
    }
}

/**
 * Owner unit (UF-029). Collapsed by default: only the name, its fixed number and whether
 * work is unfinished. The header is the only expansion trigger, so opening the unit
 * cannot navigate or archive anything; the details/actions appear in the expanded body.
 */
@Composable
private fun OwnerCard(
    summary: OwnerSummary,
    expanded: Boolean,
    onToggle: () -> Unit,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth().testTag("owner-card-${summary.owner.id}"),
        border = if (summary.unfinishedOrderCount > 0) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 8.dp, end = 4.dp, top = 2.dp, bottom = if (expanded) 8.dp else 2.dp),
        ) {
            DisclosureHeader(
                expanded = expanded,
                onToggle = onToggle,
                tag = "owner-header-${summary.owner.id}",
            ) {
                if (summary.unfinishedOrderCount > 0) {
                    Icon(
                        imageVector = Icons.Filled.PendingActions,
                        contentDescription = "有未完成订单",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp),
                    )
                }
                Text(
                    text = summary.owner.nickname,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = if (expanded) 3 else 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                StatusTag(text = summary.owner.displayNumber)
            }
            if (expanded) {
                Column(
                    modifier = Modifier.padding(end = 8.dp, bottom = 2.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (summary.unfinishedOrderCount > 0) {
                        Text(
                            text = "进行中 · ${summary.unfinishedOrderCount} 单",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    SecondaryText(
                        text = if (summary.activeAccountCount == 0) {
                            "暂无游戏账号"
                        } else {
                            "${summary.activeAccountCount} 个游戏账号 · " +
                                summary.gameNames.joinToString(" / ")
                        },
                    )
                    TextButton(onClick = onOpen, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text("查看账号")
                    }
                }
            }
        }
    }
}
