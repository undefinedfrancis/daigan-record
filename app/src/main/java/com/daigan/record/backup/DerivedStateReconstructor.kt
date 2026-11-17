package com.daigan.record.backup

import com.daigan.record.core.time.GameCalendar
import com.daigan.record.core.time.GameRefreshRules
import com.daigan.record.data.local.parseWeekdays
import com.daigan.record.data.model.CountHosting
import com.daigan.record.data.model.GameRefreshRulesDomain
import com.daigan.record.data.model.HostingPeriod
import com.daigan.record.data.model.HostingSession
import com.daigan.record.data.model.NumericMode
import com.daigan.record.data.model.OrderTask
import com.daigan.record.data.model.ProgressEventKind
import com.daigan.record.data.model.ProgressResolution
import com.daigan.record.data.model.ProgressScope
import com.daigan.record.data.model.ProgressScopeType
import com.daigan.record.data.model.ProgressType
import com.daigan.record.data.model.RecurrenceType
import com.daigan.record.data.model.TaskProgressEvent
import java.math.BigDecimal
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

/**
 * 恢复期派生状态重建（S2-R3）。
 *
 * **权威 vs 派生**（契约 `commands.progress.derivedState.authority`）：
 * - 累计进度：`task_progress_events` 是权威；
 * - 普通周期任务：occurrence 历史是权威；
 * - 派生值只有 `hosting_sessions.completedAt`、累计任务的 `task_definitions.currentValue`
 *   以及由它们推导的 `orders.completedAt`。
 *
 * 重建只写派生列：不新增/删除事件、不删除轮次行、不改补录/附件/收款/更正/批次，
 * 也不改写普通任务的状态。派生时刻一律取**存储证据**（应做任务完成证据的最大值），
 * 绝不使用恢复设备的当前时间。
 *
 * 与网页 `src/domain/derivedState.ts` 是同一套规则的两种实现。
 */
object DerivedStateReconstructor {

    data class Report(
        val sessionsCompleted: Int,
        val sessionsReopened: Int,
        val sessionTimestampsCorrected: Int,
        val taskCachesRefreshed: Int,
        val taskCompletionsChanged: Int,
        val orderFlagsChanged: Int,
        val notes: List<String>,
    )

    class Result(val dataSet: BackupDataSet, val report: Report)

