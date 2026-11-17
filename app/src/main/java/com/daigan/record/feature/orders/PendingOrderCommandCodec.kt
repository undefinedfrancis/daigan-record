package com.daigan.record.feature.orders

import com.daigan.record.data.model.HostingMode
import com.daigan.record.data.model.NumericMode
import com.daigan.record.data.model.PaymentChannel
import com.daigan.record.data.model.PaymentMode
import com.daigan.record.data.model.ProgressType
import com.daigan.record.data.model.RecurrenceType
import com.daigan.record.data.model.TaskDraft
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.util.Base64

/** The part of a pending create-order command that must survive process restoration. */
internal data class RestoredOrderCommand(
    val form: RestoredOrderForm,
    val creationOperationId: String,
    val initialPaymentOperationId: String,
    val initialReceivedAtMillis: Long,
)

internal data class RestoredOrderForm(
    val isShort: Boolean,
    /** Absent in payloads written before count-based hosting; then date-based. */
    val hostingMode: HostingMode = HostingMode.DATE_RANGE,
    val targetSessionsText: String = "",
    val title: String,
    val priceYuan: String,
    val paymentMode: PaymentMode,
    val deadlineDate: LocalDate?,
    val notes: String,
    val startDate: LocalDate,
    val endDate: LocalDate,
    val durationText: String,
    val zoneText: String,
    val dailyRefresh: LocalTime,
    val weeklyRefreshDay: DayOfWeek,
    val weeklyRefreshTime: LocalTime,
    /** 每月刷新日文本（1–31，逗号分隔）；旧载荷没有此项时按周刷新。 */
    val monthlyRefreshDaysText: String = "",
    val tasks: List<TaskDraft>,
    val initialPaymentEnabled: Boolean,
    val initialAmountYuan: String,
    val initialChannel: PaymentChannel,
    val initialNote: String,
    /** Absent/false preserves the legacy short-command fingerprint on restoration. */
    val explicitShortCalendar: Boolean = false,
    /**
     * Template provenance as it was shown when the command was submitted. Optional and
     * absent in payloads written before Phase 4, so decoding stays backward compatible.
     */
    val sourceTemplateId: String? = null,
    val sourceTemplateName: String? = null,
    val sourceTemplateVersion: String? = null,
    val serviceDayMode: Boolean = false,
    val serviceWeekdays: Set<DayOfWeek> = emptySet(),
)

/**
 * Stores the exact submitted command in [androidx.lifecycle.SavedStateHandle].
 *
 * The create-order command must stay identical across a pending request, an in-app
 * retry and process restoration; otherwise a retry after process death would allocate
 * a new order. The payload is therefore encoded as a small, versioned text value with
 * length-safe Base64 fields (titles may contain any character). Decoding never throws:
 * a malformed value simply falls back to the visible form defaults.
 */
internal object PendingOrderCommandCodec {

    private const val VERSION = "v1"
    private const val FIELD_SEPARATOR = '\u001F'

    fun encode(
        form: RestoredOrderForm,
        creationOperationId: String,
        initialPaymentOperationId: String,
        initialReceivedAtMillis: Long,
    ): String {
        val lines = mutableListOf<String>()
        fun put(key: String, value: String?) {
            lines += key + "=" + encodeText(value.orEmpty())
        }
        lines += "version=" + VERSION
        put("isShort", form.isShort.toString())
        put("hostingMode", form.hostingMode.name)
        put("targetSessionsText", form.targetSessionsText)
        put("title", form.title)
        put("priceYuan", form.priceYuan)
        put("paymentMode", form.paymentMode.name)
        put("deadlineDate", form.deadlineDate?.toString())
        put("notes", form.notes)
        put("startDate", form.startDate.toString())
        put("endDate", form.endDate.toString())
        put("durationText", form.durationText)
        put("serviceDayMode", form.serviceDayMode.toString())
        put("serviceWeekdays", form.serviceWeekdays.joinToString(",") { it.name })
        put("zoneText", form.zoneText)
        put("dailyRefresh", form.dailyRefresh.toString())
        put("weeklyRefreshDay", form.weeklyRefreshDay.name)
        put("weeklyRefreshTime", form.weeklyRefreshTime.toString())
        put("monthlyRefreshDays", form.monthlyRefreshDaysText)
        put("taskCount", form.tasks.size.toString())
        form.tasks.forEachIndexed { index, task ->
            put(
                "task.$index",
                listOf(
                    task.title,
                    task.progressType.name,
                    task.startValue,
                    task.targetValue,
                    task.unit,
                    task.recurrence.name,
                    task.weekdays.sortedBy { it.value }.joinToString(",") { it.name },
                    task.everyNDays,
                    task.monthDay,
                    task.numericMode?.name.orEmpty(),
                    task.activeFrom,
                    task.activeUntil,
                    task.colorArgb?.toString().orEmpty(),
                ).joinToString(FIELD_SEPARATOR.toString()),
            )
        }
        put("initialPaymentEnabled", form.initialPaymentEnabled.toString())
        put("initialAmountYuan", form.initialAmountYuan)
        put("initialChannel", form.initialChannel.name)
        put("initialNote", form.initialNote)
        put("explicitShortCalendar", form.explicitShortCalendar.toString())
        put("sourceTemplateId", form.sourceTemplateId)
        put("sourceTemplateName", form.sourceTemplateName)
        put("sourceTemplateVersion", form.sourceTemplateVersion)
        put("creationOperationId", creationOperationId)
        put("initialPaymentOperationId", initialPaymentOperationId)
        put("initialReceivedAtMillis", initialReceivedAtMillis.toString())
        return lines.joinToString("\n")
    }

