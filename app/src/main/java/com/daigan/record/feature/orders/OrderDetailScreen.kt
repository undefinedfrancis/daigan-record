package com.daigan.record.feature.orders

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.daigan.record.core.time.HostingPeriodSelection
import com.daigan.record.core.time.HostingStatus
import com.daigan.record.data.model.Attachment
import com.daigan.record.data.model.HostingPeriod
import com.daigan.record.data.model.HostingSession
import com.daigan.record.data.model.Money
import com.daigan.record.data.model.OrderBalance
import com.daigan.record.data.model.NumericMode
import com.daigan.record.data.model.OrderDetail
import com.daigan.record.data.model.OrderHistoryEntry
import com.daigan.record.data.model.OrderTask
import com.daigan.record.data.model.PaymentMode
import com.daigan.record.data.model.ProgressType
import com.daigan.record.data.model.Receipt
import com.daigan.record.data.model.ReceiptStatus
import com.daigan.record.data.model.UndoResult
import com.daigan.record.ui.components.CompletionRow
import com.daigan.record.ui.components.EmptyStateCard
import com.daigan.record.ui.components.LabeledValue
import com.daigan.record.ui.theme.OrderColors
import com.daigan.record.ui.components.PageHeader
import com.daigan.record.ui.components.ScreenshotStrip
import com.daigan.record.ui.components.SectionHeader
import com.daigan.record.ui.components.StatusTag
import com.daigan.record.ui.components.formatGameDate
import com.daigan.record.ui.components.formatInstant
import com.daigan.record.ui.components.formatShortDate
import java.io.File
import java.time.LocalDate
import java.util.UUID

@Composable
fun OrderDetailScreen(
    onBack: () -> Unit,
    onBackfill: (periodId: String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: OrderDetailViewModel = hiltViewModel(),
    /**
     * Opens the unified editor (UF-030). The former per-field dialogs are gone: every
     * correction is made on that one page with one save.
     */
    onOpenEditor: () -> Unit = {},
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var showRenewDialog by rememberSaveable { mutableStateOf(false) }
    var renewalOperationId by rememberSaveable { mutableStateOf(UUID.randomUUID().toString()) }
    var showArchiveOrderDialog by rememberSaveable { mutableStateOf(false) }
    var showCountRenewDialog by rememberSaveable { mutableStateOf(false) }
    var countRenewOperationId by rememberSaveable { mutableStateOf(UUID.randomUUID().toString()) }
    val cumulativePanel by viewModel.cumulative.collectAsStateWithLifecycle()

    // Payment/correction dialog state. Operation ids are created once per dialog and
    // survive a failed submission so a retry is idempotent.
    var showPaymentDialog by rememberSaveable { mutableStateOf(false) }
    var paymentPeriodId by rememberSaveable { mutableStateOf<String?>(null) }
    var paymentOperationId by rememberSaveable { mutableStateOf(UUID.randomUUID().toString()) }
    var amendReceiptId by rememberSaveable { mutableStateOf<String?>(null) }
    var correctionOperationId by rememberSaveable { mutableStateOf(UUID.randomUUID().toString()) }
    var voidReceiptId by rememberSaveable { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        viewModel.messages.collect { snackbar.showSnackbar(it) }
    }
    LaunchedEffect(Unit) {
        viewModel.renewalApplied.collect {
            showRenewDialog = false
            renewalOperationId = UUID.randomUUID().toString()
        }
    }
    LaunchedEffect(Unit) {
        viewModel.financialApplied.collect {
            showPaymentDialog = false
            amendReceiptId = null
            voidReceiptId = null
            paymentOperationId = UUID.randomUUID().toString()
            correctionOperationId = UUID.randomUUID().toString()
        }
    }
    LaunchedEffect(viewModel) {
        viewModel.maintenanceApplied.collect { deleted ->
            // Only a successful write closes a confirmation. Field edits happen on the
            // unified editor page, which owns its own draft and error state.
            showArchiveOrderDialog = false
            if (deleted) onBack()
        }
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            viewModel.refresh()
        }
    }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val state = uiState
        when {
            state.loading -> Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }

            state.error != null -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            ) {
                PageHeader(title = "订单详情", onBack = onBack)
                Box(modifier = Modifier.padding(16.dp)) {
                    EmptyStateCard(title = "无法读取订单", description = checkNotNull(state.error))
                }
            }

            state.detail != null -> OrderDetailContent(
                detail = checkNotNull(state.detail),
                lastBatchId = state.lastBatchId,
                undoResult = state.undoResult,
                onBack = onBack,
                onToggleShortTask = viewModel::toggleShortTask,
                onUpdateShortProgress = viewModel::updateShortProgress,
                onOpenCumulative = viewModel::openCumulative,
                onCompleteShortOrder = viewModel::completeShortOrder,
                onToggleOccurrence = viewModel::toggleOccurrence,
                onCalendarCompletion = viewModel::setCalendarCompletion,
                onUpdateOccurrenceProgress = viewModel::updateOccurrenceProgress,
                onCompleteHostingOrder = viewModel::completeHostingOrder,
                onUndo = viewModel::undoLastBatch,
                onClearUndoResult = viewModel::clearUndoResult,
                onAddAttachment = viewModel::addAttachment,
                onReplaceAttachment = viewModel::replaceAttachment,
                onDeleteAttachment = viewModel::deleteAttachment,
                fileFor = viewModel::fileFor,
                onBackfill = onBackfill,
                onReopenCountSession = viewModel::reopenCountSession,
                onOpenSessionCumulative = viewModel::openCumulativeHistory,
                retiredTasks = state.retiredTasks,
                onSetHistoricalRoundCompletion = viewModel::setHistoricalRoundCompletion,
                onRenewCountClick = {
                    countRenewOperationId = UUID.randomUUID().toString()
                    showCountRenewDialog = true
                },
                onRenewClick = {
                    renewalOperationId = UUID.randomUUID().toString()
                    showRenewDialog = true
                },
                onRecordPayment = { periodId ->
                    paymentPeriodId = periodId
                    paymentOperationId = UUID.randomUUID().toString()
                    showPaymentDialog = true
                },
                onAmendReceipt = { receipt ->
                    correctionOperationId = UUID.randomUUID().toString()
                    amendReceiptId = receipt.id
                },
                onVoidReceipt = { receipt ->
                    correctionOperationId = UUID.randomUUID().toString()
                    voidReceiptId = receipt.id
                },
                paymentInFlight = state.paymentInFlight,
                maintenanceInFlight = state.maintenanceInFlight,
                onOpenEditor = onOpenEditor,
                onDeleteUnusedOrder = viewModel::deleteOrder,
                onCancelOrder = viewModel::cancelOrder,
                onArchiveOrder = { showArchiveOrderDialog = true },
                onRestoreArchivedOrder = viewModel::restoreOrder,
                onDeleteCancelledOrder = viewModel::deleteCancelledOrder,
                onSetOrderColor = viewModel::setOrderColor,
                modifier = Modifier.padding(padding),
            )
        }
    }
    val detail = uiState.detail
    cumulativePanel?.let { panel ->
        CumulativeProgressDialog(
            panel = panel,
            onDismiss = viewModel::closeCumulative,
            onSubmit = viewModel::submitCumulativeTotal,
            onCorrect = viewModel::correctCumulativeEvent,
            onSelectSession = viewModel::selectCumulativeSession,
        )
    }
    if (showCountRenewDialog && detail != null) {
        CountRenewDialog(
            detail = detail,
            operationId = countRenewOperationId,
            onDismiss = { showCountRenewDialog = false },
            onConfirm = { targetText, priceText ->
                viewModel.renewCount(targetText, priceText, countRenewOperationId)
                showCountRenewDialog = false
            },
        )
    }
    if (showArchiveOrderDialog && detail != null) {
        val countBased = detail.countBased
        AlertDialog(
            onDismissRequest = { if (!uiState.maintenanceInFlight) showArchiveOrderDialog = false },
            title = { Text(if (countBased) "完成并归档此订单？" else "不续期并归档此订单？") },
            text = {
                Text(
                    if (countBased) {
                        // The mode has no calendar expiry, so the confirmation talks about
                        // the agreed rounds only.
                        "打卡式托管已完成 ${detail.completedSessionCount} / " +
                            "${detail.targetSessionCount ?: 0} 次约定次数。只归档「${detail.order.title}」，" +
                            "账号与其他订单不受影响。各轮打卡、截图、欠款和实收全部保留，" +
                            "可在账号的“查看已归档订单”中继续收款或恢复；未完成约定次数时归档会被拒绝。"
                    } else {
                        "只归档「${detail.order.title}」，账号与其他订单不受影响。各期价格、打卡、截图、欠款和实收全部保留，" +
                            "可在账号的“查看已归档订单”中继续收款或恢复。若仍有当前或未来期次，归档会被拒绝。"
                    },
                )
            },
            confirmButton = {
                TextButton(enabled = !uiState.maintenanceInFlight, onClick = viewModel::archiveOrder) {
                    Text(if (countBased) "完成并归档" else "不续期并归档")
                }
            },
            dismissButton = { TextButton(enabled = !uiState.maintenanceInFlight, onClick = { showArchiveOrderDialog = false }) { Text("取消") } },
        )
    }
    if (showRenewDialog && detail != null && detail.isHosting && !detail.order.cancelled) {
        RenewalDialog(
            detail = detail,
            operationId = renewalOperationId,
            onDismiss = { showRenewDialog = false },
            onConfirm = { operationId, days, price ->
                viewModel.renew(operationId, days, price)
            },
        )
    }

    if (showPaymentDialog && detail != null) {
        PaymentDialog(
            orderTitle = detail.order.title,
            ownerLabel = "${detail.owner.nickname} · ${detail.owner.displayNumber}",
            accountLabel = "${detail.account.gameName} · ${detail.account.server}",
            balance = detail.balance(),
            initialPeriodId = paymentPeriodId,
            now = viewModel.now(),
            operationId = paymentOperationId,
            inFlight = uiState.paymentInFlight,
            onDismiss = { if (!uiState.paymentInFlight) showPaymentDialog = false },
            onConfirm = { request ->
                viewModel.recordPayment(
                    periodId = request.periodId,
                    amountYuan = Money.centsToYuanText(request.amountCents),
                    receivedAt = request.receivedAt,
                    channel = request.channel,
                    note = request.note.orEmpty(),
                    operationId = request.operationId,
                )
            },
        )
    }

    val amendReceipt = amendReceiptId?.let { id -> detail?.receipts?.firstOrNull { it.id == id } }
    if (amendReceipt != null && detail != null) {
        AmendReceiptDialog(
            receipt = amendReceipt,
            periodLabel = periodLabelFor(detail, amendReceipt.periodId),
            inFlight = uiState.paymentInFlight,
            onDismiss = { if (!uiState.paymentInFlight) amendReceiptId = null },
            onConfirm = { amount, receivedAt, channel, note, reason ->
                viewModel.amendReceipt(
                    receiptId = amendReceipt.id,
                    amountYuan = amount,
                    receivedAt = receivedAt,
                    channel = channel,
                    note = note,
                    reason = reason,
                    operationId = correctionOperationId,
                )
            },
        )
    }

    val voidReceipt = voidReceiptId?.let { id -> detail?.receipts?.firstOrNull { it.id == id } }
    if (voidReceipt != null && detail != null) {
        VoidReceiptDialog(
            receipt = voidReceipt,
            periodLabel = periodLabelFor(detail, voidReceipt.periodId),
            inFlight = uiState.paymentInFlight,
            onDismiss = { if (!uiState.paymentInFlight) voidReceiptId = null },
            onConfirm = { reason ->
                viewModel.voidReceipt(
                    receiptId = voidReceipt.id,
                    reason = reason,
                    operationId = correctionOperationId,
                )
            },
        )
    }
}