    fun reconstruct(input: BackupDataSet): Result {
        val data = Mutable(input)
        val orders = data.table("orders")
        val periods = data.table("hosting_periods")
        val sessions = data.table("hosting_sessions")
        val tasks = data.table("task_definitions")
        val occurrences = data.table("task_occurrences")
        val events = data.table("task_progress_events")

        var sessionsCompleted = 0
        var sessionsReopened = 0
        var timestampsCorrected = 0
        var cachesRefreshed = 0
        var completionsChanged = 0
        var orderFlagsChanged = 0
        val notes = ArrayList<String>()

        val taskRows = tasks.rows
        val tasksByOrder = taskRows.groupBy { tasks.str(it, "orderId").orEmpty() }
        val eventsByTask = events.rows.groupBy { events.str(it, "taskDefinitionId").orEmpty() }
        val occurrencesByKey = occurrences.rows.groupBy { occurrences.str(it, "occurrenceKey").orEmpty() }

        // ---- 1. 轮次完成度 + 累计缓存 + 订单完成（计次订单） ---------------
        orders.rows.toList().forEach { order ->
            if (orders.str(order, "type") != "HOSTING") return@forEach
            if (orders.str(order, "hostingMode") != "COUNT_BASED") return@forEach
            val orderId = orders.str(order, "id").orEmpty()
            val orderPeriods = periods.rows.filter {
                periods.str(it, "orderId") == orderId && periods.int(it, "serviceCount") != null
            }
            if (orderPeriods.isEmpty()) return@forEach
            val orderSessions = sessions.rows.filter { sessions.str(it, "orderId") == orderId }
            val orderTasks = tasksByOrder[orderId].orEmpty()

            orderSessions.forEach { session ->
                val periodId = sessions.str(session, "periodId").orEmpty()
                val period = orderPeriods.firstOrNull { periods.str(it, "id") == periodId }
                if (period == null) {
                    notes += "轮次 ${sessions.str(session, "id")} 的期次 $periodId 不属于订单 $orderId，跳过完成度重建"
                    return@forEach
                }
                val derived = sessionCompletedAt(
                    sessionId = sessions.str(session, "id").orEmpty(),
                    sessionIndex = sessions.int(session, "sessionIndex") ?: 0,
                    period = period,
                    periods = periods,
                    tasks = tasks,
                    orderTasks = orderTasks,
                    occurrences = occurrences,
                    occurrencesByKey = occurrencesByKey,
                    events = events,
                    eventsByTask = eventsByTask,
                )
                val previous = sessions.long(session, "completedAt")
                if (derived == null) {
                    if (previous != null) {
                        sessions.setLong(session, "completedAt", null)
                        sessionsReopened += 1
                    }
                } else {
                    sessions.setLong(session, "completedAt", derived)
                    if (previous == null) sessionsCompleted += 1
                    else if (previous != derived) timestampsCorrected += 1
                }
            }

            val domainPeriods = orderPeriods.map { periods.toHostingPeriod(it) }
            val domainSessions = orderSessions.map {
                sessions.toHostingSession(it)
            }
            val active = CountHosting.resolveActiveSession(orderId, domainPeriods, domainSessions)
            orderTasks.forEach { taskRow ->
                if (!isCumulative(tasks, taskRow)) return@forEach
                val taskId = tasks.str(taskRow, "id").orEmpty()
                val cache = CountHosting.taskScopeCache(
                    task = tasks.toOrderTask(taskRow),
                    state = active,
                    periods = domainPeriods,
                    sessions = domainSessions,
                    events = eventsByTask[taskId].orEmpty().mapNotNull { toEvent(events, it) },
                )
                val value = when (cache) {
                    is CountHosting.TaskScopeCache.ActiveSession -> cache.currentValue
                    is CountHosting.TaskScopeCache.AwaitingSessionRow -> cache.currentValue
                    is CountHosting.TaskScopeCache.TaskPeriodTerminal -> cache.currentValue
                    is CountHosting.TaskScopeCache.OrderTerminal -> cache.currentValue
                    is CountHosting.TaskScopeCache.IndexSpaceExhausted -> cache.currentValue
                }
                if (tasks.str(taskRow, "currentValue") != plain(value)) {
                    tasks.setStr(taskRow, "currentValue", plain(value))
                    cachesRefreshed += 1
                }
                // 托管任务的完成时刻由轮次承载；校验要求它保持 null。
                if (tasks.long(taskRow, "completedAt") != null) {
                    tasks.setLong(taskRow, "completedAt", null)
                    cachesRefreshed += 1
                }
            }

            val completedPerPeriod = HashMap<String, Int>()
            orderSessions.forEach { session ->
                if (sessions.long(session, "completedAt") == null) return@forEach
                val id = sessions.str(session, "periodId").orEmpty()
                completedPerPeriod[id] = (completedPerPeriod[id] ?: 0) + 1
            }
            val everyPeriodMet = orderPeriods.all { period ->
                val id = periods.str(period, "id").orEmpty()
                (completedPerPeriod[id] ?: 0) >= (periods.int(period, "serviceCount") ?: 0)
            }
            // 订单完成是派生投影：达成与未达成都要与证据一致（陈旧的非空值同样改正）。
            val latest = orderSessions.mapNotNull { sessions.long(it, "completedAt") }.maxOrNull()
            val derived = if (everyPeriodMet) latest else null
            if (orders.long(order, "completedAt") != derived) {
                orders.setLong(order, "completedAt", derived)
                orderFlagsChanged += 1
            }

            // F3：`orders.targetSessions` 是**最高 periodIndex 计次期次**目标的镜像。
            // 它同样是派生投影（期次 serviceCount 才是权威），所以旧文件里的陈旧镜像
            // 在这里改正，而不是被恢复或校验当成事实。
            val latestCountPeriod = orderPeriods.maxWithOrNull(
                compareBy({ periods.int(it, "periodIndex") ?: 0 }, { periods.str(it, "id").orEmpty() }),
            )
            val mirror = latestCountPeriod?.let { periods.int(it, "serviceCount") }
            if (mirror != null && orders.int(order, "targetSessions") != mirror) {
                orders.setInt(order, "targetSessions", mirror)
                orderFlagsChanged += 1
            }
        }

        // ---- 2. 非轮次 scope 的累计派生值（TASK / PERIOD） -----------------
        // 每个累计任务都要重建（包括**零事件**的任务）：事件是权威，空集合的结果是
        // startValue，绝不是"保留文件里的缓存"。普通任务的状态是权威历史，不在这里重写。
        tasks.rows.forEach { taskRow ->
            if (!isCumulative(tasks, taskRow)) return@forEach
            val orderId = tasks.str(taskRow, "orderId").orEmpty()
            val order = orders.rows.firstOrNull { orders.str(it, "id") == orderId } ?: return@forEach
            if (orders.str(order, "type") == "HOSTING" &&
                orders.str(order, "hostingMode") == "COUNT_BASED"
            ) {
                // 计次任务的缓存是活动轮次的镜像，第 1 步已按五种状态刷新。
                return@forEach
            }
            val taskId = tasks.str(taskRow, "id").orEmpty()
            val taskEvents = eventsByTask[taskId].orEmpty().mapNotNull { toEvent(events, it) }
            val periodId = tasks.str(taskRow, "periodId")
            val scope = if (periodId == null) ProgressScope.Task else ProgressScope.Period(periodId)
            val startValue = tasks.str(taskRow, "startValue")?.let(::BigDecimal) ?: BigDecimal.ZERO
            val targetValue = tasks.str(taskRow, "targetValue")?.let(::BigDecimal) ?: BigDecimal.ZERO
            val total = ProgressResolution.resolveScopeTotal(taskEvents, taskId, scope, startValue)
            if (tasks.str(taskRow, "currentValue") != plain(total)) {
                tasks.setStr(taskRow, "currentValue", plain(total))
                cachesRefreshed += 1
            }
            // 完成投影：只有短期单（TASK scope）持久化任务级完成时刻，取**有效观测赢家的
            // recordedAt**；零观测时即使 startValue 已达标也保持 null（禁止臆造完成）。
            // 按日期托管（PERIOD）的存储规则要求任务级完成时间为 null，完成度由期次总值
            // 与目标派生，因此这里显式清除陈旧值，不新建轮次、不写事件。
            val winner = ProgressResolution.resolveEffectiveEvent(taskEvents, taskId, scope)
            val stamp = winner?.recordedAt?.toEpochMilli()?.takeIf { it > 0 }
            val derivedCompletedAt =
                if (periodId == null && stamp != null && ProgressResolution.reachedTarget(total, targetValue)) {
                    stamp
                } else {
                    null
                }
            if (tasks.long(taskRow, "completedAt") != derivedCompletedAt) {
                tasks.setLong(taskRow, "completedAt", derivedCompletedAt)
                completionsChanged += 1
            }
        }

        // ---- 3. 短期单整体完成标志 --------------------------------------
        // 在累计任务完成投影之后：订单完成只看（已经重建过的）任务完成时刻。
        orders.rows.forEach { order ->
            if (orders.str(order, "type") != "SHORT_TASK") return@forEach
            val orderId = orders.str(order, "id").orEmpty()
            val live = tasksByOrder[orderId].orEmpty().filter { tasks.long(it, "retiredAt") == null }
            if (live.isEmpty()) return@forEach
            val allDone = live.all { tasks.long(it, "completedAt") != null }
            val derived = if (allDone) live.mapNotNull { tasks.long(it, "completedAt") }.maxOrNull() else null
            if (orders.long(order, "completedAt") != derived) {
                orders.setLong(order, "completedAt", derived)
                orderFlagsChanged += 1
            }
        }

        return Result(
            dataSet = data.toDataSet(),
            report = Report(
                sessionsCompleted = sessionsCompleted,
                sessionsReopened = sessionsReopened,
                sessionTimestampsCorrected = timestampsCorrected,
                taskCachesRefreshed = cachesRefreshed,
                taskCompletionsChanged = completionsChanged,
                orderFlagsChanged = orderFlagsChanged,
                notes = notes,
            ),
        )
    }

