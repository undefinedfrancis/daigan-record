package com.daigan.record.feature.today

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.RadioButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.daigan.record.core.time.HostingStatus
import com.daigan.record.data.model.Money
import com.daigan.record.data.model.NumericMode
import com.daigan.record.data.model.OrderTask
import com.daigan.record.data.model.ProgressType
import com.daigan.record.data.model.ShortWorkOrder
import com.daigan.record.data.model.TodayHostingOrder
import com.daigan.record.data.model.UndoResult
import com.daigan.record.ui.components.CompletionRow
import com.daigan.record.ui.components.DisclosureHeader
import com.daigan.record.ui.components.EmptyStateCard
import com.daigan.record.ui.components.PageHeader
import com.daigan.record.ui.components.SecondaryText
import com.daigan.record.ui.components.SectionHeader
import com.daigan.record.ui.components.StatusTag
import com.daigan.record.ui.components.rememberExpansionState
import com.daigan.record.ui.components.formatGameDate
import com.daigan.record.ui.components.formatShortDate
import com.daigan.record.ui.theme.OrderColorOption
import com.daigan.record.ui.theme.OrderColors
import java.math.BigDecimal
import java.time.temporal.ChronoUnit

@Composable
fun TodayScreen(
    onOpenSettings: () -> Unit,
    onOpenOrder: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: TodayViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val lifecycleOwner = LocalLifecycleOwner.current

    LaunchedEffect(Unit) {
        viewModel.messages.collect { snackbar.showSnackbar(it) }
    }
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            viewModel.refresh()
        }
    }

    TodayScreen(
        uiState = uiState,
        onSelectTab = viewModel::selectTab,
        onOpenSettings = onOpenSettings,
        onOpenOrder = onOpenOrder,
        onCompleteAll = viewModel::completeAllHosting,
        onCompleteOrder = viewModel::completeHostingOrder,
        onToggleHostingTask = viewModel::toggleHostingTask,
        onUpdateHostingProgress = viewModel::updateHostingProgress,
        onToggleShortTask = viewModel::toggleShortTask,
        onUpdateShortProgress = viewModel::updateShortProgress,
        onUndo = viewModel::undoLastBatch,
        onClearUndo = viewModel::clearUndoResult,
        onToggleCompleted = viewModel::toggleCompletedSection,
        onSetColorFilter = viewModel::setColorFilter,
        onSetTaskGroup = viewModel::setTaskGroup,
        groups = uiState.groups,
        snackbar = snackbar,
        modifier = modifier,
    )
}

