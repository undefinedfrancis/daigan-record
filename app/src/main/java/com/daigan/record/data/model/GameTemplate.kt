package com.daigan.record.data.model

import java.math.BigDecimal
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID

/** Where a stored template came from. The label is shown in the library, never inferred. */
enum class TemplateOrigin { BUILT_IN, USER, IMPORTED }

/**
 * Template recurrence, expressed on the **template task** itself.
 *
 * `ONE_TIME` is the explicit representation of short-order applicability: the task is
 * performed once and has no game-day or game-week occurrence. A hosting order rejects
 * it (see [HostingOrderDraft.validate]) instead of treating it as `DAILY`; a short
 * order accepts it and never generates occurrences for any recurrence.
 */
typealias TemplateRecurrence = RecurrenceType

/** Which kind of order a template's tasks can supply defaults for. */
enum class TemplateOrderKind { HOSTING, SHORT_TASK }

data class TemplateCategory(
    val id: String,
    val templateId: String,
    val name: String,
    val sortOrder: Int,
    val tasks: List<TemplateTask>,
    /** 分类级托管方式（schema 15）；NULL = 继承模板。 */
    val hostingMode: HostingMode? = null,
    val targetSessions: Int? = null,
    val serviceWeekdays: String? = null,
)

data class TemplateTask(
    val id: String,
    val templateId: String,
    val categoryId: String,
    val title: String,
    val progressType: ProgressType,
    val startValue: BigDecimal,
    val targetValue: BigDecimal,
    val unit: String?,
    val recurrence: TemplateRecurrence,
    val weekdays: Set<DayOfWeek>,
    val everyNDays: Int?,
    val sortOrder: Int,
    val monthDay: Int? = null,
    val monthDays: Set<Int> = setOfNotNull(monthDay),
    /** 任务生效起止日期（schema 15）；null 表示随期次。 */
    val activeFrom: LocalDate? = null,
    val activeUntil: LocalDate? = null,
    /** 数值口径（schema 15）；null 读作 PER_OCCURRENCE。 */
    val numericMode: NumericMode? = null,
) {
    val oneTime: Boolean get() = recurrence == RecurrenceType.ONE_TIME
}

/**
 * One saved price option of a template, stored in integer cents.
 *
 * A preset is a **creation default**, exactly like a template task: an order copies the
 * chosen amount at creation and keeps no reference back to this row. Editing or deleting
 * a preset therefore can never rewrite the price of an order that already exists.
 */
data class TemplatePricePreset(
    val id: String,
    val amountCents: Long,
    val label: String? = null,
    val sortOrder: Int = 0,
)

/**
 * A persisted, editable template. Categories and tasks are ordered snapshots owned by
 * the template; no order ever references these rows, so editing or deleting a template
 * cannot rewrite an existing order.
 */
data class GameTemplate(
    val id: String,
    val gameName: String,
    val templateVersion: String,
    val gameVersionNotes: String?,
    val refresh: GameRefreshRulesDomain,
    val origin: TemplateOrigin,
    /** Stable seed identity for a built-in template; null for user/imported rows. */
    val builtInKey: String?,
    val createdAt: Instant,
    val updatedAt: Instant,
    val categories: List<TemplateCategory>,
    /** Stable library display order; built-ins are 0, 1, 2 in preparation order. */
    val sortOrder: Int = 0,
    /** Optional per-template price options offered as a dropdown when creating an order. */
    val pricePresets: List<TemplatePricePreset> = emptyList(),
    /**
     * Hosting mode this template proposes for new orders (UF-034). It is a **creation
     * default**: an order copies the mode and round count it was created with, and later
     * template edits never rewrite an existing order.
     */
    val hostingMode: HostingMode? = null,
    /** Proposed number of check-in rounds; null unless [hostingMode] is count-based. */
    val targetSessions: Int? = null,
    /** 模板级服务星期（schema 15）；配合 DATE_RANGE 表示"仅工作日"。 */
    val serviceWeekdays: String? = null,
    /** 建单默认标题（UF-036，schema 15）；null = 不预填。 */
    val defaultOrderTitle: String? = null,
) {
    val tasks: List<TemplateTask> get() = categories.flatMap { it.tasks }

    fun tasksFor(kind: TemplateOrderKind): List<TemplateTask> = when (kind) {
        TemplateOrderKind.HOSTING -> tasks.filter { !it.oneTime }
        TemplateOrderKind.SHORT_TASK -> tasks.filter { it.oneTime }
    }

    fun supports(kind: TemplateOrderKind): Boolean = tasksFor(kind).isNotEmpty()

    /** Resolved creation defaults; explicit template values win over category consensus. */
    val serviceDefaults: TemplateServiceDefaults get() = TemplateServiceDefaultsRule.resolve(this)
}