    fun decode(raw: String): RestoredOrderCommand? = runCatching {
        val values = raw.lineSequence()
            .filter { it.isNotBlank() }
            .associate { line ->
                val index = line.indexOf('=')
                check(index > 0) { "malformed line" }
                line.substring(0, index) to line.substring(index + 1)
            }
        check(values["version"] == VERSION) { "unsupported version" }
        fun text(key: String): String = decodeText(checkNotNull(values[key]) { "missing $key" })
        fun optional(key: String): String? = text(key).takeIf { it.isNotEmpty() }
        fun addedOptional(key: String): String? = values[key]?.let(::decodeText)?.takeIf { it.isNotEmpty() }
        val taskCount = text("taskCount").toInt()
        val tasks = (0 until taskCount).map { index ->
            val fields = text("task.$index").split(FIELD_SEPARATOR)
            check(fields.size in 8..13) { "malformed task" }
            TaskDraft(
                title = fields[0],
                progressType = ProgressType.valueOf(fields[1]),
                startValue = fields[2],
                targetValue = fields[3],
                unit = fields[4],
                recurrence = RecurrenceType.valueOf(fields[5]),
                weekdays = fields[6].split(",")
                    .filter { it.isNotBlank() }
                    .map(DayOfWeek::valueOf)
                    .toSet(),
                everyNDays = fields[7],
                monthDay = fields.getOrElse(8) { "" },
                numericMode = fields.getOrElse(9) { "" }
                    .takeIf { it.isNotEmpty() }
                    ?.let { runCatching { NumericMode.valueOf(it) }.getOrNull() },
                activeFrom = fields.getOrElse(10) { "" },
                activeUntil = fields.getOrElse(11) { "" },
                colorArgb = fields.getOrElse(12) { "" }.toIntOrNull(),
            )
        }
        RestoredOrderCommand(
            form = RestoredOrderForm(
                isShort = text("isShort").toBooleanStrict(),
                // Optional keys: a payload written before count-based hosting has none,
                // and must restore as date-based with no fabricated target.
                hostingMode = addedOptional("hostingMode")
                    ?.let { runCatching { HostingMode.valueOf(it) }.getOrNull() }
                    ?: HostingMode.DATE_RANGE,
                targetSessionsText = addedOptional("targetSessionsText").orEmpty(),
                title = text("title"),
                priceYuan = text("priceYuan"),
                paymentMode = PaymentMode.valueOf(text("paymentMode")),
                deadlineDate = optional("deadlineDate")?.let(LocalDate::parse),
                notes = text("notes"),
                startDate = LocalDate.parse(text("startDate")),
                endDate = LocalDate.parse(text("endDate")),
                durationText = text("durationText"),
                serviceDayMode = addedOptional("serviceDayMode")?.toBooleanStrictOrNull() ?: false,
                serviceWeekdays = addedOptional("serviceWeekdays")?.split(",")?.mapNotNull { runCatching { DayOfWeek.valueOf(it) }.getOrNull() }?.toSet().orEmpty(),
                zoneText = text("zoneText"),
                dailyRefresh = LocalTime.parse(text("dailyRefresh")),
                weeklyRefreshDay = DayOfWeek.valueOf(text("weeklyRefreshDay")),
                weeklyRefreshTime = LocalTime.parse(text("weeklyRefreshTime")),
                monthlyRefreshDaysText = addedOptional("monthlyRefreshDays").orEmpty(),
                tasks = tasks,
                initialPaymentEnabled = text("initialPaymentEnabled").toBooleanStrict(),
                initialAmountYuan = text("initialAmountYuan"),
                initialChannel = PaymentChannel.valueOf(text("initialChannel")),
                initialNote = text("initialNote"),
                // Optional keys: a v1 payload written before Phase 4 simply has none.
                explicitShortCalendar = addedOptional("explicitShortCalendar")?.toBooleanStrict() ?: false,
                sourceTemplateId = addedOptional("sourceTemplateId"),
                sourceTemplateName = addedOptional("sourceTemplateName"),
                sourceTemplateVersion = addedOptional("sourceTemplateVersion"),
            ),
            creationOperationId = text("creationOperationId"),
            initialPaymentOperationId = text("initialPaymentOperationId"),
            initialReceivedAtMillis = text("initialReceivedAtMillis").toLong(),
        )
    }.getOrNull()

    private fun encodeText(value: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(Charsets.UTF_8))

    private fun decodeText(value: String): String =
        String(Base64.getUrlDecoder().decode(value), Charsets.UTF_8)
}