private fun periodLabelFor(detail: OrderDetail, periodId: String?): String = when {
    periodId == null -> "短期订单"
    // The container row of 打卡式托管 has no business dates to show.
    detail.countBased -> "打卡式托管"
    else -> {
        val period = detail.periods.firstOrNull { it.id == periodId }
        if (period == null) {
            "托管期次"
        } else {
            "第 ${period.periodIndex + 1} 期 · ${formatGameDate(period.startDate)}–${formatGameDate(period.endDate)}"
        }
    }
}

/**
 * Direct order-colour row (UF-028).
 *
 * Every dot is a single tap that saves that colour for **this order**; "清除" removes it
 * and falls back to the neutral mark. No menu or dialog is required first, and the same
 * value drives the Today stripe, the Today dot and the Today colour filter.
 */
@Composable
private fun OrderColorPickerRow(
    current: Int?,
    enabled: Boolean,
    onSelect: (Int?) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 2.dp)
            .testTag("order-color-row"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = "颜色",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            modifier = Modifier
                .weight(1f)
                .horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OrderColors.palette.forEach { option ->
                val selected = current == option.argb
                Box(
                    modifier = Modifier
                        .size(22.dp)
                        .clip(CircleShape)
                        .background(Color(option.argb))
                        .then(
                            if (selected) {
                                Modifier.border(2.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                            } else {
                                Modifier
                            },
                        )
                        .clickable(enabled = enabled) { onSelect(option.argb) }
                        .semantics { contentDescription = "订单颜色${option.label}" }
                        .testTag("order-color-${option.argb}"),
                )
            }
        }
        TextButton(enabled = enabled && current != null, onClick = { onSelect(null) }) { Text("清除") }
    }
}