/**
 * Creation defaults a template proposes for a new order.
 *
 * [conflict] is true when the template does not set an explicit mode and its categories
 * disagree: the form must then keep the user's current choice and say so, instead of
 * silently picking the first category. [hostingMode] null without a conflict means
 * "no opinion" (the form default stays).
 */
data class TemplateServiceDefaults(
    val hostingMode: HostingMode?,
    val targetSessions: Int?,
    val serviceWeekdays: Set<DayOfWeek>,
    val conflict: Boolean = false,
    /** True when the value came from the template row rather than category consensus. */
    val explicit: Boolean = false,
) {
    companion object {
        val NONE = TemplateServiceDefaults(null, null, emptySet())
    }
}

object TemplateServiceDefaultsRule {

    fun resolve(template: GameTemplate): TemplateServiceDefaults {
        template.hostingMode?.let { mode ->
            return normalize(
                mode = mode,
                target = template.targetSessions,
                weekdays = template.serviceWeekdays,
                explicit = true,
            )
        }
        val declared = template.categories.mapNotNull { category ->
            val mode = category.hostingMode ?: return@mapNotNull null
            normalize(mode, category.targetSessions, category.serviceWeekdays)
        }
        if (declared.isEmpty()) return TemplateServiceDefaults.NONE
        val first = declared.first()
        val allSame = declared.all {
            it.hostingMode == first.hostingMode &&
                it.targetSessions == first.targetSessions &&
                it.serviceWeekdays == first.serviceWeekdays
        }
        return if (allSame) first else TemplateServiceDefaults(null, null, emptySet(), conflict = true)
    }

    fun normalize(
        mode: HostingMode,
        target: Int?,
        weekdays: String?,
        explicit: Boolean = false,
    ): TemplateServiceDefaults = when (mode) {
        HostingMode.COUNT_BASED -> TemplateServiceDefaults(
            hostingMode = mode,
            targetSessions = target,
            serviceWeekdays = emptySet(),
            explicit = explicit,
        )

        HostingMode.DATE_RANGE -> TemplateServiceDefaults(
            hostingMode = mode,
            targetSessions = null,
            serviceWeekdays = parseWeekdays(weekdays),
            explicit = explicit,
        )
    }

    /** "1,3,5" → ISO days; blank or invalid input contributes nothing. */
    fun parseWeekdays(raw: String?): Set<DayOfWeek> {
        if (raw.isNullOrBlank()) return emptySet()
        return raw.split(',', '，', ' ')
            .mapNotNull { it.trim().toIntOrNull() }
            .filter { it in 1..7 }
            .map(DayOfWeek::of)
            .toSet()
    }
}