@Composable
internal fun TodayScreen(
    uiState: TodayUiState,
    onSelectTab: (TodayTab) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenOrder: (String) -> Unit,
    onCompleteAll: () -> Unit,
    onCompleteOrder: (String) -> Unit,
    onToggleHostingTask: (String, String, Boolean) -> Unit,
    onUpdateHostingProgress: (String, String, String) -> Unit,
    onToggleShortTask: (String, Boolean) -> Unit,
    onUpdateShortProgress: (String, String) -> Unit,
    onUndo: () -> Unit,
    onClearUndo: () -> Unit,
    onToggleCompleted: () -> Unit,
    onSetColorFilter: (TodayColorFilter) -> Unit = {},
    onSetTaskGroup: (String, String?) -> Unit = { _, _ -> },
    groups: Map<String, String> = uiState.groups,
    modifier: Modifier = Modifier,
    snackbar: SnackbarHostState = remember { SnackbarHostState() },
) {
    var groupDialogOpen by rememberSaveable { mutableStateOf(false) }
    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            PageHeader(
                title = "今日事项",
                subtitle = uiState.dateLabel,
                actions = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // Grouping lives outside the colour story and keeps working as
                        // before; colour assignment itself moved to the order page.
                        TextButton(onClick = { groupDialogOpen = true }) { Text("分组") }
                        IconButton(onClick = onOpenSettings) {
                            Icon(Icons.Filled.Settings, contentDescription = "设置")
                        }
                    }
                },
            )
            TabRow(selectedTabIndex = uiState.selectedTab.ordinal) {
                TodayTab.entries.forEach { tab ->
                    Tab(
                        selected = uiState.selectedTab == tab,
                        onClick = { onSelectTab(tab) },
                        text = { Text(tab.label) },
                    )
                }
            }
            // Compact, direct colour filter: no dialog has to be opened first.
            OrderColorFilterRow(
                filter = uiState.colorFilter,
                colors = uiState.usedColors,
                onSelect = onSetColorFilter,
            )

            when {
                uiState.loading -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator() }

                uiState.error != null -> Box(modifier = Modifier.padding(16.dp)) {
                    EmptyStateCard(title = "无法读取今日事项", description = checkNotNull(uiState.error))
                }

                uiState.selectedTab == TodayTab.HOSTING -> HostingList(
                    uiState = uiState,
                    onOpenOrder = onOpenOrder,
                    onCompleteAll = onCompleteAll,
                    onCompleteOrder = onCompleteOrder,
                    onToggleTask = onToggleHostingTask,
                    onUpdateProgress = onUpdateHostingProgress,
                    onUndo = onUndo,
                    onToggleCompleted = onToggleCompleted,
                    groups = groups,
                )

                else -> ShortList(
                    uiState = uiState,
                    onOpenOrder = onOpenOrder,
                    onToggleTask = onToggleShortTask,
                    onUpdateProgress = onUpdateShortProgress,
                    groups = groups,
                )
            }
        }
    }

    uiState.undoResult?.let { result ->
        UndoResultDialog(result = result, onDismiss = onClearUndo)
    }
    if (groupDialogOpen) {
        TodayGroupDialog(
            accounts = (uiState.hosting.map {
                it.accountId to "${it.ownerNickname} · ${it.ownerNumber} · ${it.gameName} · ${it.order.title}"
            } + uiState.shortOrders.map {
                it.accountId to "${it.ownerNickname} · ${it.ownerNumber} · ${it.gameName} · ${it.order.title}"
            }).distinctBy { it.first },
            groups = uiState.groups,
            onGroup = onSetTaskGroup,
            onDismiss = { groupDialogOpen = false },
        )
    }
}

/**
 * Direct colour filter (UF-028): "全部" resets, "未着色" keeps uncoloured orders
 * reachable, and every colour actually used today is one tap away. Chips only appear
 * for colours in use, so the row stays compact.
 */
@Composable
private fun OrderColorFilterRow(
    filter: TodayColorFilter,
    colors: List<OrderColorOption>,
    onSelect: (TodayColorFilter) -> Unit,
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.fillMaxWidth().testTag("today-color-filter"),
    ) {
        item {
            FilterChip(
                selected = filter == TodayColorFilter.All,
                onClick = { onSelect(TodayColorFilter.All) },
                label = { Text("全部") },
            )
        }
        // Always offered: uncoloured orders stay reachable even when every other order
        // currently has a colour.
        item {
            FilterChip(
                selected = filter == TodayColorFilter.Uncolored,
                onClick = { onSelect(TodayColorFilter.Uncolored) },
                label = { Text("未着色") },
                leadingIcon = {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape),
                    )
                },
            )
        }
        items(colors) { option ->
            val selected = (filter as? TodayColorFilter.Matched)?.argb == option.argb
            FilterChip(
                selected = selected,
                onClick = { onSelect(TodayColorFilter.Matched(option.argb)) },
                label = { Text(option.label) },
                leadingIcon = {
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .clip(CircleShape)
                            .background(Color(option.argb)),
                    )
                },
            )
        }
    }
}

/**
 * Group assignment only. Colour assignment was removed from this page (UF-028) and now
 * lives on the order detail page; grouping is unrelated data and keeps its old behaviour
 * (keyed by account, shown as section headers).
 */