    /**
     * 一个轮次的完成时刻（S2-R3 / `derivedState.sessionCompletion`）。
     *
     * 完成条件：应做集非空 + 每个应做任务在该轮次内有归属观测且达标 + 至少一条归属观测。
     * 时刻 = 这些完成证据时刻的最大值（全部来自存储，确定性）。
     */
    private fun sessionCompletedAt(
        sessionId: String,
        sessionIndex: Int,
        period: MutableList<JsonElement?>,
        periods: Table,
        tasks: Table,
        orderTasks: List<MutableList<JsonElement?>>,
        occurrences: Table,
        occurrencesByKey: Map<String, List<MutableList<JsonElement?>>>,
        events: Table,
        eventsByTask: Map<String, List<MutableList<JsonElement?>>>,
    ): Long? {
        val periodId = periods.str(period, "id").orEmpty()
        val key = CountHosting.occurrenceKey(sessionIndex)
        val rows = occurrencesByKey[key].orEmpty()
        val dates = LinkedHashSet<LocalDate>()
        rows.forEach { row ->
            occurrences.str(row, "gameDate")?.let { runCatching { dates += LocalDate.parse(it) } }
        }
        orderTasks.forEach { task ->
            val taskId = tasks.str(task, "id").orEmpty()
            eventsByTask[taskId].orEmpty().forEach { event ->
                if (events.str(event, "scopeType") == "SESSION" &&
                    events.str(event, "scopeSessionId") == sessionId
                ) {
                    events.str(event, "gameDate")?.let { runCatching { dates += LocalDate.parse(it) } }
                }
            }
        }
        if (dates.isEmpty()) return null
        // 同一轮次的归属日期理论上唯一；多个时取最早的一个，保证重建确定性。
        val attributed = dates.min()
        val rules = refreshRules(periods, period)
        // 应做集与写入路径共用同一个纯规则（F2）：退休当天仍应做，晚于退休日不再应做，
        // 累计任务忽略重复触发日。这里不预过滤退休任务——它们属于历史应做集。
        val due = orderTasks.filter { task ->
            if (tasks.str(task, "periodId") != periodId) return@filter false
            CountHosting.isDueInRound(
                date = attributed,
                retirementGameDate = tasks.long(task, "retiredAt")?.let {
                    GameCalendar.gameDate(Instant.ofEpochMilli(it), rules)
                },
                activeFrom = tasks.str(task, "activeFrom")?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
                activeUntil = tasks.str(task, "activeUntil")?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
                recurrence = tasks.str(task, "recurrence")?.let { name ->
                    runCatching { RecurrenceType.valueOf(name) }.getOrNull()
                } ?: RecurrenceType.DAILY,
                cumulative = isCumulative(tasks, task),
            )
        }
        if (due.isEmpty()) return null

        val instants = ArrayList<Long>()
        for (task in due) {
            val taskId = tasks.str(task, "id").orEmpty()
            if (isCumulative(tasks, task)) {
                val domain = eventsByTask[taskId].orEmpty().mapNotNull { toEvent(events, it) }
                val scope = ProgressScope.Session(sessionId)
                val startValue = tasks.str(task, "startValue")?.let(::BigDecimal) ?: BigDecimal.ZERO
                val targetValue = tasks.str(task, "targetValue")?.let(::BigDecimal) ?: BigDecimal.ZERO
                val total = ProgressResolution.resolveScopeTotal(domain, taskId, scope, startValue)
                val observed = ProgressResolution.hasObservationInScope(domain, taskId, scope)
                if (!observed || !ProgressResolution.reachedTarget(total, targetValue)) return null
                ProgressResolution.resolveEffectiveEvent(domain, taskId, scope)?.let { winner ->
                    // 非正数时刻不是可用证据：绝不据此编造完成时刻。
                    if (winner.recordedAt.toEpochMilli() > 0) {
                        instants += winner.recordedAt.toEpochMilli()
                    }
                }
                continue
            }
            val occurrence = rows.firstOrNull { occurrences.str(it, "taskDefinitionId") == taskId }
                ?: return null
            val completedAt = occurrences.long(occurrence, "completedAt") ?: return null
            instants += completedAt
        }
        return instants.maxOrNull()
    }