/** Lightweight row for pickers that must not carry every task into a list item. */
data class GameTemplateSummary(
    val id: String,
    val gameName: String,
    val templateVersion: String,
    val gameVersionNotes: String?,
    val refresh: GameRefreshRulesDomain,
    val origin: TemplateOrigin,
    val categoryCount: Int,
    val taskCount: Int,
    val hostingTaskCount: Int,
    val shortTaskCount: Int,
    val sortOrder: Int = 0,
    val hostingMode: HostingMode = HostingMode.DATE_RANGE,
    val targetSessions: Int? = null,
) {
    val label: String get() = "$gameName · $templateVersion"

    fun supports(kind: TemplateOrderKind): Boolean = when (kind) {
        TemplateOrderKind.HOSTING -> hostingTaskCount > 0
        TemplateOrderKind.SHORT_TASK -> shortTaskCount > 0
    }
}

/**
 * Editable template content. Ids are stable while the editor is open; the repository
 * re-allocates ids that would collide with another template and never reuses an id from
 * a different template.
 */
data class TemplateDraft(
    val gameName: String = "",
    val templateVersion: String = "1.0",
    val gameVersionNotes: String = "",
    val zoneText: String = "Asia/Shanghai",
    val dailyRefresh: LocalTime = LocalTime.of(4, 0),
    val weeklyRefreshDay: DayOfWeek = DayOfWeek.MONDAY,
    val weeklyRefreshTime: LocalTime = LocalTime.of(5, 0),
    val categories: List<TemplateCategoryDraft> = listOf(TemplateCategoryDraft()),
    /** Optional price options the order form offers as a dropdown. Empty is valid. */
    val pricePresets: List<TemplatePricePresetDraft> = emptyList(),
    /** Hosting mode proposed to new orders (UF-034). Empty dates stay untouched. */
    val hostingMode: HostingMode = HostingMode.DATE_RANGE,
    /**
     * True when the mode above is an explicit choice. False keeps the template row's
     * mode NULL so it inherits the category consensus (schema 15), preserving the
     * difference between "explicitly date-based" and "no opinion".
     */
    val hostingModeExplicit: Boolean = false,
    /** Text kept verbatim so a half-typed count never silently rounds. */
    val targetSessionsText: String = "",
    /** 模板级服务星期（模板格式 4）。 */
    val serviceWeekdays: String = "",
    /** 建单默认标题（UF-036，模板格式 4）；空白归一为不预填。 */
    val defaultOrderTitle: String = "",
) {
    val countBased: Boolean get() = hostingMode == HostingMode.COUNT_BASED

    fun targetSessionsOrNull(): Int? = targetSessionsText.trim().toIntOrNull()

    val taskCount: Int get() = categories.sumOf { it.tasks.size }
    val hostingTaskCount: Int
        get() = categories.sumOf { category -> category.tasks.count { it.recurrence != RecurrenceType.ONE_TIME } }
    val shortTaskCount: Int
        get() = categories.sumOf { category -> category.tasks.count { it.recurrence == RecurrenceType.ONE_TIME } }

    fun zoneOrNull(): ZoneId? = runCatching { ZoneId.of(zoneText.trim()) }.getOrNull()

    /** Trimmed text and normalized numbers; validation runs on this copy. */
    fun normalized(): TemplateDraft = copy(
        gameName = gameName.trim(),
        templateVersion = templateVersion.trim(),
        gameVersionNotes = gameVersionNotes.trim(),
        zoneText = zoneText.trim(),
        categories = categories.map { it.normalized() },
        pricePresets = pricePresets.map { it.normalized() },
        targetSessionsText = targetSessionsText.trim(),
        defaultOrderTitle = TemplateTitleRule.normalizeDefaultOrderTitle(defaultOrderTitle).orEmpty(),
    )

    /**
     * Returns Chinese per-field messages keyed by stable field keys. Empty means the
     * draft can be saved. The same rules guard the JSON codec and the repository.
     */
    fun validate(): Map<String, String> {
        val draft = normalized()
        val errors = linkedMapOf<String, String>()
        if (draft.gameName.isEmpty()) errors["gameName"] = "请填写游戏名称"
        if (draft.gameName.length > MAX_GAME_NAME_LENGTH) {
            errors["gameName"] = "游戏名称最多 $MAX_GAME_NAME_LENGTH 个字符"
        }
        if (draft.templateVersion.isEmpty()) errors["templateVersion"] = "请填写模板版本"
        if (draft.templateVersion.length > MAX_VERSION_LENGTH) {
            errors["templateVersion"] = "模板版本最多 $MAX_VERSION_LENGTH 个字符"
        }
        TemplateTitleRule.validateDefaultOrderTitle(draft.defaultOrderTitle)?.let {
            errors["defaultOrderTitle"] = it
        }
        if (draft.gameVersionNotes.length > MAX_NOTES_LENGTH) {
            errors["gameVersionNotes"] = "游戏版本备注最多 $MAX_NOTES_LENGTH 个字符"
        }
        if (draft.zoneOrNull() == null) errors["zone"] = "时区格式不正确，例如 Asia/Shanghai"
        if (draft.countBased) {
            // A count-based template must propose a usable total; the date fields below
            // stay irrelevant for it.
            CountHosting.targetSessionError(draft.targetSessionsText)?.let {
                errors["targetSessions"] = it
            }
        }
        if (draft.categories.isEmpty()) errors["categories"] = "请至少保留一个分类"
        if (draft.categories.size > MAX_CATEGORIES) errors["categories"] = "最多 $MAX_CATEGORIES 个分类"
        if (draft.taskCount == 0) errors["tasks"] = "请至少保留一个任务选项"
        if (draft.taskCount > MAX_TASKS) errors["tasks"] = "最多 $MAX_TASKS 个任务选项"
        draft.categories.forEachIndexed { index, category ->
            if (category.name.isEmpty()) errors["category.$index"] = "请填写分类名称"
            if (category.name.length > MAX_CATEGORY_NAME_LENGTH) {
                errors["category.$index"] = "分类名称最多 $MAX_CATEGORY_NAME_LENGTH 个字符"
            }
            if (category.tasks.isEmpty()) {
                errors["category.$index.tasks"] = "每个分类至少需要一个任务选项"
            }
            category.tasks.forEachIndexed { taskIndex, task ->
                task.validate("category.$index.task.$taskIndex", errors)
            }
        }
        if (draft.pricePresets.size > MAX_PRICE_PRESETS) {
            errors["pricePresets"] = "最多 $MAX_PRICE_PRESETS 个价格预设"
        }
        // Duplicates are compared by cents, not by text: "10" and "10.00" are the same
        // preset, and a dropdown with two identical amounts is unusable.
        val seenCents = mutableSetOf<Long>()
        draft.pricePresets.forEachIndexed { index, preset ->
            val key = "pricePreset.$index.amount"
            if (preset.amountText.isEmpty()) {
                errors[key] = "请填写预设价格"
            } else if (preset.amountCentsOrNull() == null) {
                errors[key] = "金额需为不超过两位小数的非负数字"
            } else if (!seenCents.add(preset.amountCentsOrNull()!!)) {
                errors["pricePresets"] = "价格预设不能重复"
            }
            if (preset.label.length > MAX_PRICE_PRESET_LABEL_LENGTH) {
                errors["pricePreset.$index.label"] =
                    "价格预设名称最多 $MAX_PRICE_PRESET_LABEL_LENGTH 个字符"
            }
        }
        return errors
    }

    fun withNewIds(): TemplateDraft = copy(
        categories = categories.map { category ->
            category.copy(
                id = UUID.randomUUID().toString(),
                tasks = category.tasks.map { it.copy(id = UUID.randomUUID().toString()) },
            )
        },
        pricePresets = pricePresets.map { it.copy(id = UUID.randomUUID().toString()) },
    )

    companion object {
        const val MAX_GAME_NAME_LENGTH = 40
        const val MAX_VERSION_LENGTH = 30
        const val MAX_NOTES_LENGTH = 300
        const val MAX_CATEGORIES = 20
        const val MAX_TASKS = 100
        const val MAX_CATEGORY_NAME_LENGTH = 30
        const val MAX_TASK_TITLE_LENGTH = 40
        const val MAX_UNIT_LENGTH = 10
        const val MAX_PRICE_PRESETS = 20
        const val MAX_PRICE_PRESET_LABEL_LENGTH = 20

        fun from(template: GameTemplate): TemplateDraft = TemplateDraft(
            gameName = template.gameName,
            templateVersion = template.templateVersion,
            gameVersionNotes = template.gameVersionNotes.orEmpty(),
            zoneText = template.refresh.zoneId.id,
            dailyRefresh = template.refresh.dailyRefresh,
            weeklyRefreshDay = template.refresh.weeklyRefreshDay,
            weeklyRefreshTime = template.refresh.weeklyRefreshTime,
            categories = template.categories.map { category ->
                TemplateCategoryDraft(
                    id = category.id,
                    name = category.name,
                    tasks = category.tasks.map { task ->
                        TemplateTaskDraft(
                            id = task.id,
                            title = task.title,
                            progressType = task.progressType,
                            startValue = task.startValue.toPlainString(),
                            targetValue = task.targetValue.toPlainString(),
                            unit = task.unit.orEmpty(),
                            recurrence = task.recurrence,
                            weekdays = task.weekdays,
                            everyNDays = task.everyNDays?.toString().orEmpty(),
                            monthDay = task.monthDays.ifEmpty { setOfNotNull(task.monthDay) }.sorted().joinToString(","),
                            numericMode = task.numericMode,
                            activeFrom = task.activeFrom?.toString().orEmpty(),
                            activeUntil = task.activeUntil?.toString().orEmpty(),
                        )
                    },
                    hostingMode = category.hostingMode,
                    targetSessionsText = category.targetSessions?.toString().orEmpty(),
                    serviceWeekdays = category.serviceWeekdays.orEmpty(),
                )
            },
            pricePresets = template.pricePresets
                .sortedBy { it.sortOrder }
                .map { preset ->
                    TemplatePricePresetDraft(
                        id = preset.id,
                        amountText = Money.centsToYuanText(preset.amountCents),
                        label = preset.label.orEmpty(),
                    )
                },
            hostingMode = template.hostingMode ?: HostingMode.DATE_RANGE,
            hostingModeExplicit = template.hostingMode != null,
            targetSessionsText = template.targetSessions?.toString().orEmpty(),
            serviceWeekdays = template.serviceWeekdays.orEmpty(),
            defaultOrderTitle = template.defaultOrderTitle.orEmpty(),
        )
    }
}