@Composable
internal fun OrderDetailContent(
    detail: OrderDetail,
    lastBatchId: String?,
    undoResult: UndoResult?,
    onBack: () -> Unit,
    onToggleShortTask: (String, Boolean) -> Unit,
    onUpdateShortProgress: (String, String) -> Unit,
    onOpenCumulative: (String) -> Unit = {},
    onCompleteShortOrder: () -> Unit,
    onToggleOccurrence: (String, String, Boolean) -> Unit,
    onUpdateOccurrenceProgress: (String, String, String) -> Unit,
    onCompleteHostingOrder: () -> Unit,
    onUndo: () -> Unit,
    onClearUndoResult: () -> Unit,
    onAddAttachment: (String?, String?, Uri) -> Unit,
    onReplaceAttachment: (String, Uri) -> Unit,
    onDeleteAttachment: (String) -> Unit,
    fileFor: (Attachment) -> File,
    onBackfill: (String) -> Unit,
    onRenewClick: () -> Unit,
    onRecordPayment: (periodId: String?) -> Unit,
    onAmendReceipt: (Receipt) -> Unit,
    onVoidReceipt: (Receipt) -> Unit,
    paymentInFlight: Boolean,
    modifier: Modifier = Modifier,
    onCalendarCompletion: ((String, String, java.time.LocalDate, String, Boolean) -> Unit)? = null,
    /** Opens the unified editor; the only field-correction entry (UF-030). */
    onOpenEditor: () -> Unit = {},
    /** Mistaken-order cleanup, kept outside the two-entry management section. */
    onDeleteUnusedOrder: () -> Unit = {},
    onCancelOrder: () -> Unit = {},
    onArchiveOrder: () -> Unit = {},
    onRestoreArchivedOrder: () -> Unit = {},
    onDeleteCancelledOrder: () -> Unit = {},
    onSetOrderColor: (Int?) -> Unit = {},
    maintenanceInFlight: Boolean = false,
    onReopenCountSession: (Int) -> Unit = {},
    /** Opens a task's cumulative history for one explicit session (read-only). */
    onOpenSessionCumulative: (String, String) -> Unit = { _, _ -> },
    onRenewCountClick: () -> Unit = {},
    /** Removed tasks of a count order, with the historical rounds they were due in. */
    retiredTasks: List<com.daigan.record.feature.orders.RetiredTaskHistory> = emptyList(),
    /** H1: complete/retract one historical round of a removed task. */
    onSetHistoricalRoundCompletion: (String, Int, Boolean) -> Unit = { _, _, _ -> },
) {
    val canWrite = !detail.order.archived && !detail.order.cancelled && !maintenanceInFlight && !detail.owner.archived && !detail.account.archived
    // Money stays writable for archived records: debt must remain collectible without
    // reactivating any archived task.
    val canPay = !paymentInFlight && !maintenanceInFlight
    var showManagement by rememberSaveable(detail.order.id) { mutableStateOf(false) }
    Column(modifier = modifier.fillMaxSize()) {
        PageHeader(
            title = detail.order.title,
            subtitle = "${detail.owner.nickname} · ${detail.owner.displayNumber} / " +
                "${detail.account.gameName} · ${detail.account.server}",
            onBack = onBack,
            actions = {
                Box {
                    TextButton(onClick = { showManagement = true }) { Text("管理") }
                    DropdownMenu(expanded = showManagement, onDismissRequest = { showManagement = false }) {
                        Box(modifier = Modifier.padding(12.dp)) {
                            OrderMaintenanceActions(
                                detail = detail,
                                onEdit = { showManagement = false; onOpenEditor() },
                                onCancel = { showManagement = false; onCancelOrder() },
                                onRestoreArchived = { showManagement = false; onRestoreArchivedOrder() },
                                onDeleteCancelled = { showManagement = false; onDeleteCancelledOrder() },
                                busy = maintenanceInFlight,
                            )
                        }
                    }
                }
            },
        )
        // Colour dots sit at the very top: one tap recolours this order, no menu first.
        OrderColorPickerRow(
            current = detail.order.colorArgb,
            enabled = !maintenanceInFlight,
            onSelect = onSetOrderColor,
        )
        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize().testTag("order-detail-list"),
        ) {
            item {
                OrderSummaryCard(detail = detail)
            }
            item {
                UnusedOrderCleanupRow(
                    detail = detail,
                    busy = maintenanceInFlight,
                    onDelete = onDeleteUnusedOrder,
                )
            }

            if (detail.isHosting) {
                // The calendar is a date-based surface; count-based hosting has no date
                // grid or backfill entry by design.
                if (!detail.countBased) {
                    item {
                        HostingCalendarCard(
                            detail = detail,
                            enabled = canWrite,
                            onToggleOccurrence = onToggleOccurrence,
                            onUpdateOccurrenceProgress = onUpdateOccurrenceProgress,
                            onCalendarCompletion = onCalendarCompletion,
                        )
                    }
                }
                hostingSections(
                    detail = detail,
                    canWrite = canWrite,
                    lastBatchId = lastBatchId,
                    onToggleOccurrence = onToggleOccurrence,
                    onUpdateOccurrenceProgress = onUpdateOccurrenceProgress,
                    onOpenCumulative = onOpenCumulative,
                    onCompleteHostingOrder = onCompleteHostingOrder,
                    onUndo = onUndo,
                    onAddAttachment = onAddAttachment,
                    onReplaceAttachment = onReplaceAttachment,
                    onDeleteAttachment = onDeleteAttachment,
                    fileFor = fileFor,
                    onBackfill = onBackfill,
                    onRenewClick = onRenewClick,
                    onArchiveOrder = onArchiveOrder,
                    onRestoreArchivedOrder = onRestoreArchivedOrder,
                    onRecordPayment = onRecordPayment,
                    onAmendReceipt = onAmendReceipt,
                    onVoidReceipt = onVoidReceipt,
                    canPay = canPay,
                    onReopenCountSession = onReopenCountSession,
                    onOpenSessionCumulative = onOpenSessionCumulative,
                    onRenewCountClick = onRenewCountClick,
                    retiredTasks = retiredTasks,
                    onSetHistoricalRoundCompletion = onSetHistoricalRoundCompletion,
                )
            } else {
                shortSections(
                    detail = detail,
                    canWrite = canWrite,
                    lastBatchId = lastBatchId,
                    onToggleShortTask = onToggleShortTask,
                    onUpdateShortProgress = onUpdateShortProgress,
                    onOpenCumulative = onOpenCumulative,
                    onCompleteShortOrder = onCompleteShortOrder,
                    onUndo = onUndo,
                    onAddAttachment = onAddAttachment,
                    onReplaceAttachment = onReplaceAttachment,
                    onDeleteAttachment = onDeleteAttachment,
                    fileFor = fileFor,
                    onRecordPayment = onRecordPayment,
                    onAmendReceipt = onAmendReceipt,
                    onVoidReceipt = onVoidReceipt,
                    canPay = canPay,
                )
            }

        }
    }

    undoResult?.let { result ->
        AlertDialog(
            onDismissRequest = onClearUndoResult,
            title = { Text("撤销结果") },
            text = {
                Column {
                    Text(
                        if (result.alreadyUndone) {
                            "该批量操作已经撤销过。"
                        } else {
                            "已恢复 ${result.restoredCount} 项。"
                        },
                    )
                    if (result.conflicts.isNotEmpty()) {
                        Text(
                            text = "以下记录在批量完成后又被修改，已保留最新结果：",
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
            confirmButton = {
                TextButton(onClick = onClearUndoResult) { Text("知道了") }
            },
        )
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.hostingSections(
    detail: OrderDetail,
    canWrite: Boolean,
    lastBatchId: String?,
    onToggleOccurrence: (String, String, Boolean) -> Unit,
    onUpdateOccurrenceProgress: (String, String, String) -> Unit,
    onOpenCumulative: (String) -> Unit,
    onCompleteHostingOrder: () -> Unit,
    onUndo: () -> Unit,
    onAddAttachment: (String?, String?, Uri) -> Unit,
    onReplaceAttachment: (String, Uri) -> Unit,
    onDeleteAttachment: (String) -> Unit,
    fileFor: (Attachment) -> File,
    onBackfill: (String) -> Unit,
    onRenewClick: () -> Unit,
    onArchiveOrder: () -> Unit,
    onRestoreArchivedOrder: () -> Unit,
    onRecordPayment: (periodId: String?) -> Unit,
    onAmendReceipt: (Receipt) -> Unit,
    onVoidReceipt: (Receipt) -> Unit,
    canPay: Boolean,
    onReopenCountSession: (Int) -> Unit = {},
    onOpenSessionCumulative: (String, String) -> Unit = { _, _ -> },
    onRenewCountClick: () -> Unit = {},
    retiredTasks: List<com.daigan.record.feature.orders.RetiredTaskHistory> = emptyList(),
    onSetHistoricalRoundCompletion: (String, Int, Boolean) -> Unit = { _, _, _ -> },
) {
    if (detail.countBased) {
        countBasedHostingSections(
            detail = detail,
            canWrite = canWrite,
            lastBatchId = lastBatchId,
            onToggleOccurrence = onToggleOccurrence,
            onUpdateOccurrenceProgress = onUpdateOccurrenceProgress,
            onOpenCumulative = onOpenCumulative,
            onCompleteHostingOrder = onCompleteHostingOrder,
            onUndo = onUndo,
            onAddAttachment = onAddAttachment,
            onReplaceAttachment = onReplaceAttachment,
            onDeleteAttachment = onDeleteAttachment,
            fileFor = fileFor,
            onRestoreArchivedOrder = onRestoreArchivedOrder,
            onRecordPayment = onRecordPayment,
            onAmendReceipt = onAmendReceipt,
            onVoidReceipt = onVoidReceipt,
            canPay = canPay,
            onReopenCountSession = onReopenCountSession,
            onOpenSessionCumulative = onOpenSessionCumulative,
            onRenewCountClick = onRenewCountClick,
            onArchiveOrder = onArchiveOrder,
            retiredTasks = retiredTasks,
            onSetHistoricalRoundCompletion = onSetHistoricalRoundCompletion,
        )
        return
    }
    val currentPeriod = detail.currentPeriod
    val status = detail.currentStatus()
    val currentTasks = detail.currentHostingTasks()

    item {
        if (currentPeriod == null) {
            EmptyStateCard(title = "托管期次缺失", description = "该订单没有可用的托管期次。")
        } else {
            PeriodCard(
                period = currentPeriod,
                status = status,
                isCurrent = true,
                today = detail.today,
                // Only a running period is "本期". A future row (early renewal) or the
                // last ended row must be labelled for what it is.
                title = when (status) {
                    HostingStatus.ACTIVE -> "本期托管"
                    HostingStatus.NOT_STARTED -> "即将开始的托管期"
                    else -> "最近一期托管（已结束）"
                },
            )
        }
    }

    if (detail.order.archived) {
        item {
            Text("已归档 · 不续期", style = MaterialTheme.typography.titleMedium)
            Text("原服务日期、欠款、实收与历史记录保留；可在下方继续收款。恢复后可更正记录或续期。")
            TextButton(onClick = onRestoreArchivedOrder, enabled = !detail.owner.archived && !detail.account.archived) { Text("恢复订单") }
        }
    } else if (status == HostingStatus.EXPIRED) {
        item {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                ),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "已到期 · 待续期",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                    Text(
                        text = "到期后不再生成新任务；历史打卡、截图与期次价格全部保留，可继续补录或查看。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                    Button(
                        onClick = onRenewClick,
                        enabled = canWrite,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                    ) { Text("续期") }
                    TextButton(onClick = onArchiveOrder, enabled = canWrite) { Text("不续期并归档") }
                }
            }
        }
    } else if (status == HostingStatus.ACTIVE) {
        item {
            Button(
                onClick = onRenewClick,
                enabled = canWrite,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("提前续期") }
            Text(
                text = "提前续期将从当前期结束后的次日开始，不会与本期重叠。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    item {
        SectionHeader(text = "今日打卡")
        if (status == HostingStatus.NOT_STARTED) {
            Text(
                text = "托管尚未开始，不会提前生成任务。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (status == HostingStatus.ACTIVE) {
        if (currentTasks.isEmpty()) {
            item {
                Text(
                    text = "今天没有需要完成的托管任务。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        currentTasks.forEach { item ->
            item(key = "current-${item.task.id}") {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        CompletionRow(
                            title = item.task.title,
                            checked = item.completed,
                            enabled = canWrite,
                            colorArgb = item.task.colorArgb ?: detail.order.colorArgb,
                            onCheckedChange = {
                                onToggleOccurrence(item.task.id, item.slot.key, it)
                            },
                            subtitle = taskSubtitle(item.task, item.windowLabel, item.value),
                            trailing = {
                                when {
                                    item.task.progressType != ProgressType.NUMERIC -> Unit
                                    item.task.numericMode == NumericMode.CUMULATIVE ->
                                        CumulativeOpenButton(
                                            enabled = canWrite,
                                            onClick = { onOpenCumulative(item.task.id) },
                                        )
                                    else -> NumericEditButton(
                                        initialValue = (item.value ?: item.task.currentValue).toPlainString(),
                                        enabled = canWrite,
                                        onValue = {
                                            onUpdateOccurrenceProgress(
                                                item.task.id,
                                                item.slot.key,
                                                it,
                                            )
                                        },
                                    )
                                }
                            },
                        )
                        if (item.occurrenceId != null) {
                            ScreenshotStrip(
                                attachments = detail.attachmentsForOccurrence(item.occurrenceId),
                                fileFor = fileFor,
                                enabled = canWrite,
                                onAdd = { uri -> onAddAttachment(null, item.occurrenceId, uri) },
                                onReplace = { attachment, uri ->
                                    onReplaceAttachment(attachment.id, uri)
                                },
                                onDelete = { attachment -> onDeleteAttachment(attachment.id) },
                                modifier = Modifier.padding(top = 8.dp),
                                emptyLabel = "本次打卡还没有截图。",
                            )
                        } else {
                            Text(
                                text = "勾选完成后可添加本次打卡截图。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onCompleteHostingOrder,
                    enabled = canWrite && currentTasks.any { !it.completed },
                ) { Text("整单完成") }
                if (lastBatchId != null) {
                    TextButton(onClick = onUndo) { Text("撤销本次批量完成") }
                }
            }
        }
    }

    item {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "本期打卡记录",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            if (currentPeriod != null && canWrite) {
                TextButton(onClick = { onBackfill(currentPeriod.id) }) {
                    Text("补录/更正")
                }
            }
        }
    }

    if (currentPeriod != null) {
        val entries = detail.historyEntries().filter { it.periodId == currentPeriod.id }
        if (entries.isEmpty()) {
            item {
                Text(
                    text = "本期暂无打卡记录。批量完成不会补写历史遗漏。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        entries.forEach { entry ->
            // Keyed by occurrence, not by task+date: two weekly occurrences can share the
            // period's first date, and a repeated LazyColumn key crashes the list.
            item(key = "history-${entry.stableKey}") {
                HistoryCard(
                    entry = entry,
                    canWrite = canWrite,
                    onAddAttachment = onAddAttachment,
                    onReplaceAttachment = onReplaceAttachment,
                    onDeleteAttachment = onDeleteAttachment,
                    fileFor = fileFor,
                )
            }
        }
    }

    val future = detail.futurePeriods
    if (future.isNotEmpty()) {
        item {
            HorizontalDivider()
            SectionHeader(text = "未来期次（已提前续期）")
            Text(
                text = "这些期次尚未开始，不计入今日任务、日历可执行项和提醒；" +
                    "每期价格与收款单独记录，开始后才会生成应做任务。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        future.forEach { period ->
            item(key = "future-period-${period.id}") {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "第 ${period.periodIndex + 1} 期 · 未开始",
                            style = MaterialTheme.typography.titleSmall,
                        )
                        LabeledValue(
                            label = "服务日期（含首尾）",
                            value = "${formatGameDate(period.startDate)} – ${formatGameDate(period.endDate)}" +
                                " · ${period.durationDays} 天",
                        )
                        LabeledValue(label = "本期约定价格", value = Money.formatCents(period.priceCents))
                        Text(
                            text = "该期收款可在「收款与欠款」中选择本期次单独记录。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }

    val previous = detail.previousPeriods
    if (previous.isNotEmpty()) {
        item {
            HorizontalDivider()
            SectionHeader(text = "往期记录")
        }
        previous.forEach { period ->
            item(key = "period-${period.id}") {
                PreviousPeriodCard(
                    period = period,
                    tasks = detail.tasks.filter { it.periodId == period.id },
                    entries = detail.historyEntries().filter { it.periodId == period.id },
                    canWrite = canWrite,
                    onBackfill = onBackfill,
                    onAddAttachment = onAddAttachment,
                    onReplaceAttachment = onReplaceAttachment,
                    onDeleteAttachment = onDeleteAttachment,
                    fileFor = fileFor,
                )
            }
        }
    }

    item {
        PaymentSection(
            detail = detail,
            canPay = canPay,
            onRecordPayment = onRecordPayment,
            onAmendReceipt = onAmendReceipt,
            onVoidReceipt = onVoidReceipt,
        )
    }
}

/**
 * Detail page of 打卡式托管 (UF-033).
 *
 * Deliberately no period card, remaining-days line, expiry banner, renewal button or
 * date backfill entry: this mode has no start/end date, never expires and is completed
 * only by reaching the agreed number of rounds. Everything else (tasks, screenshots,
 * receipts, undo) works like the existing hosting flow.
 */
private fun androidx.compose.foundation.lazy.LazyListScope.countBasedHostingSections(
    detail: OrderDetail,
    canWrite: Boolean,
    lastBatchId: String?,
    onToggleOccurrence: (String, String, Boolean) -> Unit,
    onUpdateOccurrenceProgress: (String, String, String) -> Unit,
    onOpenCumulative: (String) -> Unit,
    onCompleteHostingOrder: () -> Unit,
    onUndo: () -> Unit,
    onAddAttachment: (String?, String?, Uri) -> Unit,
    onReplaceAttachment: (String, Uri) -> Unit,
    onDeleteAttachment: (String) -> Unit,
    fileFor: (Attachment) -> File,
    onRestoreArchivedOrder: () -> Unit,
    onRecordPayment: (periodId: String?) -> Unit,
    onAmendReceipt: (Receipt) -> Unit,
    onVoidReceipt: (Receipt) -> Unit,
    canPay: Boolean,
    onReopenCountSession: (Int) -> Unit,
    onOpenSessionCumulative: (String, String) -> Unit = { _, _ -> },
    onRenewCountClick: () -> Unit = {},
    onArchiveOrder: () -> Unit,
    retiredTasks: List<com.daigan.record.feature.orders.RetiredTaskHistory> = emptyList(),
    onSetHistoricalRoundCompletion: (String, Int, Boolean) -> Unit = { _, _, _ -> },
) {
    val target = detail.targetSessionCount ?: 0
    val completed = detail.completedSessionCount
    val remaining = detail.remainingSessionCount ?: 0

    item {
        Card(
            modifier = Modifier.testTag("order-count-progress"),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "打卡进度",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    StatusTag(text = if (detail.countSessionsComplete) "已完成" else "打卡中")
                }
                Text(
                    text = "已打卡 $completed / $target 次",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 8.dp),
                )
                Text(
                    text = "剩余 $remaining 次 · 同一天可以完成多轮",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "按实际完成的轮次累计；每轮全部任务完成才计 1 次，" +
                        "没打卡的日子不扣次数，也不会因此到期。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
                if (!detail.order.archived && !detail.order.cancelled) {
                    TextButton(
                        onClick = onRenewCountClick,
                        enabled = canWrite,
                        modifier = Modifier.testTag("count-renew"),
                    ) { Text("追加期次（继续接单）") }
                    Text(
                        text = "新增一期自己的次数、价格与任务；历史期次、收款与轮次全部保留。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (detail.order.archived) {
                    Text(
                        text = "已归档 · 历史记录、收款与截图保留",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    TextButton(
                        onClick = onRestoreArchivedOrder,
                        enabled = !detail.owner.archived && !detail.account.archived,
                    ) { Text("恢复订单") }
                }
            }
        }
    }

    if (detail.countSessionsComplete) {
        item {
            Text(
                text = "已达成本次约定的打卡次数，无需继续打卡。历史轮次、收款与截图全部保留。",
                style = MaterialTheme.typography.bodyMedium,
            )
            if (!detail.order.archived) {
                // No calendar expiry exists in this mode: reaching the agreed count is the
                // completion signal. The repository re-checks that before archiving.
                Button(
                    onClick = onArchiveOrder,
                    enabled = canWrite,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                        .testTag("count-complete-archive"),
                ) { Text("完成并归档") }
                Text(
                    text = "归档后不再打卡；历史轮次、欠款、实收与截图仍可查看，也可恢复订单。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    } else {
        val currentTasks = detail.currentHostingTasks()
        item {
            Text(
                text = "第 ${detail.activeSessionIndex ?: (completed + 1)} 次打卡",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = "本轮全部任务完成后计 1 次；下一轮从空白进度开始，上一轮记录保留。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (currentTasks.isEmpty()) {
            item {
                Text(
                    text = "该订单没有可打卡的任务。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        currentTasks.forEach { item ->
            item(key = "count-current-${item.task.id}") {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        CompletionRow(
                            title = item.task.title,
                            checked = item.completed,
                            enabled = canWrite,
                            colorArgb = item.task.colorArgb ?: detail.order.colorArgb,
                            onCheckedChange = {
                                onToggleOccurrence(item.task.id, item.slot.key, it)
                            },
                            subtitle = taskSubtitle(item.task, item.windowLabel, item.value),
                            trailing = {
                                when {
                                    item.task.progressType != ProgressType.NUMERIC -> Unit
                                    item.task.numericMode == NumericMode.CUMULATIVE ->
                                        CumulativeOpenButton(
                                            enabled = canWrite,
                                            onClick = { onOpenCumulative(item.task.id) },
                                        )
                                    else -> NumericEditButton(
                                        initialValue = (item.value ?: item.task.currentValue).toPlainString(),
                                        enabled = canWrite,
                                        onValue = {
                                            onUpdateOccurrenceProgress(
                                                item.task.id,
                                                item.slot.key,
                                                it,
                                            )
                                        },
                                    )
                                }
                            },
                        )
                        if (item.occurrenceId != null) {
                            ScreenshotStrip(
                                attachments = detail.attachmentsForOccurrence(item.occurrenceId),
                                fileFor = fileFor,
                                enabled = canWrite,
                                onAdd = { uri -> onAddAttachment(null, item.occurrenceId, uri) },
                                onReplace = { attachment, uri ->
                                    onReplaceAttachment(attachment.id, uri)
                                },
                                onDelete = { attachment -> onDeleteAttachment(attachment.id) },
                                modifier = Modifier.padding(top = 8.dp),
                                emptyLabel = "本次打卡还没有截图。",
                            )
                        } else {
                            Text(
                                text = "勾选完成后可添加本次打卡截图。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onCompleteHostingOrder,
                    enabled = canWrite && currentTasks.any { !it.completed },
                ) { Text("本轮完成") }
                if (lastBatchId != null) {
                    TextButton(onClick = onUndo) { Text("撤销本次批量完成") }
                }
            }
        }
    }

    item {
        HorizontalDivider()
        SectionHeader(text = "打卡轮次记录")
        Text(
            text = "每轮全部任务完成计 1 次；撤销或更正会重新打开对应轮次，不会静默丢失历史。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (detail.sessions.isEmpty()) {
        item {
            Text(
                text = "还没有打卡轮次记录。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    } else {
        detail.sessions.sortedByDescending { it.sessionIndex }.forEach { session ->
            item(key = "count-session-${session.id}") {
                // A cumulative task that was removed later still owns this round's
                // history, so its entry must not disappear with the current list (H1).
                val currentCumulative = detail.tasksForPeriod(session.periodId)
                    .firstOrNull { it.isCumulativeNumeric() }
                val removedCumulative = detail.tasks.firstOrNull {
                    it.periodId == session.periodId && it.retiredAt != null &&
                        it.isCumulativeNumeric()
                }
                val cumulativeTask = currentCumulative ?: removedCumulative
                val cumulativeWasDue = cumulativeTask != null ||
                    retiredTasks.any { retired ->
                        retired.cumulative && retired.rounds.any { it.sessionId == session.id }
                    }
                SessionCard(
                    session = session,
                    canReopen = canWrite && session.completed &&
                        // A cumulative round is corrected through its event history, not
                        // by clearing occurrences (which would derive "complete" again).
                        !cumulativeWasDue,
                    onReopen = { onReopenCountSession(session.sessionIndex) },
                    onOpenCumulative = cumulativeTask?.let { task ->
                        { onOpenSessionCumulative(task.id, session.id) }
                    },
                )
            }
        }
    }

    if (retiredTasks.isNotEmpty()) {
        item {
            HorizontalDivider()
            SectionHeader(text = "已移除任务（历史）")
            Text(
                text = "被移除的任务不再出现在当前打卡列表；但被移除当天及以前的轮次里它仍然算应做。" +
                    "下面可以补记、撤销或更正这些历史记录，轮次归属日期不会改变。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        retiredTasks.forEach { retired ->
            item(key = "retired-task-${retired.taskId}") {
                RetiredTaskHistoryCard(
                    retired = retired,
                    canWrite = canWrite,
                    onOpenCumulative = { sessionId ->
                        onOpenSessionCumulative(retired.taskId, sessionId)
                    },
                    onSetRoundCompletion = { sessionIndex, completed ->
                        onSetHistoricalRoundCompletion(retired.taskId, sessionIndex, completed)
                    },
                )
            }
        }
    }

    val entries = detail.historyEntries()
    if (entries.isNotEmpty()) {
        item {
            SectionHeader(text = "历史打卡明细")
            Text(
                text = "包含每轮任务的完成时间与截图。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        entries.forEach { entry ->
            item(key = "count-history-${entry.stableKey}") {
                HistoryCard(
                    entry = entry,
                    canWrite = canWrite,
                    onAddAttachment = onAddAttachment,
                    onReplaceAttachment = onReplaceAttachment,
                    onDeleteAttachment = onDeleteAttachment,
                    fileFor = fileFor,
                )
            }
        }
    }

    item {
        PaymentSection(
            detail = detail,
            canPay = canPay,
            onRecordPayment = onRecordPayment,
            onAmendReceipt = onAmendReceipt,
            onVoidReceipt = onVoidReceipt,
        )
    }
}

@Composable
private fun SessionCard(
    session: HostingSession,
    canReopen: Boolean,
    onReopen: () -> Unit,
    onOpenCumulative: (() -> Unit)? = null,
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "第 ${session.sessionIndex} 次打卡",
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = if (session.completed) {
                        "完成于 " + java.time.format.DateTimeFormatter
                            .ofPattern("M 月 d 日 HH:mm")
                            .withZone(java.time.ZoneId.systemDefault())
                            .format(session.completedAt)
                    } else {
                        "未计入 · 本轮任务尚未全部完成"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            onOpenCumulative?.let { open ->
                TextButton(
                    onClick = open,
                    modifier = Modifier.testTag("count-session-cumulative-${session.sessionIndex}"),
                ) { Text("累计进度") }
            }
            if (canReopen) {
                // Explicit correction path: reopen this round (total drops by one, the
                // round row stays), instead of silently deleting history.
                TextButton(
                    onClick = onReopen,
                    modifier = Modifier.testTag("count-session-reopen-${session.sessionIndex}"),
                ) { Text("撤销本轮") }
            }
            StatusTag(text = if (session.completed) "已计 1 次" else "未计入")
        }
    }
}

/**
 * H1: historical surface of a removed task.
 *
 * A retired task keeps its responsibility for the rounds it was still due in, so those
 * rounds must stay correctable instead of leaving the round permanently incomplete. Each
 * row names the round, its own attributed game date and the recorded state; a cumulative
 * task opens its append-only event history, an ordinary task toggles that round's
 * completion. Neither path can move the record to another round, task or date.
 */
@Composable
private fun RetiredTaskHistoryCard(
    retired: com.daigan.record.feature.orders.RetiredTaskHistory,
    canWrite: Boolean,
    onOpenCumulative: (String) -> Unit,
    onSetRoundCompletion: (Int, Boolean) -> Unit,
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(
            modifier = Modifier
                .padding(12.dp)
                .testTag("retired-task-${retired.taskId}"),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = retired.title,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                StatusTag(text = "已移除")
            }
            Text(
                text = buildString {
                    append("移除于 ")
                    append(
                        retired.retiredAt?.let { formatInstant(it) } ?: "未知时间",
                    )
                    append(" · 仍计入该日及以前的轮次")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (retired.cumulative) {
                Text(
                    text = "累计任务：用「累计历史」查看并更正该轮记录（留空即撤销）。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (retired.rounds.isEmpty()) {
                Text(
                    text = "还没有需要它的历史轮次。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            retired.rounds.forEach { round ->
                if (retired.cumulative) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "第 ${round.sessionIndex} 次 · ${formatGameDate(round.attributedDate)}" +
                                if (round.hasObservation) " · 已有记录" else " · 暂无记录",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(
                            onClick = { onOpenCumulative(round.sessionId) },
                            modifier = Modifier.testTag("retired-cumulative-${round.sessionIndex}"),
                        ) { Text("累计历史") }
                    }
                } else {
                    CompletionRow(
                        title = "第 ${round.sessionIndex} 次 · ${formatGameDate(round.attributedDate)}",
                        checked = round.completed,
                        enabled = canWrite,
                        modifier = Modifier.testTag("retired-round-${round.sessionIndex}"),
                        subtitle = if (round.completed) "已记录" else "该轮次当时应做，尚未记录",
                        onCheckedChange = { onSetRoundCompletion(round.sessionIndex, it) },
                    )
                }
            }
        }
    }
}

/** A cumulative numeric task (UF-038). */
private fun com.daigan.record.data.model.OrderTask.isCumulativeNumeric(): Boolean =
    progressType == ProgressType.NUMERIC && numericMode == NumericMode.CUMULATIVE

private fun androidx.compose.foundation.lazy.LazyListScope.shortSections(
    detail: OrderDetail,
    canWrite: Boolean,
    lastBatchId: String?,
    onToggleShortTask: (String, Boolean) -> Unit,
    onUpdateShortProgress: (String, String) -> Unit,
    onOpenCumulative: (String) -> Unit,
    onCompleteShortOrder: () -> Unit,
    onUndo: () -> Unit,
    onAddAttachment: (String?, String?, Uri) -> Unit,
    onReplaceAttachment: (String, Uri) -> Unit,
    onDeleteAttachment: (String) -> Unit,
    fileFor: (Attachment) -> File,
    onRecordPayment: (periodId: String?) -> Unit,
    onAmendReceipt: (Receipt) -> Unit,
    onVoidReceipt: (Receipt) -> Unit,
    canPay: Boolean,
) {
    item {
        Card(
            modifier = Modifier.testTag("order-detail-summary"),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = if (detail.order.completed) "已完成" else "进行中",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    StatusTag(text = if (detail.order.completed) "完成" else "未完成")
                }
                LabeledValue(
                    label = "约定总价",
                    value = detail.order.priceCents?.let(Money::formatCents) ?: "未填写",
                    modifier = Modifier.padding(top = 8.dp),
                )
                LabeledValue(
                    label = "交付期限",
                    value = detail.order.deadlineDate?.let(::formatGameDate) ?: "无期限",
                )
                LabeledValue(
                    label = "收款方式",
                    value = when (detail.order.paymentMode) {
                        PaymentMode.ONE_TIME -> "一次结清"
                        PaymentMode.INSTALLMENTS -> "分批收款"
                    },
                )
                if (!detail.order.notes.isNullOrBlank()) {
                    LabeledValue(label = "备注", value = detail.order.notes.orEmpty())
                }
            }
        }
    }

    item {
        SectionHeader(text = "任务与进度")
    }

    detail.tasksForPeriod(null).forEach { task ->
        item(key = "short-${task.id}") {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(modifier = Modifier.padding(12.dp)) {
                    CompletionRow(
                        title = task.title,
                        checked = task.completed,
                        enabled = canWrite,
                        colorArgb = task.colorArgb ?: detail.order.colorArgb,
                        onCheckedChange = { onToggleShortTask(task.id, it) },
                        subtitle = numericSubtitle(task),
                        trailing = {
                            when {
                                task.progressType != ProgressType.NUMERIC -> Unit
                                task.numericMode == NumericMode.CUMULATIVE ->
                                    CumulativeOpenButton(
                                        enabled = canWrite,
                                        onClick = { onOpenCumulative(task.id) },
                                    )
                                else -> NumericEditButton(
                                    initialValue = task.currentValue.toPlainString(),
                                    enabled = canWrite,
                                    onValue = { onUpdateShortProgress(task.id, it) },
                                )
                            }
                        },
                    )
                    ScreenshotStrip(
                        attachments = detail.attachmentsForTask(task.id),
                        fileFor = fileFor,
                        enabled = canWrite,
                        onAdd = { uri -> onAddAttachment(task.id, null, uri) },
                        onReplace = { attachment, uri -> onReplaceAttachment(attachment.id, uri) },
                        onDelete = { attachment -> onDeleteAttachment(attachment.id) },
                        modifier = Modifier.padding(top = 4.dp),
                        emptyLabel = "该任务还没有截图。",
                    )
                }
            }
        }
    }

    item {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = onCompleteShortOrder,
                enabled = canWrite && detail.tasksForPeriod(null).any { !it.completed },
            ) { Text("整单完成") }
            if (lastBatchId != null) {
                TextButton(onClick = onUndo) { Text("撤销本次批量完成") }
            }
        }
    }

    item {
        SectionHeader(text = "完成记录")
    }
    val retiredTasks = detail.tasks.filter { it.periodId == null && it.retiredAt != null }
    if (retiredTasks.isNotEmpty()) {
        item { SectionHeader(text = "已更正或移除的任务记录") }
        retiredTasks.forEach { task ->
            item(key = "retired-${task.id}") {
                Card {
                    Column(Modifier.padding(12.dp)) {
                        Text(task.title)
                        Text("保留进度 ${task.currentValue.toPlainString()} / ${task.targetValue.toPlainString()} ${task.unit.orEmpty()} · ${if (task.completed) "已完成" else "历史定义"}")
                        ScreenshotStrip(attachments = detail.attachmentsForTask(task.id), fileFor = fileFor,
                            onAdd = { uri -> onAddAttachment(task.id, null, uri) }, onReplace = { a, uri -> onReplaceAttachment(a.id, uri) },
                            onDelete = { a -> onDeleteAttachment(a.id) }, enabled = canWrite, emptyLabel = "暂无历史截图")
                    }
                }
            }
        }
    }
    val completed = detail.tasksForPeriod(null).filter { it.completed }
    if (completed.isEmpty()) {
        item {
            Text(
                text = "还没有已完成的任务。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    } else {
        completed.forEach { task ->
            item(key = "short-history-${task.id}") {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(task.title, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            text = task.completedAt?.let { formatInstant(it) } ?: "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }

    item {
        PaymentSection(
            detail = detail,
            canPay = canPay,
            onRecordPayment = onRecordPayment,
            onAmendReceipt = onAmendReceipt,
            onVoidReceipt = onVoidReceipt,
        )
    }
}

@Composable
private fun OrderSummaryCard(detail: OrderDetail) {
    Card(
        modifier = Modifier.testTag("order-detail-summary"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "订单详情",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                StatusTag(
                    text = when {
                        detail.order.cancelled -> "已作废"
                        detail.countBased && detail.countSessionsComplete -> "已完成"
                        detail.countBased -> "打卡中"
                        detail.order.isShort && detail.order.completed -> "已完成"
                        detail.order.isShort -> "进行中"
                        detail.currentStatus() == HostingStatus.EXPIRED -> "已到期"
                        detail.currentStatus() == HostingStatus.NOT_STARTED -> "未开始"
                        else -> "托管中"
                    },
                )
            }
            LabeledValue(
                label = "游戏账号",
                value = "${detail.account.gameName} · ${detail.account.server} · ${detail.account.uid}",
                modifier = Modifier.padding(top = 8.dp),
            )
            if (detail.countBased) {
                // No business date range exists: show the agreed and remaining rounds
                // instead of a period/duration that the mode never agreed to.
                LabeledValue(
                    label = "打卡目标",
                    value = "${detail.targetSessionCount ?: 0} 次",
                    modifier = Modifier.padding(top = 8.dp),
                )
                LabeledValue(
                    label = "已完成 / 剩余",
                    value = "${detail.completedSessionCount} 次 / " +
                        "${detail.remainingSessionCount ?: 0} 次",
                )
                LabeledValue(
                    label = "约定价格",
                    value = Money.formatCents(detail.periods.lastOrNull()?.priceCents ?: 0L),
                )
                Text(
                    text = "打卡式托管 · 无起止日期，按实际完成轮次累计；未打卡的日子不扣次数。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            val period = detail.currentPeriod
            if (period != null && !detail.countBased) {
                LabeledValue(
                    label = "本期期限（含首尾）",
                    value = "${formatGameDate(period.startDate)} – ${formatGameDate(period.endDate)}" +
                        " · ${period.durationDays} 天",
                )
                LabeledValue(
                    label = "本期约定价格",
                    value = Money.formatCents(period.priceCents),
                )
                LabeledValue(
                    label = "期次",
                    value = "第 ${period.periodIndex + 1} 期",
                )
                Text(
                    text = "游戏时区 ${period.refresh.zoneId} · 每日刷新 " +
                        "%02d:%02d".format(
                            period.refresh.dailyRefresh.hour,
                            period.refresh.dailyRefresh.minute,
                        ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            if (!detail.order.notes.isNullOrBlank()) {
                LabeledValue(
                    label = "订单备注",
                    value = detail.order.notes.orEmpty(),
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun PeriodCard(
    period: HostingPeriod,
    status: HostingStatus?,
    isCurrent: Boolean,
    title: String,
    today: LocalDate? = null,
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "$title · 第 ${period.periodIndex + 1} 期",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                StatusTag(
                    text = when (status) {
                        HostingStatus.EXPIRED -> "已到期 · 待续期"
                        HostingStatus.NOT_STARTED -> "未开始"
                        HostingStatus.ACTIVE -> "托管中"
                        null -> "历史"
                    },
                )
            }
            Text(
                text = "${formatGameDate(period.startDate)} – ${formatGameDate(period.endDate)} " +
                    "（含首尾 ${period.durationDays} 天）",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 4.dp),
            )
            if (period.serviceDays != null) {
                Text("计划服务 ${period.serviceDays} 天 · ${period.serviceWeekdays.sortedBy { it.value }.joinToString("、") { "周" + "一二三四五六日"[it.value - 1] }}", style = MaterialTheme.typography.bodySmall)
            }
            if (today != null && period.serviceDays != null) Text("剩余计划服务 ${period.remainingServiceDays(today)} 天（含今天）", style = MaterialTheme.typography.bodySmall)
            Text(
                text = "本期价格 ${Money.formatCents(period.priceCents)}",
                style = MaterialTheme.typography.bodyMedium,
            )
            if (!isCurrent) {
                Text(
                    text = "往期价格与任务快照已保留，不会随续期改写。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun PreviousPeriodCard(
    period: HostingPeriod,
    tasks: List<OrderTask>,
    entries: List<OrderHistoryEntry>,
    canWrite: Boolean,
    onBackfill: (String) -> Unit,
    onAddAttachment: (String?, String?, Uri) -> Unit,
    onReplaceAttachment: (String, Uri) -> Unit,
    onDeleteAttachment: (String) -> Unit,
    fileFor: (Attachment) -> File,
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(modifier = Modifier.padding(16.dp)) {
            PeriodCard(period = period, status = null, isCurrent = false, title = "往期")
            Text(
                text = "任务快照",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(top = 8.dp),
            )
            tasks.forEach { task ->
                Text(
                    text = "· ${task.title}（${recurrenceText(task)}）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "打卡记录 · ${entries.size} 条",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.weight(1f),
                )
                if (canWrite) {
                    TextButton(onClick = { onBackfill(period.id) }) { Text("补录/更正") }
                }
            }
            entries.forEach { entry ->
                HistoryCard(
                    entry = entry,
                    canWrite = canWrite,
                    onAddAttachment = onAddAttachment,
                    onReplaceAttachment = onReplaceAttachment,
                    onDeleteAttachment = onDeleteAttachment,
                    fileFor = fileFor,
                )
            }
        }
    }
}

@Composable
private fun HistoryCard(
    entry: OrderHistoryEntry,
    canWrite: Boolean,
    onAddAttachment: (String?, String?, Uri) -> Unit,
    onReplaceAttachment: (String, Uri) -> Unit,
    onDeleteAttachment: (String) -> Unit,
    fileFor: (Attachment) -> File,
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = entry.taskTitle,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                )
                if (entry.backfilled) {
                    StatusTag(text = "补录")
                }
            }
            Text(
                text = if (entry.windowStart != null && entry.windowEnd != null &&
                    entry.windowStart != entry.windowEnd
                ) {
                    "游戏周 ${formatGameDate(entry.windowStart)} – ${formatGameDate(entry.windowEnd)}"
                } else {
                    "游戏日期 ${formatGameDate(entry.attributedDate)}"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "记录时间 ${formatInstant(entry.recordedAt)}" +
                    if (entry.value != null) " · 进度 ${entry.value.toPlainString()}" else "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val occurrenceId = entry.occurrenceId
            ScreenshotStrip(
                attachments = entry.attachments,
                fileFor = fileFor,
                enabled = canWrite && occurrenceId != null,
                onAdd = { uri -> onAddAttachment(null, occurrenceId, uri) },
                onReplace = { attachment, uri -> onReplaceAttachment(attachment.id, uri) },
                onDelete = { attachment -> onDeleteAttachment(attachment.id) },
                modifier = Modifier.padding(top = 4.dp),
                emptyLabel = "本次打卡还没有截图。",
            )
        }
    }
}

/**
 * Current-period debt, historical debt and total debt, plus every receipt grouped by
 * the period it was explicitly paid against. All numbers come from [OrderDetail.balance].
 */
@Composable
private fun PaymentSection(
    detail: OrderDetail,
    canPay: Boolean,
    onRecordPayment: (periodId: String?) -> Unit,
    onAmendReceipt: (Receipt) -> Unit,
    onVoidReceipt: (Receipt) -> Unit,
) {
    val balance = detail.balance()
    val current = balance.currentItem
    val archived = detail.owner.archived || detail.account.archived

    Card(
        modifier = Modifier.testTag("order-detail-payment-section"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "收款与欠款",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                if (balance.totalOutstandingCents == 0L) {
                    StatusTag(text = "已结清")
                }
            }
            if (balance.hosting) {
                val currentIsFuture = current?.periodId
                    ?.let { it in balance.futurePeriodIds } == true
                LabeledValue(
                    label = (if (currentIsFuture) "最近待开始期（第 " else "本期（第 ") +
                        "${((current?.periodIndex ?: 0) + 1)} 期）应收 / 已收 / 待收",
                    value = current?.let {
                        "${Money.formatCents(it.priceCents)} / ${Money.formatCents(it.paidCents)} / " +
                            Money.formatCents(it.outstandingCents)
                    } ?: "暂无期次",
                    modifier = Modifier.padding(top = 8.dp),
                )
                LabeledValue(
                    label = "历史欠款（往期未收）",
                    value = Money.formatCents(balance.historicalOutstandingCents),
                )
                if (balance.futureOutstandingCents > 0L) {
                    LabeledValue(
                        label = "未来期次应收（尚未开始）",
                        value = Money.formatCents(balance.futureOutstandingCents),
                    )
                }
                LabeledValue(
                    label = "合计待收",
                    value = Money.formatCents(balance.totalOutstandingCents),
                )
                if (balance.totalOverpaidCents > 0L) {
                    Text(
                        text = "已收超过约定价格 " + Money.formatCents(balance.totalOverpaidCents) +
                            "（多收）。改价不会改写收款流水，可作废或更正收款后再收。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            } else {
                val item = balance.items.firstOrNull()
                LabeledValue(
                    label = "约定总价 / 已收 / 待收",
                    value = item?.let {
                        "${Money.formatCents(it.priceCents)} / ${Money.formatCents(it.paidCents)} / " +
                            Money.formatCents(it.outstandingCents)
                    } ?: "未填写",
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            LabeledValue(
                label = "收款方式",
                value = when (detail.order.paymentMode) {
                    PaymentMode.ONE_TIME -> "一次结清"
                    PaymentMode.INSTALLMENTS -> "分批收款"
                },
            )
            if (archived) {
                Text(
                    text = "老板或账号已归档：仍可结算欠款、作废或更正收款，但不会恢复任何任务。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            Button(
                onClick = { onRecordPayment(current?.periodId) },
                enabled = canPay && balance.receivableItems.isNotEmpty(),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
            ) {
                Text(if (detail.order.cancelled) "订单已作废" else if (balance.receivableItems.isEmpty()) "已全部结清" else "记录收款")
            }
        }
    }

    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = "收款记录（按期次）", style = MaterialTheme.typography.titleMedium)
            Text(
                text = "共 ${detail.receipts.size} 笔，含已作废与已更正记录；分期收款不会自动抵扣其他期次。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
            if (detail.receipts.isEmpty()) {
                Text(
                    text = "还没有收款记录。记录收款后，这里会显示实际时间、金额与渠道。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            balance.items.forEach { item ->
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = item.periodLabel +
                            if (item.periodId?.let { it in balance.futurePeriodIds } == true) " · 未开始" else "",
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = when {
                            item.overpaidCents > 0L -> "多收 ${Money.formatCents(item.overpaidCents)}"
                            item.settled -> "已结清"
                            else -> "待收 ${Money.formatCents(item.outstandingCents)}"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                val periodReceipts = detail.receiptsForPeriod(item.periodId)
                if (periodReceipts.isEmpty()) {
                    Text(
                        text = "该期暂无收款。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                periodReceipts.forEach { receipt ->
                    ReceiptRow(
                        receipt = receipt,
                        now = detail.now,
                        correctedToCents = detail.correctionFor(receipt.id)?.newAmountCents,
                        correctionReason = detail.correctionFor(receipt.id)?.reason,
                        canPay = canPay,
                        onAmend = { onAmendReceipt(receipt) },
                        onVoid = { onVoidReceipt(receipt) },
                    )
                }
            }
        }
    }

    if (detail.priceChanges.isNotEmpty()) {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "价格修改记录 · ${detail.priceChanges.size} 次",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = "只记录约定价格的更正；对应收款流水从未被改写。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
                detail.priceChanges.forEach { change ->
                    Text(
                        text = buildString {
                            append("第 ${((change.periodId?.let { id ->
                                detail.periods.firstOrNull { it.id == id }?.periodIndex
                            } ?: 0) + 1)} 处价格 ")
                            append(Money.formatCents(change.previousPriceCents))
                            append(" → ")
                            append(Money.formatCents(change.newPriceCents))
                            append(" · ")
                            append(formatInstant(change.createdAt))
                            if (!change.reason.isNullOrBlank()) {
                                append(" · 原因：")
                                append(change.reason)
                            }
                        },
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun ReceiptRow(
    receipt: Receipt,
    now: java.time.Instant,
    correctedToCents: Long?,
    correctionReason: String?,
    canPay: Boolean,
    onAmend: () -> Unit,
    onVoid: () -> Unit,
) {
    val pending = receipt.effective && receipt.receivedAt.isAfter(now)
    Column(modifier = Modifier.padding(top = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = Money.formatCents(receipt.amountCents),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            when {
                receipt.status == ReceiptStatus.VOIDED -> StatusTag(text = "已作废")
                receipt.status == ReceiptStatus.REPLACED -> StatusTag(text = "已更正")
                pending -> StatusTag(text = "待到账")
            }
        }
        Text(
            text = "${receipt.channel.label} · " +
                (if (pending) "预计到账 " else "实收时间 ") +
                formatInstant(receipt.receivedAt),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (pending) {
            Text(
                text = "该笔收款尚未到账：不计入实收，也不冲抵待收（例如平台担保交易）。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        receipt.note?.let {
            Text(
                text = "备注：$it",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (receipt.status == ReceiptStatus.REPLACED) {
            Text(
                text = "已更正为 " + (correctedToCents?.let(Money::formatCents) ?: "另一笔记录") +
                    "，原金额仅保留为历史记录。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (receipt.status == ReceiptStatus.VOIDED) {
            Text(
                text = "该笔收款已作废，不计入实收与欠款余额。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (!correctionReason.isNullOrBlank()) {
            Text(
                text = "更正原因：$correctionReason",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (receipt.effective && canPay) {
            Row {
                TextButton(onClick = onAmend) { Text("更正") }
                TextButton(onClick = onVoid) { Text("作废") }
            }
        }
    }
}

@Composable
internal fun NumericEditButton(
    initialValue: String,
    enabled: Boolean,
    onValue: (String) -> Unit,
) {
    var show by remember { mutableStateOf(false) }
    TextButton(onClick = { show = true }, enabled = enabled) { Text("更新进度") }
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

/** 累计目标的行内入口（UF-038）：输入的是当前累计总值，不是本次增量。 */
@Composable
private fun CumulativeOpenButton(
    enabled: Boolean,
    onClick: () -> Unit,
) {
    TextButton(onClick = onClick, enabled = enabled) { Text("记录累计") }
}

/**
 * 累计进度面板（UF-038）。
 *
 * - 输入并提交**当前累计总值**：第一天 10、第二天 15，结果就是 15。
 * - 历史追加式保留：同一天两次输入保留两条事件，并可逐条更正或撤销；旧行和附件不动。
 * - 达标后仍可更正；更正到目标以下会重新打开任务。
 */
@Composable
internal fun CumulativeProgressDialog(
    panel: CumulativePanel,
    onDismiss: () -> Unit,
    onSubmit: (String) -> Unit,
    onCorrect: (String, String) -> Unit,
    onSelectSession: (String) -> Unit = {},
) {
    var totalText by rememberSaveable(panel.taskId) {
        mutableStateOf(panel.currentTotal.stripTrailingZeros().toPlainString())
    }
    var correctingId by rememberSaveable(panel.taskId) { mutableStateOf<String?>(null) }
    var correctedText by rememberSaveable(panel.taskId) { mutableStateOf("") }
    val reached = panel.currentTotal >= panel.targetValue
    AlertDialog(
        onDismissRequest = { if (!panel.submitting) onDismiss() },
        title = {
            Text(
                buildString {
                    append("累计进度 · ")
                    append(panel.taskTitle)
                    if (panel.scopeLabel.isNotEmpty()) append("（${panel.scopeLabel}）")
                },
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "当前累计 ${panel.currentTotal.stripTrailingZeros().toPlainString()}" +
                        (panel.unit?.takeIf { it.isNotBlank() }?.let { " $it" } ?: "") +
                        " / 目标 ${panel.targetValue.stripTrailingZeros().toPlainString()}" +
                        if (reached) "（已达标）" else "",
                    style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                )
                if (panel.sessions.size > 1) {
                    Text(
                        text = "切换轮次（只读查看，不改变当前轮次）",
                        style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                        color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    androidx.compose.foundation.layout.FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        panel.sessions.forEach { option ->
                            androidx.compose.material3.FilterChip(
                                selected = option.sessionId ==
                                    (panel.scope as? com.daigan.record.data.model.ProgressScope.Session)?.sessionId,
                                onClick = { onSelectSession(option.sessionId) },
                                label = {
                                    Text(
                                        "第 ${option.sessionIndex} 次" +
                                            if (option.completed) " · 已完成" else "",
                                    )
                                },
                            )
                        }
                    }
                }
                Text(
                    text = when {
                        panel.historicalSubmitDate != null ->
                            "这是已移除任务的历史轮次：只能补记该轮归属日" +
                                "（${formatGameDate(panel.historicalSubmitDate)}）的累计总值，" +
                                "不能记录该日之后的进度；已有记录仍可更正或撤销。"
                        panel.historical ->
                            "历史轮次只读：新增进度请回到当前轮次；这里可以更正或撤销已有记录。"
                        else ->
                            "记录的是累计总值，不是本次增量；同一天多次记录会保留每条历史。"
                    },
                    style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                    color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (!panel.historical || panel.historicalSubmitDate != null) {
                    OutlinedTextField(
                        value = totalText,
                        onValueChange = { totalText = it },
                        label = { Text("当前累计总值") },
                        singleLine = true,
                        enabled = !panel.submitting,
                        isError = panel.error != null,
                        supportingText = { panel.error?.let { Text(it) } },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    TextButton(
                        onClick = {
                            onSubmit(totalText)
                            correctingId = null
                        },
                        enabled = !panel.submitting,
                    ) { Text(if (panel.submitting) "保存中…" else "保存当前总值") }
                } else if (panel.error != null) {
                    Text(
                        panel.error,
                        color = androidx.compose.material3.MaterialTheme.colorScheme.error,
                        style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                    )
                }
                if (panel.events.isNotEmpty()) {
                    Text(
                        text = "历史记录（可更正或撤销）",
                        style = androidx.compose.material3.MaterialTheme.typography.titleSmall,
                    )
                    panel.events.sortedByDescending { it.sequence }.forEach { event ->
                        val isRetracted = event.kind == com.daigan.record.data.model.ProgressEventKind.RETRACT
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                text = buildString {
                                    append(event.gameDate)
                                    append(" · ")
                                    append(
                                        when (event.kind) {
                                            com.daigan.record.data.model.ProgressEventKind.SUBMIT -> "记录"
                                            com.daigan.record.data.model.ProgressEventKind.CORRECT -> "更正"
                                            com.daigan.record.data.model.ProgressEventKind.RETRACT -> "撤销"
                                        },
                                    )
                                    append(" · ")
                                    append(event.totalValue?.stripTrailingZeros()?.toPlainString() ?: "—")
                                },
                                style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                            )
                            if (correctingId == event.id) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    OutlinedTextField(
                                        value = correctedText,
                                        onValueChange = { correctedText = it },
                                        label = { Text("更正为（留空=撤销）") },
                                        singleLine = true,
                                        enabled = !panel.submitting,
                                        modifier = Modifier.weight(1f),
                                    )
                                    TextButton(
                                        onClick = {
                                            onCorrect(event.id, correctedText)
                                            correctingId = null
                                        },
                                        enabled = !panel.submitting,
                                    ) { Text("提交") }
                                    TextButton(onClick = { correctingId = null }) { Text("取消") }
                                }
                            } else if (!isRetracted) {
                                TextButton(
                                    onClick = {
                                        correctingId = event.id
                                        correctedText = event.totalValue?.stripTrailingZeros()?.toPlainString().orEmpty()
                                    },
                                    enabled = !panel.submitting,
                                ) { Text("更正/撤销") }
                            }
                        }
                    }
                } else {
                    Text(
                        text = "还没有记录。",
                        style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                        color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss, enabled = !panel.submitting) { Text("关闭") }
        },
    )
}

@Composable
private fun RenewalDialog(
    detail: OrderDetail,
    operationId: String,
    onDismiss: () -> Unit,
    onConfirm: (String, Int, String) -> Unit,
) {
    var duration by rememberSaveable { mutableStateOf("30") }
    // The new period continues from the last *scheduled* period, which is what the
    // repository does. An early renewal therefore never previews today's date.
    val lastScheduled = detail.latestPeriod
    // The period actually being served today. It is only the same row as [lastScheduled]
    // when no renewal is pending, so the copy below must not call the last scheduled
    // period "本期".
    val servingPeriod = detail.effectivePeriod
    var price by rememberSaveable {
        mutableStateOf(lastScheduled?.priceCents?.let(Money::centsToYuanText) ?: "")
    }
    val days = duration.toIntOrNull()
    val start = lastScheduled
        ?.let { HostingPeriodSelection.nextStartDate(it.endDate, detail.today) }
        ?: detail.today
    val end = days?.takeIf { it in 1..3650 }?.let { count ->
        if (lastScheduled?.serviceDays != null) com.daigan.record.core.time.GameCalendar.hostingEndDateForWeekdays(start, count, lastScheduled.serviceWeekdays)
        else start.plusDays(count.toLong() - 1)
    }
    val early = lastScheduled != null && start.isAfter(detail.today)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("续期 · 原订单第 ${(lastScheduled?.periodIndex ?: -1) + 2} 期")
        },
        text = {
            Column {
                if (early && servingPeriod != null) {
                    Text(
                        text = "提前续期：当前第 ${servingPeriod.periodIndex + 1} 期仍按原日期服务至 " +
                            "${formatGameDate(servingPeriod.endDate)}（含当天），" +
                            "本期任务、日历和提醒不受影响。" +
                            "新一期从 ${formatGameDate(start)} 开始，不与本期重叠。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else if (early) {
                    // No period covers today: the order has not started yet, so nothing is
                    // "currently being served" and the last scheduled row is a future one.
                    Text(
                        text = "该订单尚未开始服务。第 ${(lastScheduled?.periodIndex ?: 0) + 1} 期" +
                            "（${formatGameDate(lastScheduled!!.startDate)} 至 " +
                            "${formatGameDate(lastScheduled.endDate)}）为已安排的最近期次，" +
                            "新一期从 ${formatGameDate(start)} 开始，不与已安排的期次重叠。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else {
                    Text(
                        text = "续期：新一期从今天的游戏日期 ${formatGameDate(start)} 开始，" +
                            "不沿用旧到期日；旧期次、价格、打卡与截图全部保留。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Text(
                    text = "若当前时间早于每日刷新，今天的游戏日期仍是前一日；续期后只在该期开始日及之后生成任务，不会提前生成。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
                OutlinedTextField(
                    value = duration,
                    onValueChange = { duration = it },
                    label = { Text(if (lastScheduled?.serviceDays != null) "续期服务天数" else "续期天数") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                )
                Text(
                    text = if (end != null) {
                        "结束日期（含当天） ${formatGameDate(end)}"
                    } else {
                        "请输入有效的续期天数"
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = price,
                    onValueChange = { price = it },
                    label = { Text("本期价格（元）") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                )
                Text(
                    text = "新期次的实收从零开始，旧期次的价格与收款记录全部保留；" +
                        "若旧期仍有欠款，续期后仍可单独选择旧期次补收。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(operationId, days ?: 0, price) },
                enabled = days != null && days > 0,
            ) { Text("确认续期") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

private fun taskSubtitle(task: OrderTask, windowLabel: String?, value: java.math.BigDecimal?): String {
    val parts = mutableListOf<String>()
    if (windowLabel != null) parts += "游戏周 $windowLabel"
    if (task.progressType == ProgressType.NUMERIC) {
        parts += "进度 ${(value ?: task.currentValue).toPlainString()}/${task.targetValue.toPlainString()}" +
            (task.unit?.let { " $it" } ?: "")
    }
    return parts.joinToString(" · ")
}

private fun numericSubtitle(task: OrderTask): String =
    if (task.progressType == ProgressType.NUMERIC) {
        "进度 ${task.currentValue.toPlainString()}/${task.targetValue.toPlainString()}" +
            (task.unit?.let { " $it" } ?: "")
    } else {
        ""
    }

private fun recurrenceText(task: OrderTask): String = when (task.recurrence) {
    com.daigan.record.data.model.RecurrenceType.ONE_TIME -> "一次性"
    com.daigan.record.data.model.RecurrenceType.DAILY -> "每日"
    com.daigan.record.data.model.RecurrenceType.WEEKLY -> "每周"
    com.daigan.record.data.model.RecurrenceType.SELECTED_WEEKDAYS ->
        "指定星期 " + task.weekdays.joinToString("、") { weekdayLabel(it) }
    com.daigan.record.data.model.RecurrenceType.MONTHLY -> "每月 ${task.monthDays.sorted().joinToString("、")} 日（无该日跳过）"
    com.daigan.record.data.model.RecurrenceType.EVERY_N_DAYS ->
        "每隔 ${task.everyNDays ?: "?"} 天"
}

private fun weekdayLabel(day: java.time.DayOfWeek): String = when (day) {
    java.time.DayOfWeek.MONDAY -> "周一"
    java.time.DayOfWeek.TUESDAY -> "周二"
    java.time.DayOfWeek.WEDNESDAY -> "周三"
    java.time.DayOfWeek.THURSDAY -> "周四"
    java.time.DayOfWeek.FRIDAY -> "周五"
    java.time.DayOfWeek.SATURDAY -> "周六"
    java.time.DayOfWeek.SUNDAY -> "周日"
}

/**
 * 计次托管续期对话框：只填本期次数与本期的价格。期次的日期只是内部锚点，界面不提供
 * 起止日期，因此不会伪造一个日历截止日。
 */
@Composable
private fun CountRenewDialog(
    detail: OrderDetail,
    operationId: String,
    onDismiss: () -> Unit,
    onConfirm: (targetSessionsText: String, priceYuan: String) -> Unit,
) {
    var targetText by rememberSaveable(operationId) {
        // Prefill from the latest count period: the order-level mirror is the sum of
        // every period and would propose the wrong size for the new one.
        mutableStateOf(
            (detail.periods.lastOrNull { it.serviceCount != null }?.serviceCount ?: 30).toString(),
        )
    }
    var priceText by rememberSaveable(operationId) { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("追加一期打卡") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "新期次拥有自己的次数、价格与任务快照；已完成的轮次和收款仍保留在原期次。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = targetText,
                    onValueChange = { targetText = it },
                    label = { Text("本期打卡次数") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = priceText,
                    onValueChange = { priceText = it },
                    label = { Text("本期价格（元）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(targetText, priceText) }) { Text("追加期次") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