    private fun isCumulative(tasks: Table, row: MutableList<JsonElement?>): Boolean =
        tasks.str(row, "progressType") == ProgressType.NUMERIC.name &&
            tasks.str(row, "numericMode") == NumericMode.CUMULATIVE.name

    private fun plain(value: BigDecimal): String = value.stripTrailingZeros().toPlainString()

    private fun refreshRules(periods: Table, period: MutableList<JsonElement?>): GameRefreshRules {
        val zone = runCatching { ZoneId.of(periods.str(period, "gameZoneId").orEmpty()) }
            .getOrDefault(ZoneId.systemDefault())
        val daily = periods.int(period, "dailyRefreshMinutes") ?: 0
        val weeklyDay = periods.int(period, "weeklyRefreshDay") ?: 1
        val weeklyMinutes = periods.int(period, "weeklyRefreshMinutes") ?: 0
        val monthlyDays = com.daigan.record.data.model.parseMonthlyDates(
            periods.str(period, "monthlyRefreshDays"),
        ) ?: emptySet()
        return GameRefreshRules(
            zoneId = zone,
            dailyRefresh = LocalTime.of(daily / 60, daily % 60),
            weeklyRefreshDay = DayOfWeek.of(weeklyDay.coerceIn(1, 7)),
            weeklyRefreshTime = LocalTime.of(weeklyMinutes / 60, weeklyMinutes % 60),
            monthlyRefreshDays = monthlyDays,
        )
    }