data class TemplateCategoryDraft(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val tasks: List<TemplateTaskDraft> = listOf(TemplateTaskDraft()),
    /**
     * 分类级托管方式（模板格式 4）。null = 继承模板；DATE_RANGE 是显式按日期。
     * 历史网页分类的 null 来自保存路径把 'dates' 归一，因此导入时被解释为显式。
     */
    val hostingMode: HostingMode? = null,
    /** 分类目标次数（文本，保持半输入状态不静默取整）。 */
    val targetSessionsText: String = "",
    /** 分类服务星期（逗号分隔 ISO 1–7）；配合 DATE_RANGE 表示"仅工作日"。 */
    val serviceWeekdays: String = "",
) {
    fun normalized(): TemplateCategoryDraft = copy(
        name = name.trim(),
        tasks = tasks.map { it.normalized() },
    )
}

data class TemplateTaskDraft(
    val id: String = UUID.randomUUID().toString(),
    val title: String = "",
    val progressType: ProgressType = ProgressType.CHECKBOX,
    val startValue: String = "0",
    val targetValue: String = "1",
    val unit: String = "",
    /** Default for a template task is a daily check-in, like the order form. */
    val recurrence: RecurrenceType = RecurrenceType.DAILY,
    val weekdays: Set<DayOfWeek> = emptySet(),
    val everyNDays: String = "",
    val monthDay: String = "",
    /** 任务生效起止日期（ISO，模板格式 4）；空表示随期次。 */
    val activeFrom: String = "",
    val activeUntil: String = "",
    /** 数值口径默认值；null 读作 PER_OCCURRENCE（普通任务不变）。 */
    val numericMode: NumericMode? = null,
) {
    fun startDecimal(): BigDecimal? = parseDecimal(startValue)
    fun targetDecimal(): BigDecimal? = parseDecimal(targetValue)
    fun everyNOrNull(): Int? = everyNDays.trim().toIntOrNull()
    fun monthDaysOrNull(): Set<Int>? = parseMonthlyDates(monthDay)
    fun monthDayOrNull(): Int? = monthDaysOrNull()?.firstOrNull()

    /** A null draft value is ordinary per-occurrence. */
    val effectiveNumericMode: NumericMode
        get() = numericMode ?: NumericMode.PER_OCCURRENCE

    fun normalized(): TemplateTaskDraft = copy(
        title = title.trim(),
        unit = if (progressType == ProgressType.NUMERIC) unit.trim() else "",
        startValue = if (progressType == ProgressType.NUMERIC) startValue.trim() else "0",
        targetValue = if (progressType == ProgressType.NUMERIC) targetValue.trim() else "1",
        monthDay = if (recurrence == RecurrenceType.MONTHLY) monthDay.trim() else "",
        everyNDays = if (recurrence == RecurrenceType.EVERY_N_DAYS) everyNDays.trim() else "",
        weekdays = if (recurrence == RecurrenceType.SELECTED_WEEKDAYS) weekdays else emptySet(),
        // The numeric mode belongs to numeric tasks only; dates are trimmed or kept open.
        numericMode = numericMode?.takeIf { progressType == ProgressType.NUMERIC },
        activeFrom = activeFrom.trim(),
        activeUntil = activeUntil.trim(),
    )

    internal fun validate(key: String, errors: MutableMap<String, String>) {
        val task = normalized()
        if (task.title.isEmpty()) errors[key] = "请填写任务名称"
        if (task.title.length > TemplateDraft.MAX_TASK_TITLE_LENGTH) {
            errors[key] = "任务名称最多 ${TemplateDraft.MAX_TASK_TITLE_LENGTH} 个字符"
        }
        if (task.unit.length > TemplateDraft.MAX_UNIT_LENGTH) {
            errors["$key.unit"] = "单位最多 ${TemplateDraft.MAX_UNIT_LENGTH} 个字符"
        }
        if (task.progressType == ProgressType.NUMERIC) {
            val start = task.startDecimal()
            val target = task.targetDecimal()
            if (start == null) errors["$key.start"] = "起始值格式不正确"
            if (target == null) errors["$key.target"] = "目标值格式不正确"
            if (start != null && start < BigDecimal.ZERO) errors["$key.start"] = "起始值不能为负数"
            if (start != null && start.scale() > MAX_DECIMAL_SCALE) {
                errors["$key.start"] = "起始值最多 $MAX_DECIMAL_SCALE 位小数"
            }
            if (target != null && target.scale() > MAX_DECIMAL_SCALE) {
                errors["$key.target"] = "目标值最多 $MAX_DECIMAL_SCALE 位小数"
            }
            if (start != null && target != null && target <= start) {
                errors["$key.target"] = "目标值需大于起始值"
            }
            if (target != null && target.abs() > MAX_DECIMAL_ABS) {
                errors["$key.target"] = "目标值过大"
            }
        }
        when (task.recurrence) {
            RecurrenceType.MONTHLY -> if (task.monthDaysOrNull() == null) {
                errors["$key.monthDay"] = "请输入 1–31 的日号，可用逗号分隔多个日期"
            }
            RecurrenceType.SELECTED_WEEKDAYS -> if (task.weekdays.isEmpty()) {
                errors["$key.weekdays"] = "请为指定星期任务选择执行日"
            }
            RecurrenceType.EVERY_N_DAYS -> {
                val n = task.everyNOrNull()
                if (n == null || n < 1 || n > MAX_INTERVAL_DAYS) {
                    errors["$key.interval"] = "间隔天数需为 1–$MAX_INTERVAL_DAYS 的整数"
                }
            }
            RecurrenceType.ONE_TIME, RecurrenceType.DAILY, RecurrenceType.WEEKLY -> Unit
        }
        task.validateActiveRange(key, errors)
    }

    /** Blank means open-ended; filled values must parse and keep activeFrom ≤ activeUntil. */
    private fun validateActiveRange(key: String, errors: MutableMap<String, String>) {
        val fromRaw = activeFrom.trim()
        val untilRaw = activeUntil.trim()
        val from = if (fromRaw.isEmpty()) {
            null
        } else {
            runCatching { LocalDate.parse(fromRaw) }.getOrNull()
        }
        if (fromRaw.isNotEmpty() && from == null) {
            errors["$key.activeFrom"] = "开始生效日期格式不正确"
        }
        val until = if (untilRaw.isEmpty()) {
            null
        } else {
            runCatching { LocalDate.parse(untilRaw) }.getOrNull()
        }
        if (untilRaw.isNotEmpty() && until == null) {
            errors["$key.activeUntil"] = "停止生效日期格式不正确"
        }
        if (from != null && until != null && until.isBefore(from)) {
            errors["$key.activeUntil"] = "停止生效日期不能早于开始生效日期"
        }
    }

    companion object {
        const val MAX_DECIMAL_SCALE = 3
        const val MAX_INTERVAL_DAYS = 365
        val MAX_DECIMAL_ABS: BigDecimal = BigDecimal("1000000000000")

        fun parseDecimal(raw: String): BigDecimal? =
            runCatching { BigDecimal(raw.trim()) }.getOrNull()
    }
}

