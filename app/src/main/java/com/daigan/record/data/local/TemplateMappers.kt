package com.daigan.record.data.local

import com.daigan.record.data.local.entity.GameTemplateEntity
import com.daigan.record.data.local.entity.TemplateCategoryEntity
import com.daigan.record.data.local.entity.TemplatePricePresetEntity
import com.daigan.record.data.local.entity.TemplateTaskEntity
import com.daigan.record.data.model.GameRefreshRulesDomain
import com.daigan.record.data.model.GameTemplate
import com.daigan.record.data.model.GameTemplateSummary
import com.daigan.record.data.model.RecurrenceType
import com.daigan.record.data.model.TemplateCategory
import com.daigan.record.data.model.TemplatePricePreset
import com.daigan.record.data.model.TemplateTask
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.ZoneId

fun GameTemplateEntity.refreshDomain(): GameRefreshRulesDomain = GameRefreshRulesDomain(
    zoneId = ZoneId.of(zoneId),
    dailyRefresh = LocalTime.of(dailyRefreshMinutes / 60, dailyRefreshMinutes % 60),
    weeklyRefreshDay = DayOfWeek.of(weeklyRefreshDay.coerceIn(1, 7)),
    weeklyRefreshTime = LocalTime.of(weeklyRefreshMinutes / 60, weeklyRefreshMinutes % 60),
)

fun TemplateTaskEntity.toModel(): TemplateTask = TemplateTask(
    id = id,
    templateId = templateId,
    categoryId = categoryId,
    title = title,
    progressType = progressType,
    startValue = startValue,
    targetValue = targetValue,
    unit = unit,
    recurrence = recurrence,
    weekdays = parseWeekdays(weekdays),
    everyNDays = everyNDays,
    monthDay = monthDay,
    monthDays = com.daigan.record.data.model.parseMonthlyDates(monthDays) ?: setOfNotNull(monthDay),
    sortOrder = sortOrder,
    activeFrom = activeFrom?.let(java.time.LocalDate::parse),
    activeUntil = activeUntil?.let(java.time.LocalDate::parse),
    numericMode = numericMode,
)

fun TemplatePricePresetEntity.toModel(): TemplatePricePreset = TemplatePricePreset(
    id = id,
    amountCents = amountCents,
    label = label,
    sortOrder = sortOrder,
)

fun GameTemplateEntity.toModel(
    categories: List<TemplateCategoryEntity>,
    tasks: List<TemplateTaskEntity>,
    pricePresets: List<TemplatePricePresetEntity> = emptyList(),
): GameTemplate {
    val tasksByCategory = tasks.groupBy { it.categoryId }
    return GameTemplate(
        id = id,
        gameName = gameName,
        templateVersion = templateVersion,
        gameVersionNotes = gameVersionNotes,
        refresh = refreshDomain(),
        origin = origin,
        builtInKey = builtInKey,
        createdAt = createdAt,
        updatedAt = updatedAt,
        categories = categories
            .sortedBy { it.sortOrder }
            .map { category ->
                TemplateCategory(
                    id = category.id,
                    templateId = category.templateId,
                    name = category.name,
                    sortOrder = category.sortOrder,
                    tasks = tasksByCategory[category.id].orEmpty()
                        .sortedBy { it.sortOrder }
                        .map(TemplateTaskEntity::toModel),
                    hostingMode = category.hostingMode,
                    targetSessions = category.targetSessions,
                    serviceWeekdays = category.serviceWeekdays,
                )
            },
        sortOrder = sortOrder,
        pricePresets = pricePresets
            .filter { it.templateId == id }
            .sortedBy { it.sortOrder }
            .map(TemplatePricePresetEntity::toModel),
        // null means "inherit the category consensus"; it is never rewritten to DATE_RANGE
        // on read, otherwise an explicit DATE_RANGE and an inherited one could not be told apart.
        hostingMode = hostingMode,
        targetSessions = targetSessions,
        serviceWeekdays = serviceWeekdays,
        defaultOrderTitle = defaultOrderTitle,
    )
}

fun GameTemplateEntity.toSummary(
    categories: List<TemplateCategoryEntity>,
    tasks: List<TemplateTaskEntity>,
): GameTemplateSummary {
    val categoryIds = categories.map { it.id }.toSet()
    val templateTasks = tasks.filter { it.categoryId in categoryIds }
    return GameTemplateSummary(
        id = id,
        gameName = gameName,
        templateVersion = templateVersion,
        gameVersionNotes = gameVersionNotes,
        refresh = refreshDomain(),
        origin = origin,
        categoryCount = categories.size,
        taskCount = templateTasks.size,
        hostingTaskCount = templateTasks.count { it.recurrence != RecurrenceType.ONE_TIME },
        shortTaskCount = templateTasks.count { it.recurrence == RecurrenceType.ONE_TIME },
        sortOrder = sortOrder,
        // The picker only needs a display value; inherit-vs-explicit is preserved on the
        // full GameTemplate model, not in this lightweight row.
        hostingMode = hostingMode ?: com.daigan.record.data.model.HostingMode.DATE_RANGE,
        targetSessions = targetSessions,
    )
}