@Composable
private fun TodayGroupDialog(
    accounts: List<Pair<String, String>>,
    groups: Map<String, String>,
    onGroup: (String, String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var account by rememberSaveable { mutableStateOf(accounts.firstOrNull()?.first.orEmpty()) }
    var group by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(account, groups) {
        group = groups[account].orEmpty()
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("今日分组") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("选择账号", style = MaterialTheme.typography.titleSmall)
                if (accounts.isEmpty()) Text("今日暂无可设置的账号")
                accounts.forEach { (id, label) ->
                    Row(modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { account = id },
                        verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = account == id, onClick = null)
                        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    }
                }
                OutlinedTextField(group, { group = it }, label = { Text("分组（可留空取消）") }, singleLine = true)
                Text("订单颜色请在订单详情页顶部选择。", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(enabled = accounts.any { it.first == account }, onClick = {
                onGroup(account, group.trim().ifBlank { null })
                onDismiss()
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun HostingList(
    uiState: TodayUiState,
    onOpenOrder: (String) -> Unit,
    onCompleteAll: () -> Unit,
    onCompleteOrder: (String) -> Unit,
    onToggleTask: (String, String, Boolean) -> Unit,
    onUpdateProgress: (String, String, String) -> Unit,
    onUndo: () -> Unit,
    onToggleCompleted: () -> Unit,
    groups: Map<String, String> = uiState.groups,
) {
    val actionable = uiState.filteredHosting.filter {
        it.status == HostingStatus.ACTIVE && it.tasks.any { task -> !task.completed }
    }
    // One expansion map per list, keyed by order id, so regrouping or a refresh never
    // moves any unit's open state onto another unit.
    val expansion = rememberExpansionState()
    LazyColumn(
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        if (uiState.filteredHosting.isEmpty()) {
            item {
                EmptyStateCard(
                    title = if (uiState.colorFilter == TodayColorFilter.All) {
                        "还没有托管打卡"
                    } else {
                        "该颜色下没有托管打卡"
                    },
                    description = if (uiState.colorFilter == TodayColorFilter.All) {
                        "暂时没有托管打卡。"
                    } else {
                        "可点“全部”清除颜色筛选。"
                    },
                )
            }
        } else {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Button(
                        onClick = onCompleteAll,
                        enabled = actionable.isNotEmpty(),
                    ) { Text("所有应做托管单完成") }
                    if (uiState.lastBatchId != null) {
                        TextButton(onClick = onUndo) { Text("撤销") }
                    }
                }
            }

            uiState.activeHosting.groupBy { groups[it.accountId]?.takeIf(String::isNotBlank) ?: "未分组" }
                .toSortedMap().forEach { (group, orders) ->
                if (groups.isNotEmpty()) {
                    item(key = "hosting-group-$group") { SectionHeader(text = "分组 · $group") }
                }
                orders.sortedBy { it.order.title }.forEach { order ->
                    item(key = "hosting-${order.order.id}") {
                        HostingOrderCard(
                            order = order,
                            expanded = expansion.isExpanded(order.order.id),
                            onToggle = { expansion.toggle(order.order.id) },
                            onOpen = { onOpenOrder(order.order.id) },
                            onCompleteOrder = { onCompleteOrder(order.order.id) },
                            onToggleTask = onToggleTask,
                            onUpdateProgress = onUpdateProgress,
                        )
                    }
                }
            }

            if (uiState.completedHosting.isNotEmpty()) {
                item(key = "hosting-completed-header") {
                    DisclosureHeader(
                        expanded = uiState.completedExpanded,
                        onToggle = onToggleCompleted,
                        tag = "today-completed-header",
                    ) {
                        Text(
                            text = "已完成 · ${uiState.completedHosting.size} 单",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                if (uiState.completedExpanded) {
                    uiState.completedHosting.forEach { order ->
                        item(key = "completed-${order.order.id}") {
                            CompletedHostingCard(
                                order = order,
                                onOpen = { onOpenOrder(order.order.id) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HostingOrderCard(
    order: TodayHostingOrder,
    expanded: Boolean,
    onToggle: () -> Unit,
    onOpen: () -> Unit,
    onCompleteOrder: () -> Unit,
    onToggleTask: (String, String, Boolean) -> Unit,
    onUpdateProgress: (String, String, String) -> Unit,
) {
    val mark = OrderColors.mark(order.order.colorArgb)
    val doneCount = order.tasks.count { it.completed }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = OrderColors.tint(
                order.order.colorArgb, MaterialTheme.colorScheme.surface,
            ),
        ),
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            OrderColorStripe(mark = mark)
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 8.dp, end = 4.dp, top = 2.dp, bottom = if (expanded) 8.dp else 2.dp),
            ) {
                // Collapsed default (UF-029): title, colour mark, completion tag and a
                // chevron. Checkboxes and buttons only exist in the expanded body, so
                // expanding can never complete or open anything by accident.
                DisclosureHeader(
                    expanded = expanded,
                    onToggle = onToggle,
                    tag = "today-hosting-header-${order.order.id}",
                ) {
                    OrderColorDot(mark)
                    Text(
                        text = order.order.title,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = if (expanded) Int.MAX_VALUE else 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (order.tasks.isNotEmpty()) {
                        Text(
                            text = "$doneCount/${order.tasks.size}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    StatusTag(text = hostingStatusText(order))
                }

                if (expanded) {
                    Column(
                        modifier = Modifier.padding(end = 8.dp, bottom = 2.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        // One merged identity line instead of two stacked labels.
                        SecondaryText(
                            text = "${order.ownerNickname} · ${order.ownerNumber} · " +
                                "${order.gameName} · ${order.server}",
                        )
                        SecondaryText(text = hostingPeriodText(order))
                        if (order.countBased && !order.sessionsComplete) {
                            SecondaryText(
                                text = "第 ${order.activeSessionIndex ?: (order.completedSessions + 1)} 次打卡 · " +
                                    "本轮全部任务完成才计 1 次",
                            )
                        }

                        when (order.status) {
                            HostingStatus.EXPIRED -> Text(
                                text = "已到期，不再生成新任务；历史记录、截图与期次价格保留。可进入详情续期或补录。",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error,
                            )

                            HostingStatus.NOT_STARTED -> Text(
                                text = "托管尚未开始，不会提前生成任务。",
                                style = MaterialTheme.typography.bodyMedium,
                            )

                            HostingStatus.ACTIVE -> order.tasks.forEach { task ->
                                CompletionRow(
                                    title = task.task.title,
                                    modifier = Modifier.heightIn(min = 48.dp),
                                    checked = task.completed,
                                    colorArgb = task.task.colorArgb ?: order.order.colorArgb,
                                    onCheckedChange = { completed ->
                                        onToggleTask(task.task.id, task.slot.key, completed)
                                    },
                                    subtitle = buildString {
                                        if (task.windowLabel != null) {
                                            append("游戏周 ${task.windowLabel}")
                                        }
                                        if (task.task.progressType == ProgressType.NUMERIC) {
                                            if (isNotEmpty()) append(" · ")
                                            append(
                                                (if (task.task.numericMode == NumericMode.CUMULATIVE) "累计 " else "进度 ") +
                                                    (task.value ?: task.task.currentValue).toPlainString() + "/" +
                                                    task.task.targetValue.toPlainString(),
                                            )
                                            task.task.unit?.let { append(" $it") }
                                        }
                                    },
                                    trailing = {
                                        if (task.task.progressType == ProgressType.NUMERIC) {
                                            if (task.task.numericMode == NumericMode.CUMULATIVE) {
                                                // 累计目标要输入「当前总值」并保留逐条历史，
                                                // 在订单详情的累计面板里完成，避免今日页写入
                                                // 不可追溯的增量。
                                                TextButton(onClick = onOpen) { Text("记录累计") }
                                            } else {
                                                ProgressDialogButton(
                                                    initialValue = (task.value ?: task.task.currentValue).toPlainString(),
                                                    onValue = { onUpdateProgress(task.task.id, task.slot.key, it) },
                                                )
                                            }
                                        }
                                    },
                                )
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (
                                order.status == HostingStatus.ACTIVE &&
                                !order.sessionsComplete &&
                                order.tasks.any { !it.completed }
                            ) {
                                TextButton(onClick = onCompleteOrder) {
                                    Text(if (order.countBased) "本轮完成" else "整单完成")
                                }
                            }
                            Box(modifier = Modifier.weight(1f))
                            TextButton(onClick = onOpen) {
                                Text(
                                    if (!order.countBased && order.status == HostingStatus.EXPIRED) {
                                        "查看并续期"
                                    } else {
                                        "订单详情"
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Status wording shown in the collapsed header: completion stays recognisable. */
private fun hostingStatusText(order: TodayHostingOrder): String = if (order.countBased) {
    when {
        order.sessionsComplete -> "已完成"
        order.allTasksCompleted -> "本轮已完成"
        else -> "打卡中"
    }
} else {
    when (order.status) {
        HostingStatus.EXPIRED -> "已到期 · 待续期"
        HostingStatus.NOT_STARTED -> "未开始"
        HostingStatus.ACTIVE -> when {
            order.tasks.isEmpty() -> "今日无任务"
            order.allTasksCompleted -> "今日已完成"
            else -> "待完成"
        }
    }
}

/** Date range or打卡 progress; one line either way, no duplicated label. */
private fun hostingPeriodText(order: TodayHostingOrder): String = if (order.countBased) {
    "已打卡 ${order.completedSessions}/${order.targetSessions ?: 0} 次" +
        (order.remainingSessions?.let { " · 剩余 $it 次" } ?: "")
} else {
    "${formatGameDate(order.period.startDate)} — ${formatGameDate(order.period.endDate)}"
}

@Composable
private fun CompletedHostingCard(order: TodayHostingOrder, onOpen: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpen)
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(order.order.title, style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = if (order.countBased) {
                        "${order.ownerNickname} · ${order.gameName} · " +
                            "已打卡 ${order.completedSessions}/${order.targetSessions ?: 0} 次"
                    } else {
                        "${order.ownerNickname} · ${order.gameName} · 今日任务已全部完成"
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            StatusTag(text = "已完成")
        }
    }
}

@Composable
private fun ShortList(
    uiState: TodayUiState,
    onOpenOrder: (String) -> Unit,
    onToggleTask: (String, Boolean) -> Unit,
    onUpdateProgress: (String, String) -> Unit,
    groups: Map<String, String> = uiState.groups,
) {
    var overdueExpanded by rememberSaveable { mutableStateOf(false) }
    val visible = uiState.filteredShortOrders.filterNot { it.overdue }
    val overdue = uiState.filteredShortOrders.filter { it.overdue }
    // Same disclosure model as hosting: per-order identity, collapsed by default.
    val expansion = rememberExpansionState()
    LazyColumn(
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        if (uiState.filteredShortOrders.isEmpty()) {
            item {
                EmptyStateCard(
                    title = if (uiState.colorFilter == TodayColorFilter.All) {
                        "还没有短期任务"
                    } else {
                        "该颜色下没有短期任务"
                    },
                    description = if (uiState.colorFilter == TodayColorFilter.All) {
                        "暂时没有短期任务。"
                    } else {
                        "可点“全部”清除颜色筛选。"
                    },
                )
            }
        }
        visible.groupBy { groups[it.accountId]?.takeIf(String::isNotBlank) ?: "未分组" }
            .toSortedMap().forEach { (group, orders) ->
            if (groups.isNotEmpty()) {
                item(key = "short-group-$group") { SectionHeader(text = "分组 · $group") }
            }
            orders.sortedBy { it.order.title }.forEach { order ->
                item(key = "short-${order.order.id}") {
                    ShortOrderCard(
                        order = order,
                        expanded = expansion.isExpanded(order.order.id),
                        onToggle = { expansion.toggle(order.order.id) },
                        onOpen = { onOpenOrder(order.order.id) },
                        onToggleTask = onToggleTask,
                        onUpdateProgress = onUpdateProgress,
                    )
                }
            }
        }
        if (overdue.isNotEmpty()) {
            item(key = "short-overdue-header") {
                DisclosureHeader(
                    expanded = overdueExpanded,
                    onToggle = { overdueExpanded = !overdueExpanded },
                    tag = "today-overdue-header",
                ) {
                    Text(
                        text = "逾期事项 · ${overdue.size} 单",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            if (overdueExpanded) overdue.sortedBy { it.order.title }.forEach { order ->
                item(key = "overdue-${order.order.id}") {
                    ShortOrderCard(
                        order = order,
                        expanded = expansion.isExpanded(order.order.id),
                        onToggle = { expansion.toggle(order.order.id) },
                        onOpen = { onOpenOrder(order.order.id) },
                        onToggleTask = onToggleTask,
                        onUpdateProgress = onUpdateProgress,
                    )
                }
            }
        }
    }
}

@Composable
private fun ShortOrderCard(
    order: ShortWorkOrder,
    expanded: Boolean,
    onToggle: () -> Unit,
    onOpen: () -> Unit,
    onToggleTask: (String, Boolean) -> Unit,
    onUpdateProgress: (String, String) -> Unit,
) {
    val mark = OrderColors.mark(order.order.colorArgb)
    val doneCount = order.tasks.count { it.completed }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = OrderColors.tint(
                order.order.colorArgb, MaterialTheme.colorScheme.surface,
            ),
        ),
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            OrderColorStripe(mark = mark)
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 8.dp, end = 4.dp, top = 2.dp, bottom = if (expanded) 8.dp else 2.dp),
            ) {
                DisclosureHeader(
                    expanded = expanded,
                    onToggle = onToggle,
                    tag = "today-short-header-${order.order.id}",
                ) {
                    OrderColorDot(mark)
                    Text(
                        text = order.order.title,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = if (expanded) Int.MAX_VALUE else 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (order.tasks.isNotEmpty()) {
                        Text(
                            text = "$doneCount/${order.tasks.size}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    StatusTag(
                        text = when {
                            order.overdue -> "已逾期"
                            order.order.deadlineDate == null -> "无期限"
                            else -> "进行中"
                        },
                    )
                }

                if (expanded) {
                    Column(
                        modifier = Modifier.padding(end = 8.dp, bottom = 2.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        SecondaryText(
                            text = "${order.ownerNickname} · ${order.ownerNumber} · " +
                                "${order.gameName} · ${order.server}",
                        )
                        Text(
                            text = deadlineText(order),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (order.overdue) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                        order.tasks.forEach { task ->
                            CompletionRow(
                                title = task.task.title,
                                modifier = Modifier.heightIn(min = 48.dp),
                                checked = task.completed,
                                colorArgb = task.task.colorArgb ?: order.order.colorArgb,
                                onCheckedChange = { completed -> onToggleTask(task.task.id, completed) },
                                subtitle = if (task.task.progressType == ProgressType.NUMERIC) {
                                    (if (task.task.numericMode == NumericMode.CUMULATIVE) "累计 " else "进度 ") +
                                        task.currentValue.toPlainString() + "/" +
                                        task.task.targetValue.toPlainString() +
                                        (task.task.unit?.let { " $it" } ?: "")
                                } else {
                                    ""
                                },
                                trailing = {
                                    if (task.task.progressType == ProgressType.NUMERIC) {
                                        if (task.task.numericMode == NumericMode.CUMULATIVE) {
                                            TextButton(onClick = onOpen) { Text("记录累计") }
                                        } else {
                                            ProgressDialogButton(
                                                initialValue = task.currentValue.toPlainString(),
                                                onValue = { onUpdateProgress(task.task.id, it) },
                                            )
                                        }
                                    }
                                },
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "约定总价 " + (order.order.priceCents?.let(Money::formatCents) ?: "未填写"),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = onOpen) { Text("订单详情") }
                        }
                    }
                }
            }
        }
    }
}

/** Vivid side stripe of one order: the primary "which order is this" mark. */
@Composable
private fun OrderColorStripe(mark: Color) {
    Box(
        modifier = Modifier
            .width(5.dp)
            .fillMaxHeight()
            .background(mark),
    )
}

/** Small matching dot beside an order title, so the mark survives narrow layouts. */
@Composable
private fun OrderColorDot(mark: Color) {
    Box(
        modifier = Modifier
            .size(10.dp)
            .clip(CircleShape)
            .background(mark),
    )
}

@Composable
private fun ProgressDialogButton(initialValue: String, onValue: (String) -> Unit) {
    var show by remember { mutableStateOf(false) }
    TextButton(onClick = { show = true }) { Text("更新进度") }
    if (show) {
        var text by rememberSaveable { mutableStateOf(initialValue) }
        AlertDialog(
            onDismissRequest = { show = false },
            title = { Text("更新数值进度") },
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("当前值") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        show = false
                        onValue(text)
                    },
                ) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { show = false }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun UndoResultDialog(result: UndoResult, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("撤销结果") },
        text = {
            Column {
                Text(
                    if (result.alreadyUndone) {
                        "该批量操作已经撤销过。"
                    } else {
                        "已恢复 ${result.restoredCount} 项，批量前已完成的项不会被清除。"
                    },
                )
                if (result.conflicts.isNotEmpty()) {
                    Text(
                        text = "以下项目在批量完成后又被修改，已保留最新结果：",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    result.conflicts.forEach { conflict ->
                        Text(
                            text = "· ${conflict.description}：${conflict.reason}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("知道了") } },
    )
}

private fun deadlineText(order: ShortWorkOrder): String {
    val deadline = order.order.deadlineDate ?: return "无交付期限"
    val days = ChronoUnit.DAYS.between(order.today, deadline)
    return when {
        days < 0 -> "已逾期 ${-days} 天 · ${formatGameDate(deadline)} 截止"
        days == 0L -> "今天截止（${formatGameDate(deadline)}）"
        else -> "${formatShortDate(deadline)} 截止"
    }
}