/**
 * One editable price option in the template editor. The text is kept verbatim so a
 * half-typed amount never silently rounds; validation runs on [normalized].
 */
data class TemplatePricePresetDraft(
    val id: String = UUID.randomUUID().toString(),
    val amountText: String = "",
    val label: String = "",
) {
    fun amountCentsOrNull(): Long? = Money.parseYuanToCentsOrNull(amountText)

    fun normalized(): TemplatePricePresetDraft = copy(
        amountText = amountText.trim(),
        label = label.trim(),
    )
}

/** Why a template cannot supply defaults for the chosen order type. */
const val TEMPLATE_NO_HOSTING_TASKS_MESSAGE =
    "该模板没有可用于托管的任务（一次性任务只能用于短期单）"
const val TEMPLATE_NO_SHORT_TASKS_MESSAGE =
    "该模板没有可用于短期单的一次性任务"

/**
 * Converts a stored template task into the order-creation form representation.
 * The template's start/target defaults become the order task's initial values, which
 * the user can still edit before creating the order.
 */
fun TemplateTask.toTaskDraft(): TaskDraft = TaskDraft(
    title = title,
    progressType = progressType,
    startValue = startValue.toPlainString(),
    targetValue = targetValue.toPlainString(),
    unit = unit.orEmpty(),
    recurrence = recurrence,
    weekdays = weekdays,
    everyNDays = everyNDays?.toString().orEmpty(),
    monthDay = monthDays.ifEmpty { setOfNotNull(monthDay) }.sorted().joinToString(","),
    numericMode = numericMode,
    activeFrom = activeFrom?.toString().orEmpty(),
    activeUntil = activeUntil?.toString().orEmpty(),
)

fun GameTemplate.provenance(): OrderTemplateProvenance = OrderTemplateProvenance(
    templateId = id,
    gameName = gameName,
    templateVersion = templateVersion,
)
