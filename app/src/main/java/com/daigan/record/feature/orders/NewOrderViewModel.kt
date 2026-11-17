package com.daigan.record.feature.orders

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.daigan.record.core.time.GameCalendar
import com.daigan.record.core.time.GameRefreshRules
import com.daigan.record.core.time.TimeProvider
import com.daigan.record.data.model.CountHosting
import com.daigan.record.data.model.GameAccount
import com.daigan.record.data.model.GameTemplate
import com.daigan.record.data.model.GameTemplateSummary
import com.daigan.record.data.model.HostingMode
import com.daigan.record.data.model.HostingOrderDraft
import com.daigan.record.data.model.InitialPaymentDraft
import com.daigan.record.data.model.Money
import com.daigan.record.data.model.OrderCommandFingerprint
import com.daigan.record.data.model.OrderTemplateProvenance
import com.daigan.record.data.model.TemplateTitleRule
import com.daigan.record.data.model.TemplatePricePreset
import com.daigan.record.data.model.OrderType
import com.daigan.record.data.model.parseMonthlyDates
import com.daigan.record.data.model.Owner
import com.daigan.record.data.model.PaymentChannel
import com.daigan.record.data.model.PaymentMode
import com.daigan.record.data.model.ProgressType
import com.daigan.record.data.model.RecurrenceType
import com.daigan.record.data.model.ShortOrderDraft
import com.daigan.record.data.model.TEMPLATE_NO_HOSTING_TASKS_MESSAGE
import com.daigan.record.data.model.TEMPLATE_NO_SHORT_TASKS_MESSAGE
import com.daigan.record.data.model.TaskDraft
import com.daigan.record.data.model.TemplateOrderKind
import com.daigan.record.data.model.toTaskDraft
import com.daigan.record.data.repository.GameAccountRepository
import com.daigan.record.data.repository.GameTemplateRepository
import com.daigan.record.data.repository.OrderRepository
import com.daigan.record.data.repository.OwnerRepository
import com.daigan.record.ui.navigation.Routes
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class NewOrderUiState(
    val loading: Boolean = true,
    val loadError: String? = null,
    val owner: Owner? = null,
    val account: GameAccount? = null,
    val type: OrderType = OrderType.HOSTING,
    /** 托管方式：按日期（原有）或打卡式（按次数，UF-033）。 */
    val hostingMode: HostingMode = HostingMode.DATE_RANGE,
    /** Text of the agreed check-in rounds; validated only in count-based mode. */
    val targetSessionsText: String = CountHosting.DEFAULT_TARGET_SESSIONS.toString(),
    val title: String = "日常托管",
    val priceYuan: String = "",
    val paymentMode: PaymentMode = PaymentMode.ONE_TIME,
    val deadlineDate: LocalDate? = null,
    val notes: String = "",
    val startDate: LocalDate = LocalDate.now(),
    val endDate: LocalDate = LocalDate.now().plusDays(29),
    val durationText: String = "30",
    val serviceDayMode: Boolean = false,
    val serviceWeekdays: Set<DayOfWeek> = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY),
    val zoneText: String = ZoneId.systemDefault().id,
    val dailyRefresh: LocalTime = LocalTime.of(4, 0),
    val weeklyRefreshDay: DayOfWeek = DayOfWeek.MONDAY,
    val weeklyRefreshTime: LocalTime = LocalTime.of(5, 0),
    /** 每月刷新日（逗号分隔 1–31）；空白表示按周刷新。 */
    val monthlyRefreshDaysText: String = "",
    val tasks: List<TaskDraft> = listOf(TaskDraft()),
    val initialPaymentEnabled: Boolean = false,
    val initialAmountYuan: String = "",
    val initialChannel: PaymentChannel = PaymentChannel.WECHAT,
    val initialNote: String = "",
    /** Actual first-receipt moment. Defaults to "now"; historical values are allowed. */
    val initialReceivedAtMillis: Long = 0L,
    /** Template library and the currently applied creation defaults (optional). */
    val templates: List<GameTemplateSummary> = emptyList(),
    val selectedTemplateId: String? = null,
    val selectedTemplateName: String? = null,
    val selectedTemplateVersion: String? = null,
    val templateNotice: String? = null,
    /**
     * Price options carried by the currently applied template. A dropdown only; the
     * price field stays editable and is never filled by applying a template alone.
     */
    val templatePricePresets: List<TemplatePricePreset> = emptyList(),
    /** The preset the price field currently matches, or null when the price is hand typed. */
    val selectedPricePresetId: String? = null,
    val formErrors: Map<String, String> = emptyMap(),
    val saveError: String? = null,
    val saving: Boolean = false,
    /**
     * The order this form instance committed. Non-null is a terminal successful state:
     * the form itself is already an order, so another tap on it is not a new order
     * intent. It is persisted and only cleared by [NewOrderViewModel.startNewCreation].
     */
    val createdOrderId: String? = null,
    /**
     * True while the committed result has not been handed to the navigation host yet.
     * State (not a replay-zero event), so a collector that attaches or reattaches after
     * completion still observes the destination; acknowledging it stops replays.
     */
    val createdNavigationPending: Boolean = false,
) {
    val errorMessages: List<String> get() = formErrors.values.distinct()
    val isShort: Boolean get() = type == OrderType.SHORT_TASK
    val countBased: Boolean get() = hostingMode == HostingMode.COUNT_BASED
    val targetSessionsOrNull: Int? get() = targetSessionsText.trim().toIntOrNull()
    val isCreated: Boolean get() = createdOrderId != null
    val hasPricePresets: Boolean get() = templatePricePresets.isNotEmpty()

    val priceCentsOrNull: Long? get() = Money.parseYuanToCentsOrNull(priceYuan)
    val initialCentsOrNull: Long? get() = Money.parsePositiveYuanToCentsOrNull(initialAmountYuan)

    /** Preview only; the repository recomputes the same numbers inside its transaction. */
    val remainingAfterInitialCents: Long?
        get() {
            if (!initialPaymentEnabled) return priceCentsOrNull
            val price = priceCentsOrNull ?: return null
            val initial = initialCentsOrNull ?: return null
            return if (initial > price) null else price - initial
        }
}