    private fun toEvent(events: Table, row: MutableList<JsonElement?>): TaskProgressEvent? {
        val id = events.str(row, "id") ?: return null
        val kind = events.str(row, "kind")?.let { ProgressEventKind.valueOf(it) } ?: return null
        val scopeType = events.str(row, "scopeType")?.let { ProgressScopeType.valueOf(it) } ?: return null
        val scope = when (scopeType) {
            ProgressScopeType.PERIOD -> ProgressScope.Period(events.str(row, "scopePeriodId").orEmpty())
            ProgressScopeType.SESSION -> ProgressScope.Session(events.str(row, "scopeSessionId").orEmpty())
            ProgressScopeType.TASK -> ProgressScope.Task
        }
        val gameDate = events.str(row, "gameDate") ?: return null
        return TaskProgressEvent(
            id = id,
            taskDefinitionId = events.str(row, "taskDefinitionId").orEmpty(),
            operationId = events.str(row, "operationId").orEmpty(),
            kind = kind,
            scope = scope,
            gameDate = LocalDate.parse(gameDate),
            totalValue = events.str(row, "totalValue")?.let(::BigDecimal),
            sequence = events.int(row, "sequence") ?: 0,
            correctsEventId = events.str(row, "correctsEventId"),
            recordedAt = Instant.ofEpochMilli(events.long(row, "recordedAt") ?: 0L),
            createdAt = Instant.ofEpochMilli(events.long(row, "createdAt") ?: 0L),
        )
    }

    // ------------------------------------------------------------------ mutable