@HiltViewModel
class NewOrderViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val ownerRepository: OwnerRepository,
    private val accountRepository: GameAccountRepository,
    private val orderRepository: OrderRepository,
    private val timeProvider: TimeProvider,
    private val templateRepository: GameTemplateRepository,
) : ViewModel() {

    private val ownerId: String = checkNotNull(savedStateHandle[Routes.OWNER_ID_ARG]) {
        "ownerId 参数缺失"
    }
    private val accountId: String = checkNotNull(savedStateHandle[Routes.ACCOUNT_ID_ARG]) {
        "accountId 参数缺失"
    }

    private val initialToday = GameCalendar.gameDate(
        timeProvider.now(),
        GameRefreshRules.provisional(ZoneId.systemDefault()),
    )

    private val _uiState = MutableStateFlow(
        NewOrderUiState(
            startDate = initialToday,
            endDate = initialToday.plusDays(29),
            zoneText = ZoneId.systemDefault().id,
            initialReceivedAtMillis = timeProvider.now().toEpochMilli(),
        ),
    )
    val uiState: StateFlow<NewOrderUiState> = _uiState.asStateFlow()

    /**
     * The tasks produced by the last template application. Used only to decide whether a
     * type switch may re-derive the defaults: once the user edits a task, their work is
     * never silently replaced. Not persisted; after process restoration the form keeps
     * the exact tasks from the pending command instead of re-applying a template.
     */
    private var lastAppliedTasks: List<TaskDraft>? = null

    /**
     * Form-local record of the last title the app auto-filled (UF-036): the built-in
     * type defaults and any applied template default. A title that differs from this
     * record is a manual edit and is never overwritten by later template defaults.
     * Pure UI memory; never persisted or exported.
     */
    private var lastAutoAppliedTitle: String? = "日常托管"

    private var restoredPendingCommand = false
    private var explicitShortCalendar = true
    private var templateIntent = 0L

    /** Every newer form decision invalidates older asynchronous default reads. */
    private fun invalidateTemplateReads() {
        templateIntent++
    }

    private fun canApplyTemplate(intent: Long, type: OrderType): Boolean =
        intent == templateIntent && _uiState.value.type == type &&
            !_uiState.value.saving && !_uiState.value.isCreated

    init {
        restorePendingCommand()
        viewModelScope.launch {
            val owner = ownerRepository.findOwner(ownerId)
            val account = accountRepository.findAccount(accountId)
            val error = when {
                owner == null -> "找不到这位老板"
                account == null -> "找不到这个游戏账号"
                account.ownerId != ownerId -> "游戏账号不属于该老板"
                owner.archived -> "老板已归档，请先恢复老板后再接新单"
                account.archived -> "游戏账号已归档，请先恢复账号后再接新单"
                else -> null
            }
            runCatching { templateRepository.ensureBuiltInTemplates() }
            val templates = runCatching { templateRepository.findAllSummaries() }
                .getOrDefault(emptyList())
            _uiState.update {
                it.copy(
                    loading = false,
                    loadError = error,
                    owner = owner,
                    account = account,
                    templates = templates,
                )
            }
            if (error == null && templateIntent == 0L && !restoredPendingCommand && !_uiState.value.isCreated) {
                applyAccountDefaultTemplate(account?.defaultTemplateId, templates)
            }
        }
    }

    /**
     * Preselects the account's default template and fills the form once. A missing or
     * deleted template is ignored; an account never requires a template to create an
     * order, and a restored pending command is left exactly as it was.
     */
    private suspend fun applyAccountDefaultTemplate(
        templateId: String?,
        templates: List<GameTemplateSummary>,
    ) {
        if (templateId == null || templates.none { it.id == templateId }) return
        val intent = templateIntent
        val type = _uiState.value.type
        val template = runCatching { templateRepository.findTemplate(templateId) }.getOrNull()
            ?: return
        if (!canApplyTemplate(intent, type)) return
        applyTemplateDefaults(template, type)
        _uiState.update {
            // A mixed-category conflict must stay visible: it changes what the user has
            // to decide, unlike the plain "defaults were filled" reassurance.
            val conflictNotice = it.templateNotice?.takeIf { notice -> notice.contains("不一致") }
            it.copy(
                templateNotice = conflictNotice ?: (
                    "已按账号默认模板填入「${template.gameName} · " +
                        "${template.templateVersion}」；可随时修改或清除。"
                    ),
            )
        }
    }

    /**
     * Restores the exact command that was pending when the process died, including the
     * create-operation id, the initial-receipt id and the actual receipt time. Without
     * this, a retry after restoration could not be recognized as the same command.
     */
    private fun restorePendingCommand() {
        restoreCommittedCreation()
        val restored = savedStateHandle.get<String>(KEY_PENDING_COMMAND)
            ?.let(PendingOrderCommandCodec::decode)
            ?: return
        restoredPendingCommand = true
        savedStateHandle[KEY_CREATION_OPERATION] = restored.creationOperationId
        savedStateHandle[KEY_INITIAL_PAYMENT_OPERATION] = restored.initialPaymentOperationId
        val form = restored.form
        explicitShortCalendar = form.explicitShortCalendar
        _uiState.update { current ->
            current.copy(
                type = if (form.isShort) OrderType.SHORT_TASK else OrderType.HOSTING,
                // A restored command keeps the exact mode/count it was submitted with;
                // a payload written before count-based hosting restores date mode.
                hostingMode = form.hostingMode,
                targetSessionsText = form.targetSessionsText.ifBlank {
                    CountHosting.DEFAULT_TARGET_SESSIONS.toString()
                },
                title = form.title,
                priceYuan = form.priceYuan,
                paymentMode = form.paymentMode,
                deadlineDate = form.deadlineDate,
                notes = form.notes,
                startDate = form.startDate,
                endDate = form.endDate,
                durationText = form.durationText,
                serviceDayMode = form.serviceDayMode,
                serviceWeekdays = form.serviceWeekdays,
                zoneText = form.zoneText,
                dailyRefresh = form.dailyRefresh,
                weeklyRefreshDay = form.weeklyRefreshDay,
                weeklyRefreshTime = form.weeklyRefreshTime,
                monthlyRefreshDaysText = form.monthlyRefreshDaysText,
                tasks = form.tasks,
                initialPaymentEnabled = form.initialPaymentEnabled,
                initialAmountYuan = form.initialAmountYuan,
                initialChannel = form.initialChannel,
                initialNote = form.initialNote,
                initialReceivedAtMillis = restored.initialReceivedAtMillis,
                selectedTemplateId = form.sourceTemplateId,
                selectedTemplateName = form.sourceTemplateName,
                selectedTemplateVersion = form.sourceTemplateVersion,
                // A restored command fixes its own price and carries no preset list, so
                // nothing here may claim a preset is selected or offer a stale dropdown.
                templatePricePresets = emptyList(),
                selectedPricePresetId = null,
            )
        }
    }

    /**
     * A restored form that already committed an order must not look like an empty form:
     * keeping the result in state lets the screen reopen the original destination and
     * prevents a second create command from the restored instance.
     */
    private fun restoreCommittedCreation() {
        val createdOrderId = savedStateHandle.get<String>(KEY_CREATED_ORDER_ID) ?: return
        val pending = savedStateHandle.get<Boolean>(KEY_CREATED_NAVIGATION_PENDING) ?: true
        _uiState.update {
            it.copy(
                createdOrderId = createdOrderId,
                createdNavigationPending = pending,
                saving = false,
            )
        }
    }

    fun selectType(type: OrderType) {
        val previous = _uiState.value
        if (previous.type == type || previous.saving || previous.isCreated) return
        invalidateTemplateReads()
        explicitShortCalendar = true
        val intent = templateIntent
        // Re-derive the template defaults only while the task list is still exactly what
        // the template produced. Edited tasks are the user's work and are never replaced.
        val mayReapply = previous.selectedTemplateId != null &&
            lastAppliedTasks != null &&
            previous.tasks == lastAppliedTasks
        // A default-only title (built-in type default or last auto fill) follows the new
        // order type; a manually edited title is kept exactly as typed.
        val previousTitle = previous.title
        val defaultOnlyTitle = previousTitle == lastAutoAppliedTitle ||
            previousTitle == "日常托管" || previousTitle == "短期任务"
        val newTitle = when {
            type == OrderType.HOSTING && defaultOnlyTitle -> "日常托管"
            type == OrderType.SHORT_TASK && defaultOnlyTitle -> "短期任务"
            else -> previousTitle
        }
        if (newTitle != previousTitle) lastAutoAppliedTitle = newTitle
        _uiState.update { state ->
            state.copy(
                type = type,
                title = newTitle,
                formErrors = emptyMap(),
                saveError = null,
            )
        }
        val templateId = previous.selectedTemplateId
        if (mayReapply && templateId != null) {
            viewModelScope.launch {
                val template = runCatching { templateRepository.findTemplate(templateId) }
                    .getOrNull()
                    ?: return@launch
                if (canApplyTemplate(intent, type)) applyTemplateDefaults(template, type)
            }
        } else if (previous.selectedTemplateId != null) {
            _uiState.update {
                it.copy(
                    templateNotice = "已切换订单类型；当前任务保留你的编辑，可重新选择模板以套用默认值。",
                )
            }
        }
    }

    /**
     * Fills task defaults and refresh rules from one template. The template is a creation
     * default only; the created order stores its own copies and provenance label.
     */
    fun applyTemplate(templateId: String) {
        val state = _uiState.value
        if (state.isCreated || state.saving) return
        invalidateTemplateReads()
        val intent = templateIntent
        val type = state.type
        viewModelScope.launch {
            val template = runCatching { templateRepository.findTemplate(templateId) }.getOrNull()
            if (!canApplyTemplate(intent, type)) return@launch
            if (template == null) {
                _uiState.update { it.copy(templateNotice = "模板不存在，可能已被删除") }
                return@launch
            }
            applyTemplateDefaults(template, type)
        }
    }

    /** Drops the template label and notice; the current tasks stay as editable drafts. */
    fun clearTemplate() {
        if (_uiState.value.saving || _uiState.value.isCreated) return
        invalidateTemplateReads()
        lastAppliedTasks = null
        _uiState.update {
            it.copy(
                selectedTemplateId = null,
                selectedTemplateName = null,
                selectedTemplateVersion = null,
                templatePricePresets = emptyList(),
                selectedPricePresetId = null,
                templateNotice = "已清除模板默认值；当前任务仍可继续编辑。",
            )
        }
    }

    private fun applyTemplateDefaults(
        template: GameTemplate,
        type: OrderType,
    ) {
        if (_uiState.value.isCreated || _uiState.value.saving) return
        val kind = if (type == OrderType.SHORT_TASK) {
            TemplateOrderKind.SHORT_TASK
        } else {
            TemplateOrderKind.HOSTING
        }
        val applicable = template.tasksFor(kind)
        if (applicable.isEmpty()) {
            // Never reinterpret a one-time task as a daily occurrence, and never wipe the
            // tasks the user already has because a template does not fit this order type.
            _uiState.update {
                it.copy(
                    templateNotice = if (kind == TemplateOrderKind.HOSTING) {
                        "「${template.gameName}」$TEMPLATE_NO_HOSTING_TASKS_MESSAGE"
                    } else {
                        "「${template.gameName}」$TEMPLATE_NO_SHORT_TASKS_MESSAGE"
                    },
                )
            }
            return
        }
        explicitShortCalendar = true
        val tasks = applicable.map { it.toTaskDraft() }
        lastAppliedTasks = tasks
        // UF-036: the template default order title fills only an untouched title. A
        // manual edit always wins, a blank default never clears, and clearing the title
        // makes later automatic application possible again.
        val currentTitle = _uiState.value.title
        val resolvedTitle = TemplateTitleRule.resolveOrderTitle(
            currentTitle = currentTitle,
            lastAutoApplied = lastAutoAppliedTitle,
            defaultTitle = template.defaultOrderTitle,
        )
        lastAutoAppliedTitle = TemplateTitleRule.nextProvenance(
            currentTitle = currentTitle,
            lastAutoApplied = lastAutoAppliedTitle,
            defaultTitle = template.defaultOrderTitle,
        )
        // Category defaults are resolved once: an explicit template mode wins, otherwise
        // the categories' consensus applies, and a disagreement keeps the user's current
        // choice instead of silently picking the first category.
        val defaults = template.serviceDefaults
        val hostingDefaults = type == OrderType.HOSTING
        val effectiveMode = if (hostingDefaults) defaults.hostingMode else null
        val targetSessions = when {
            effectiveMode == HostingMode.COUNT_BASED ->
                (defaults.targetSessions ?: CountHosting.DEFAULT_TARGET_SESSIONS).toString()

            else -> _uiState.value.targetSessionsText
        }
        val serviceWeekdays = when {
            effectiveMode == HostingMode.DATE_RANGE -> defaults.serviceWeekdays
            effectiveMode == HostingMode.COUNT_BASED -> emptySet()
            else -> _uiState.value.serviceWeekdays
        }
        val serviceDayMode = when {
            effectiveMode == HostingMode.COUNT_BASED -> false
            effectiveMode == HostingMode.DATE_RANGE -> defaults.serviceWeekdays.isNotEmpty()
            else -> _uiState.value.serviceDayMode
        }
        _uiState.update {
            it.copy(
                title = resolvedTitle,
                tasks = tasks,
                zoneText = template.refresh.zoneId.id,
                dailyRefresh = template.refresh.dailyRefresh,
                weeklyRefreshDay = template.refresh.weeklyRefreshDay,
                weeklyRefreshTime = template.refresh.weeklyRefreshTime,
                // Monthly refresh days belong to the order's refresh intent; a template
                // carries weekly rules only, so the user's monthly input is preserved.
                monthlyRefreshDaysText = it.monthlyRefreshDaysText,
                selectedTemplateId = template.id,
                selectedTemplateName = template.gameName,
                selectedTemplateVersion = template.templateVersion,
                templatePricePresets = template.pricePresets.sortedBy { it.sortOrder },
                // Selecting a template never fills the price: the field keeps whatever the
                // user typed, and nothing is claimed to be "the preset" until they pick one.
                selectedPricePresetId = null,
                // UF-034: the template's hosting mode and agreed round count are copied
                // as creation defaults. The user can still adjust them before saving,
                // and the created order keeps its own snapshot.
                hostingMode = effectiveMode ?: it.hostingMode,
                targetSessionsText = targetSessions,
                // An explicit mode overrides incompatible inherited values: count mode
                // clears the service-day plan; an explicit date range clears it unless the
                // category declares service weekdays.
                serviceWeekdays = serviceWeekdays,
                serviceDayMode = serviceDayMode,
                templateNotice = "已填入「${template.gameName} · ${template.templateVersion}」" +
                    "的 ${tasks.size} 项任务与刷新规则" +
                    when {
                        !hostingDefaults -> ""
                        defaults.conflict ->
                            "；该模板各分类的托管方式不一致，请手动选择托管方式"

                        effectiveMode == HostingMode.COUNT_BASED -> "，打卡式托管" +
                            "目标 ${defaults.targetSessions ?: CountHosting.DEFAULT_TARGET_SESSIONS} 次"

                        effectiveMode == HostingMode.DATE_RANGE && defaults.serviceWeekdays.isNotEmpty() ->
                            "，按日期托管（仅服务星期）"

                        else -> "，按日期托管"
                    } + "；仍可修改。",
                formErrors = emptyMap(),
                saveError = null,
            )
        }
    }

    /**
     * Switches the hosting mode. Count-based mode has no date/ weekday input, so the
     * service-day switch is turned off and the date fields are simply left unused
     * (never converted into a fabricated range).
     */
    fun selectHostingMode(mode: HostingMode) {
        val state = _uiState.value
        if (state.saving || state.isCreated || state.hostingMode == mode) return
        invalidateTemplateReads()
        _uiState.update {
            it.copy(
                hostingMode = mode,
                serviceDayMode = if (mode == HostingMode.COUNT_BASED) false else it.serviceDayMode,
                formErrors = emptyMap(),
                saveError = null,
            )
        }
    }

    fun updateTargetSessions(value: String) = _uiState.update {
        it.copy(targetSessionsText = value, formErrors = emptyMap(), saveError = null)
    }

    fun updateTitle(value: String) = _uiState.update { it.copy(title = value) }

    fun updatePrice(value: String) = _uiState.update { state ->
        val selected = state.templatePricePresets
            .firstOrNull { it.id == state.selectedPricePresetId }
        // Hand-typed text detaches from the preset even when the digits happen to match,
        // so the next edit is never attributed to a preset the user did not re-pick.
        val stillSelected = selected != null &&
            value == Money.centsToYuanText(selected.amountCents)
        state.copy(
            priceYuan = value,
            selectedPricePresetId = selected?.takeIf { stillSelected }?.id,
        )
    }

    /**
     * Fills the price field from one of the current template's presets. An unknown id is
     * ignored rather than blanking the field, so a stale dropdown tap cannot erase work.
     */
    fun selectPricePreset(presetId: String) {
        if (_uiState.value.saving || _uiState.value.isCreated) return
        val preset = _uiState.value.templatePricePresets.firstOrNull { it.id == presetId } ?: return
        _uiState.update {
            it.copy(
                priceYuan = Money.centsToYuanText(preset.amountCents),
                selectedPricePresetId = preset.id,
            )
        }
    }

    fun selectPaymentMode(mode: PaymentMode) = _uiState.update {
        it.copy(paymentMode = mode, formErrors = emptyMap(), saveError = null)
    }

    fun setInitialPaymentEnabled(enabled: Boolean) = _uiState.update {
        it.copy(initialPaymentEnabled = enabled, formErrors = emptyMap(), saveError = null)
    }

    fun updateInitialAmount(value: String) = _uiState.update { it.copy(initialAmountYuan = value) }

    fun selectInitialChannel(channel: PaymentChannel) = _uiState.update {
        it.copy(initialChannel = channel)
    }

    fun updateInitialNote(value: String) = _uiState.update { it.copy(initialNote = value) }

    /** Actual first-receipt moment; independent from the order-creation time. */
    fun updateInitialReceivedAt(instant: Instant) = _uiState.update {
        it.copy(initialReceivedAtMillis = instant.toEpochMilli())
    }

    fun updateNotes(value: String) = _uiState.update { it.copy(notes = value) }

    fun setDeadline(date: LocalDate?) = _uiState.update { it.copy(deadlineDate = date) }

    fun setServiceDayMode(enabled: Boolean) { _uiState.update { it.copy(serviceDayMode = enabled) } }
    fun toggleServiceWeekday(day: DayOfWeek) { _uiState.update { it.copy(serviceWeekdays = if (day in it.serviceWeekdays) it.serviceWeekdays - day else it.serviceWeekdays + day) } }

    fun setStartDate(date: LocalDate) {
        _uiState.update { state ->
            val duration = state.durationText.toIntOrNull()?.takeIf { it > 0 } ?: 1
            state.copy(startDate = date, endDate = date.plusDays(duration.toLong() - 1))
        }
    }

    fun setDurationText(value: String) {
        _uiState.update { state ->
            val duration = value.toIntOrNull()
            state.copy(
                durationText = value,
                endDate = if (duration != null && duration > 0) {
                    state.startDate.plusDays(duration.toLong() - 1)
                } else {
                    state.endDate
                },
            )
        }
    }

    fun setEndDate(date: LocalDate) {
        _uiState.update { state ->
            val duration = if (date.isBefore(state.startDate)) {
                state.durationText
            } else {
                (date.toEpochDay() - state.startDate.toEpochDay() + 1).toString()
            }
            state.copy(endDate = date, durationText = duration)
        }
    }

    fun setZoneText(value: String) {
        invalidateTemplateReads()
        explicitShortCalendar = true
        _uiState.update { it.copy(zoneText = value) }
    }

    fun setDailyRefresh(time: LocalTime) {
        invalidateTemplateReads()
        explicitShortCalendar = true
        _uiState.update { it.copy(dailyRefresh = time) }
    }

    fun setWeeklyRefreshDay(day: DayOfWeek) {
        invalidateTemplateReads()
        explicitShortCalendar = true
        _uiState.update { it.copy(weeklyRefreshDay = day) }
    }

    fun setWeeklyRefreshTime(time: LocalTime) {
        invalidateTemplateReads()
        explicitShortCalendar = true
        _uiState.update { it.copy(weeklyRefreshTime = time) }
    }

    fun setMonthlyRefreshDaysText(value: String) {
        invalidateTemplateReads()
        explicitShortCalendar = true
        _uiState.update { it.copy(monthlyRefreshDaysText = value) }
    }

    fun addTask() {
        invalidateTemplateReads()
        _uiState.update { it.copy(tasks = it.tasks + TaskDraft()) }
    }

    fun removeTask(index: Int) {
        invalidateTemplateReads()
        _uiState.update { state ->
            if (state.tasks.size <= 1) state else state.copy(
                tasks = state.tasks.filterIndexed { i, _ -> i != index },
            )
        }
    }

    fun updateTask(index: Int, transform: (TaskDraft) -> TaskDraft) {
        invalidateTemplateReads()
        _uiState.update { state ->
            state.copy(
                tasks = state.tasks.mapIndexed { i, task -> if (i == index) transform(task) else task },
            )
        }
    }

    /**
     * Submits the whole create-order command.
     *
     * The submission gate is acquired synchronously (before any coroutine starts), so a
     * double tap in the same frame cannot start two commands. The create-operation id,
     * the initial-receipt id, the actual receipt time and the exact form payload are
     * kept in [SavedStateHandle]; a retry after process restoration is the same
     * command. Only an edited payload (explicit new command) gets new identities.
     *
     * A committed creation is terminal for this form instance: the committed order id
     * stays in state until navigation is acknowledged or [startNewCreation] is called,
     * so a repeated tap on the visible successful form never allocates new ids.
     */
    fun submit() {
        val state = _uiState.value
        if (state.isCreated || state.saving) return
        if (state.loadError != null) {
            _uiState.update { it.copy(saveError = state.loadError) }
            return
        }
        invalidateTemplateReads()
        // Gate first: everything below runs synchronously on the main thread.
        _uiState.update { it.copy(saving = true, formErrors = emptyMap(), saveError = null) }

        if (state.monthlyRefreshDaysText.isNotBlank() &&
            parseMonthlyDates(state.monthlyRefreshDaysText) == null
        ) {
            _uiState.update {
                it.copy(
                    saving = false,
                    formErrors = mapOf("monthlyRefresh" to "每月刷新日需为 1–31，可用逗号分隔多个日期"),
                )
            }
            return
        }
        var creationOperationId = stableCreationOperationId()
        var initialPaymentOperationId = stableInitialPaymentOperationId()
        var draft = buildDraft(state, creationOperationId, initialPaymentOperationId)
        if (draft == null) {
            _uiState.update {
                it.copy(
                    saving = false,
                    formErrors = mapOf("zone" to "时区格式不正确，例如 Asia/Shanghai"),
                    saveError = null,
                )
            }
            return
        }
        val errors = when (draft) {
            is ShortOrderDraft -> draft.validate()
            is HostingOrderDraft -> draft.validate()
            else -> emptyMap()
        }
        if (errors.isNotEmpty()) {
            _uiState.update { it.copy(saving = false, formErrors = errors, saveError = null) }
            return
        }

        var fingerprint = fingerprintOf(draft)
        val pendingFingerprint = savedStateHandle.get<String>(KEY_PENDING_FINGERPRINT)
        if (pendingFingerprint != null && pendingFingerprint != fingerprint) {
            // The previous submission failed and the user changed the command. This is
            // an explicitly new command, so it must not reuse the old operation ids.
            creationOperationId = UUID.randomUUID().toString()
            initialPaymentOperationId = UUID.randomUUID().toString()
            savedStateHandle[KEY_CREATION_OPERATION] = creationOperationId
            savedStateHandle[KEY_INITIAL_PAYMENT_OPERATION] = initialPaymentOperationId
            draft = checkNotNull(buildDraft(state, creationOperationId, initialPaymentOperationId))
            fingerprint = fingerprintOf(draft)
        }

        savedStateHandle[KEY_PENDING_FINGERPRINT] = fingerprint
        savedStateHandle[KEY_PENDING_COMMAND] = PendingOrderCommandCodec.encode(
            form = state.toRestoredForm(),
            creationOperationId = creationOperationId,
            initialPaymentOperationId = initialPaymentOperationId,
            initialReceivedAtMillis = state.initialReceivedAtMillis,
        )

        viewModelScope.launch {
            runCatching {
                when (draft) {
                    is ShortOrderDraft -> orderRepository.createShortOrder(draft)
                    is HostingOrderDraft -> orderRepository.createHostingOrder(draft)
                    else -> error("未知订单类型")
                }
            }.onSuccess { orderId ->
                // Committed. Keep the order id, the operation ids, the fingerprint, the
                // exact submitted receipt time and the payload: this form instance is
                // now terminal, and a repeated tap must not become another order.
                savedStateHandle[KEY_CREATED_ORDER_ID] = orderId
                savedStateHandle[KEY_CREATED_NAVIGATION_PENDING] = true
                _uiState.update {
                    it.copy(
                        saving = false,
                        createdOrderId = orderId,
                        createdNavigationPending = true,
                    )
                }
            }.onFailure { error ->
                // Keep the identities and payload so a retry is the same command.
                _uiState.update {
                    it.copy(
                        saving = false,
                        saveError = error.message ?: "创建订单失败，请重试",
                    )
                }
            }
        }
    }

    /**
     * The navigation host has taken the committed result. The form stays terminal and
     * keeps its order id; only the navigation signal stops, so a configuration change
     * or a recomposition cannot navigate to the same order twice.
     */
    fun acknowledgeCreatedNavigation(orderId: String) {
        val current = _uiState.value
        if (current.createdOrderId != orderId || !current.createdNavigationPending) return
        savedStateHandle[KEY_CREATED_NAVIGATION_PENDING] = false
        _uiState.update { it.copy(createdNavigationPending = false) }
    }

    /**
     * The only way out of the terminal successful state. Called from an explicit user
     * action ("再建一张"), it drops the committed identity, forgets the completed
     * command and allocates new ids for the next, genuinely new order.
     */
    fun startNewCreation() {
        if (_uiState.value.saving) return
        invalidateTemplateReads()
        explicitShortCalendar = true
        savedStateHandle.remove<String>(KEY_CREATED_ORDER_ID)
        savedStateHandle.remove<Boolean>(KEY_CREATED_NAVIGATION_PENDING)
        savedStateHandle.remove<String>(KEY_PENDING_FINGERPRINT)
        savedStateHandle.remove<String>(KEY_PENDING_COMMAND)
        savedStateHandle[KEY_CREATION_OPERATION] = UUID.randomUUID().toString()
        savedStateHandle[KEY_INITIAL_PAYMENT_OPERATION] = UUID.randomUUID().toString()
        _uiState.update {
            it.copy(
                createdOrderId = null,
                createdNavigationPending = false,
                saving = false,
                saveError = null,
                formErrors = emptyMap(),
                initialReceivedAtMillis = timeProvider.now().toEpochMilli(),
            )
        }
    }

    private fun buildDraft(
        state: NewOrderUiState,
        creationOperationId: String,
        initialPaymentOperationId: String,
    ): Any? {
        val zone = runCatching { ZoneId.of(state.zoneText.trim()) }.getOrNull() ?: return null
        val monthlyDays = if (state.monthlyRefreshDaysText.isBlank()) {
            emptySet()
        } else {
            parseMonthlyDates(state.monthlyRefreshDaysText) ?: return null
        }
        val refresh = GameRefreshRules(
            zoneId = zone,
            dailyRefresh = state.dailyRefresh,
            weeklyRefreshDay = state.weeklyRefreshDay,
            weeklyRefreshTime = state.weeklyRefreshTime,
            monthlyRefreshDays = monthlyDays,
        )
        val initialPayment = if (state.initialPaymentEnabled) {
            InitialPaymentDraft(
                amountYuan = state.initialAmountYuan,
                channel = state.initialChannel,
                note = state.initialNote,
                operationId = initialPaymentOperationId,
                receivedAt = Instant.ofEpochMilli(state.initialReceivedAtMillis),
            )
        } else {
            null
        }
        // Provenance is a truthful label captured at creation. It never references the
        // editable template row, so later template changes cannot rewrite this order.
        val provenance = state.selectedTemplateId
            ?.takeIf {
                !state.selectedTemplateName.isNullOrBlank() &&
                    !state.selectedTemplateVersion.isNullOrBlank()
            }
            ?.let { id ->
                OrderTemplateProvenance(
                    templateId = id,
                    gameName = checkNotNull(state.selectedTemplateName),
                    templateVersion = checkNotNull(state.selectedTemplateVersion),
                )
            }
        return if (state.isShort) {
            ShortOrderDraft(
                ownerId = ownerId,
                accountId = accountId,
                title = state.title,
                priceYuan = state.priceYuan,
                paymentMode = state.paymentMode,
                deadlineDate = state.deadlineDate,
                notes = state.notes,
                tasks = state.tasks,
                initialPayment = initialPayment,
                refresh = if (explicitShortCalendar) refresh else null,
                sourceTemplate = provenance,
                creationOperationId = creationOperationId,
            )
        } else {
            HostingOrderDraft(
                ownerId = ownerId,
                accountId = accountId,
                title = state.title,
                startDate = state.startDate,
                endDate = state.endDate,
                priceYuan = state.priceYuan,
                paymentMode = state.paymentMode,
                notes = state.notes,
                refresh = refresh,
                tasks = state.tasks,
                initialPayment = initialPayment,
                sourceTemplate = provenance,
                creationOperationId = creationOperationId,
                // A count-based order has no service-day plan; the date fields above stay
                // as inert defaults and are ignored by validation and persistence.
                serviceDays = if (state.serviceDayMode && !state.countBased) {
                    state.durationText.toIntOrNull() ?: 0
                } else {
                    null
                },
                serviceWeekdays = if (state.serviceDayMode && !state.countBased) {
                    state.serviceWeekdays
                } else {
                    emptySet()
                },
                hostingMode = state.hostingMode,
                targetSessions = if (state.countBased) state.targetSessionsOrNull else null,
            )
        }
    }

    private fun fingerprintOf(draft: Any): String = when (draft) {
        is ShortOrderDraft -> OrderCommandFingerprint.short(draft)
        is HostingOrderDraft -> OrderCommandFingerprint.hosting(draft)
        else -> error("未知订单类型")
    }

    private fun stableCreationOperationId(): String =
        savedStateHandle.get<String>(KEY_CREATION_OPERATION)
            ?: UUID.randomUUID().toString().also {
                savedStateHandle[KEY_CREATION_OPERATION] = it
            }

    private fun stableInitialPaymentOperationId(): String =
        savedStateHandle.get<String>(KEY_INITIAL_PAYMENT_OPERATION)
            ?: UUID.randomUUID().toString().also {
                savedStateHandle[KEY_INITIAL_PAYMENT_OPERATION] = it
            }

    private fun NewOrderUiState.toRestoredForm(): RestoredOrderForm = RestoredOrderForm(
        isShort = isShort,
        hostingMode = hostingMode,
        targetSessionsText = targetSessionsText,
        title = title,
        priceYuan = priceYuan,
        paymentMode = paymentMode,
        deadlineDate = deadlineDate,
        notes = notes,
        startDate = startDate,
        endDate = endDate,
        durationText = durationText,
        serviceDayMode = serviceDayMode,
        serviceWeekdays = serviceWeekdays,
        zoneText = zoneText,
        dailyRefresh = dailyRefresh,
        weeklyRefreshDay = weeklyRefreshDay,
        weeklyRefreshTime = weeklyRefreshTime,
        monthlyRefreshDaysText = monthlyRefreshDaysText,
        tasks = tasks,
        initialPaymentEnabled = initialPaymentEnabled,
        initialAmountYuan = initialAmountYuan,
        initialChannel = initialChannel,
        initialNote = initialNote,
        explicitShortCalendar = explicitShortCalendar,
        sourceTemplateId = selectedTemplateId,
        sourceTemplateName = selectedTemplateName,
        sourceTemplateVersion = selectedTemplateVersion,
    )

    private companion object {
        const val KEY_CREATION_OPERATION = "new-order.creation-operation"
        const val KEY_INITIAL_PAYMENT_OPERATION = "new-order.initial-payment-operation"
        const val KEY_PENDING_FINGERPRINT = "new-order.pending-fingerprint"
        const val KEY_PENDING_COMMAND = "new-order.pending-command"
        const val KEY_CREATED_ORDER_ID = "new-order.created-order-id"
        const val KEY_CREATED_NAVIGATION_PENDING = "new-order.created-navigation-pending"
    }
}