    /** 按列名读写便携行；行内位置由目录决定。 */
    class Table internal constructor(
        private val spec: BackupTableCatalog.Table,
        val rows: MutableList<MutableList<JsonElement?>>,
    ) {
        fun str(row: MutableList<JsonElement?>, column: String): String? {
            val index = spec.columnNames.indexOf(column)
            if (index < 0) return null
            val value = row.getOrNull(index) as? JsonPrimitive ?: return null
            return if (value.isString) value.content else null
        }

        fun int(row: MutableList<JsonElement?>, column: String): Int? =
            text(row, column)?.toIntOrNull()

        fun long(row: MutableList<JsonElement?>, column: String): Long? =
            text(row, column)?.toLongOrNull()

        fun setStr(row: MutableList<JsonElement?>, column: String, value: String?) {
            val index = spec.columnNames.indexOf(column)
            if (index < 0) return
            row[index] = value?.let(::JsonPrimitive) ?: JsonNull
        }

        fun setLong(row: MutableList<JsonElement?>, column: String, value: Long?) {
            val index = spec.columnNames.indexOf(column)
            if (index < 0) return
            row[index] = value?.let { JsonPrimitive(it) } ?: JsonNull
        }

        fun setInt(row: MutableList<JsonElement?>, column: String, value: Int?) {
            val index = spec.columnNames.indexOf(column)
            if (index < 0) return
            row[index] = value?.let { JsonPrimitive(it) } ?: JsonNull
        }

        fun toHostingPeriod(row: MutableList<JsonElement?>): HostingPeriod {
            val daily = int(row, "dailyRefreshMinutes") ?: 0
            val weeklyDay = int(row, "weeklyRefreshDay") ?: 1
            val weeklyMinutes = int(row, "weeklyRefreshMinutes") ?: 0
            return HostingPeriod(
                id = str(row, "id").orEmpty(),
                orderId = str(row, "orderId").orEmpty(),
                periodIndex = int(row, "periodIndex") ?: 0,
                startDate = LocalDate.parse(str(row, "startDate")),
                endDate = LocalDate.parse(str(row, "endDate")),
                priceCents = long(row, "priceCents") ?: 0L,
                refresh = GameRefreshRulesDomain(
                    zoneId = runCatching { ZoneId.of(str(row, "gameZoneId").orEmpty()) }
                        .getOrDefault(ZoneId.systemDefault()),
                    dailyRefresh = LocalTime.of(daily / 60, daily % 60),
                    weeklyRefreshDay = DayOfWeek.of(weeklyDay.coerceIn(1, 7)),
                    weeklyRefreshTime = LocalTime.of(weeklyMinutes / 60, weeklyMinutes % 60),
                ),
                createdAt = Instant.ofEpochMilli(long(row, "createdAt") ?: 0L),
                renewalOperationId = str(row, "renewalOperationId"),
                serviceDays = int(row, "serviceDays"),
                serviceWeekdays = parseWeekdays(str(row, "serviceWeekdays")),
                serviceCount = int(row, "serviceCount"),
            )
        }

        fun toHostingSession(row: MutableList<JsonElement?>): HostingSession = HostingSession(
            id = str(row, "id").orEmpty(),
            orderId = str(row, "orderId").orEmpty(),
            sessionIndex = int(row, "sessionIndex") ?: 0,
            createdAt = Instant.ofEpochMilli(long(row, "createdAt") ?: 0L),
            completedAt = long(row, "completedAt")?.let(Instant::ofEpochMilli),
            completionBatchId = str(row, "completionBatchId"),
            periodId = str(row, "periodId"),
        )

        fun toOrderTask(row: MutableList<JsonElement?>): OrderTask = OrderTask(
            id = str(row, "id").orEmpty(),
            orderId = str(row, "orderId").orEmpty(),
            periodId = str(row, "periodId"),
            title = str(row, "title").orEmpty(),
            progressType = str(row, "progressType")?.let { ProgressType.valueOf(it) }
                ?: ProgressType.CHECKBOX,
            startValue = str(row, "startValue")?.let(::BigDecimal) ?: BigDecimal.ZERO,
            targetValue = str(row, "targetValue")?.let(::BigDecimal) ?: BigDecimal.ZERO,
            currentValue = str(row, "currentValue")?.let(::BigDecimal) ?: BigDecimal.ZERO,
            unit = str(row, "unit"),
            recurrence = str(row, "recurrence")?.let { RecurrenceType.valueOf(it) }
                ?: RecurrenceType.DAILY,
            weekdays = emptySet(),
            everyNDays = int(row, "everyNDays"),
            sortOrder = int(row, "sortOrder") ?: 0,
            completedAt = long(row, "completedAt")?.let(Instant::ofEpochMilli),
            monthDay = int(row, "monthDay"),
            monthDays = emptySet(),
            retiredAt = long(row, "retiredAt")?.let(Instant::ofEpochMilli),
            activeFrom = str(row, "activeFrom")?.let { LocalDate.parse(it) },
            activeUntil = str(row, "activeUntil")?.let { LocalDate.parse(it) },
            numericMode = str(row, "numericMode")?.let { NumericMode.valueOf(it) },
            colorArgb = int(row, "colorArgb"),
        )

        private fun text(row: MutableList<JsonElement?>, column: String): String? {
            val index = spec.columnNames.indexOf(column)
            if (index < 0) return null
            return (row.getOrNull(index) as? JsonPrimitive)?.content
        }
    }

    private class Mutable(input: BackupDataSet) {
        private val tables: LinkedHashMap<String, Table> = LinkedHashMap()
        private val sources: LinkedHashMap<String, BackupDataSet.TableRows> = LinkedHashMap()

        init {
            input.tables.forEach { tableRows ->
                sources[tableRows.table.name] = tableRows
                tables[tableRows.table.name] = Table(
                    tableRows.table,
                    tableRows.rows.map { it.toMutableList() }.toMutableList(),
                )
            }
        }

        fun table(name: String): Table = checkNotNull(tables[name]) { "缺少数据表 $name" }

        fun toDataSet(): BackupDataSet = BackupDataSet(
            sources.map { (name, source) ->
                BackupDataSet.TableRows(
                    table = source.table,
                    rows = checkNotNull(tables[name]).rows.map { it.toList() },
                    presentColumns = source.presentColumns,
                )
            },
        )
    }
}
