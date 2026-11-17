package com.daigan.record.data.template

import com.daigan.record.data.model.GameTemplate
import com.daigan.record.data.model.HostingMode
import com.daigan.record.data.model.Money
import com.daigan.record.data.model.NumericMode
import com.daigan.record.data.model.ProgressType
import com.daigan.record.data.model.RecurrenceType
import com.daigan.record.data.model.TemplateCategoryDraft
import com.daigan.record.data.model.TemplateDraft
import com.daigan.record.data.model.TemplatePricePreset
import com.daigan.record.data.model.TemplatePricePresetDraft
import com.daigan.record.data.model.TemplateTaskDraft
import java.math.BigDecimal
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.format.DateTimeParseException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** Outcome of reading one template file. Failure never mutates the library. */
sealed interface TemplateFileResult {
    data class Success(val content: TemplateFileContent) : TemplateFileResult

    data class Failure(val message: String) : TemplateFileResult
}

/**
 * Validated template content read from a file.
 *
 * [draft] keeps the ids written in the file so a built-in seed can preserve its stable
 * identity. An interactive import re-allocates ids before writing (see the repository)
 * so a file can never overwrite an unrelated row.
 */
data class TemplateFileContent(
    val templateId: String,
    val draft: TemplateDraft,
    val categoryCount: Int,
    val taskCount: Int,
    /** Per-game 区服 options carried by the file; empty when the field is absent. */
    val serverOptions: List<String> = emptyList(),
)

/**
 * UTF-8 JSON template exchange.
 *
 * The file contains **only** template content and format metadata. Owners, accounts,
 * credentials, orders, receipts and every other business table are never written, and a
 * file that carries unknown top-level or template fields is rejected rather than
 * silently merged.
 *
 * Compatibility policy: exactly one format version (1) is accepted. A file with any
 * other `formatVersion` or `format` is rejected with a clear message, so a future format
 * can never be misread by this build. Every collection, string and numeric limit is
 * checked here, before the repository is asked to write anything.
 */
object TemplateFileCodec {

    const val FORMAT_NAME = "daigan-template"

    /**
     * Version history:
     * 1 — the original template content plus optional per-game 区服 options.
     * 2 — adds the optional per-template price presets (`pricePresets`).
     * 3 — adds the optional hosting mode and agreed round count (`hostingMode`,
     *     `targetSessions`), so a 打卡式托管 template survives export/import.
     * 4 — the shared interop contract: category-level service defaults
     *     (`hostingMode`/`targetSessions`/`serviceWeekdays`), template-level
     *     `serviceWeekdays`/`defaultOrderTitle`, and task-level
     *     `activeFrom`/`activeUntil`/`numericMode`.
     *
     * Every version from 1 stays readable through the historical key sets declared
     * below; v4 writers and readers reject unknown keys at every level. A template file
     * never carries account categories, account ids, order ids or progress history.
     */
    const val FORMAT_VERSION = 4

    /** Oldest readable format version. A v1 file simply has no price presets. */
    const val MIN_READABLE_FORMAT_VERSION = 1

    /** Hard byte limit for a template file; also enforced while reading the stream. */
    const val MAX_FILE_BYTES = 256 * 1024

    /** Per-game 区服 option limits, mirroring the account field's 60-character cap. */
    const val MAX_SERVER_OPTIONS = 50
    const val MAX_SERVER_OPTION_LENGTH = 60

    private val json = Json {
        prettyPrint = true
        isLenient = false
        ignoreUnknownKeys = false
    }

    private val rootKeys = setOf("format", "formatVersion", "template")
    private val pricePresetKeys = setOf("id", "amountCents", "label")

    /** 每个版本允许的模板键集；历史版本另行放行它们当年写过的键。 */
    private fun templateKeysFor(version: Int): Set<String> {
        val keys = mutableSetOf(
            "id",
            "gameName",
            "templateVersion",
            "gameVersionNotes",
            "timeZone",
            "dailyRefresh",
            "weeklyRefreshDay",
            "weeklyRefreshTime",
            "categories",
            "serverOptions",
        )
        if (version >= 2) keys += "pricePresets"
        if (version >= 3) {
            keys += "hostingMode"
            keys += "targetSessions"
        }
        if (version >= 4) {
            keys += "serviceWeekdays"
            keys += "defaultOrderTitle"
        } else {
            // 网页 1/2 写过的 legacy 扩展键：只在历史版本里被接受。
            keys += "serviceMode"
            keys += "serviceCount"
        }
        return keys
    }

    private fun categoryKeysFor(version: Int): Set<String> = if (version >= 4) {
        setOf("id", "name", "hostingMode", "targetSessions", "serviceWeekdays", "tasks")
    } else {
        setOf("id", "name", "tasks", "serviceMode", "serviceCount")
    }

    private fun taskKeysFor(version: Int): Set<String> {
        val keys = mutableSetOf(
            "id",
            "categoryId",
            "title",
            "progressType",
            "startValue",
            "targetValue",
            "unit",
            "recurrence",
            "weekdays",
            "everyNDays",
        )
        if (version >= 2) {
            keys += "monthDay"
            keys += "monthDays"
            keys += "activeFrom"
            keys += "activeUntil"
        }
        if (version >= 4) keys += "numericMode"
        return keys
    }

    /**
     * Encodes one template with its game's 区服 options and its price presets. Both
     * optional collections are omitted when empty, so a template that uses neither
     * stays readable by an older build that only knows format version 1; a non-empty
     * collection is written and rejected by those strict old readers, which is
     * intentional — the data is not silently dropped.
     */
    fun encode(
        template: GameTemplate,
        serverOptions: List<String> = emptyList(),
        pricePresets: List<TemplatePricePreset> = template.pricePresets,
    ): ByteArray {
        val root = buildJsonObject {
            put("format", FORMAT_NAME)
            put("formatVersion", FORMAT_VERSION)
            putJsonObject("template") {
                put("id", template.id)
                put("gameName", template.gameName)
                put("templateVersion", template.templateVersion)
                put("gameVersionNotes", template.gameVersionNotes)
                put("timeZone", template.refresh.zoneId.id)
                put("dailyRefresh", minutesToText(template.refresh.dailyRefreshMinutes))
                put("weeklyRefreshDay", template.refresh.weeklyRefreshDay.name)
                put("weeklyRefreshTime", minutesToText(template.refresh.weeklyRefreshMinutes))
                // 模板级 = 分类共识镜像；DATE_RANGE 是显式按日期，null 只在"无意见"时使用。
                put(
                    "hostingMode",
                    template.hostingMode?.name?.let(::JsonPrimitive) ?: JsonNull,
                )
                if (template.hostingMode == HostingMode.COUNT_BASED) {
                    put("targetSessions", template.targetSessions?.let(::JsonPrimitive) ?: JsonNull)
                } else {
                    put("targetSessions", JsonNull)
                }
                put(
                    "serviceWeekdays",
                    template.serviceWeekdays?.let(::JsonPrimitive) ?: JsonNull,
                )
                put(
                    "defaultOrderTitle",
                    template.defaultOrderTitle?.takeIf { it.isNotBlank() }?.let(::JsonPrimitive) ?: JsonNull,
                )
                putJsonArray("categories") {
                    template.categories.forEach { category ->
                        add(
                            buildJsonObject {
                                put("id", category.id)
                                put("name", category.name)
                                put(
                                    "hostingMode",
                                    category.hostingMode?.name?.let(::JsonPrimitive) ?: JsonNull,
                                )
                                put(
                                    "targetSessions",
                                    category.targetSessions?.let(::JsonPrimitive) ?: JsonNull,
                                )
                                put(
                                    "serviceWeekdays",
                                    category.serviceWeekdays?.let(::JsonPrimitive) ?: JsonNull,
                                )
                                putJsonArray("tasks") {
                                    category.tasks.forEach { task ->
                                        add(
                                            buildJsonObject {
                                                put("id", task.id)
                                                put("categoryId", task.categoryId)
                                                put("title", task.title)
                                                put("progressType", task.progressType.name)
                                                put("startValue", task.startValue.toPlainString())
                                                put("targetValue", task.targetValue.toPlainString())
                                                put("unit", task.unit)
                                                put("recurrence", task.recurrence.name)
                                                put(
                                                    "weekdays",
                                                    buildJsonArray {
                                                        task.weekdays.sortedBy { it.value }
                                                            .forEach { add(JsonPrimitive(it.name)) }
                                                    },
                                                )
                                                put("monthDay", task.monthDay?.let(::JsonPrimitive) ?: JsonNull)
                                                put("monthDays", buildJsonArray { task.monthDays.ifEmpty { setOfNotNull(task.monthDay) }.sorted().forEach { add(JsonPrimitive(it)) } })
                                                put(
                                                    "everyNDays",
                                                    task.everyNDays?.let(::JsonPrimitive) ?: JsonNull,
                                                )
                                                put(
                                                    "activeFrom",
                                                    task.activeFrom?.toString()?.let(::JsonPrimitive) ?: JsonNull,
                                                )
                                                put(
                                                    "activeUntil",
                                                    task.activeUntil?.toString()?.let(::JsonPrimitive) ?: JsonNull,
                                                )
                                                put(
                                                    "numericMode",
                                                    task.numericMode?.name?.let(::JsonPrimitive) ?: JsonNull,
                                                )
                                            },
                                        )
                                    }
                                }
                            },
                        )
                    }
                }
                if (serverOptions.isNotEmpty()) {
                    putJsonArray("serverOptions") {
                        serverOptions.forEach { add(JsonPrimitive(it)) }
                    }
                }
                if (pricePresets.isNotEmpty()) {
                    putJsonArray("pricePresets") {
                        pricePresets.sortedBy { it.sortOrder }.forEach { preset ->
                            add(
                                buildJsonObject {
                                    put("id", preset.id)
                                    // Integer cents, never a float: an exported amount
                                    // must decode to exactly the price that was saved.
                                    put("amountCents", preset.amountCents)
                                    // Use an explicit branch: JsonObjectBuilder.put returns the
                                    // previous value, so using it inside `?:` would overwrite a
                                    // newly written non-empty label with JSON null.
                                    if (preset.label != null) {
                                        put("label", JsonPrimitive(preset.label))
                                    } else {
                                        put("label", JsonNull)
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
        return json.encodeToString(JsonObject.serializer(), root).toByteArray(StandardCharsets.UTF_8)
    }

    fun decode(bytes: ByteArray): TemplateFileResult {
        if (bytes.isEmpty()) return TemplateFileResult.Failure("文件为空，未导入任何内容")
        if (bytes.size > MAX_FILE_BYTES) {
            return TemplateFileResult.Failure(
                "文件超过 ${MAX_FILE_BYTES / 1024} KB 上限，未导入任何内容",
            )
        }
        val text = decodeUtf8(bytes)
            ?: return TemplateFileResult.Failure("文件不是有效的 UTF-8 文本")
        val root = runCatching { json.parseToJsonElement(text) }.getOrElse {
            return TemplateFileResult.Failure("文件不是有效的 JSON，未导入任何内容")
        }
        return try {
            TemplateFileResult.Success(parseRoot(root))
        } catch (error: TemplateFileException) {
            TemplateFileResult.Failure(error.message ?: "模板文件内容无效")
        }
    }

    private fun parseRoot(root: JsonElement): TemplateFileContent {
        val rootObject = root.objectOrThrow("模板文件根节点必须是 JSON 对象")
        rejectUnknownKeys(rootObject, rootKeys, "模板文件")
        val format = rootObject.requireString("format")
        if (format != FORMAT_NAME) {
            throw TemplateFileException("这不是本应用的模板文件（format=$format）")
        }
        val version = rootObject.requireInt("formatVersion")
        if (version !in MIN_READABLE_FORMAT_VERSION..FORMAT_VERSION) {
            throw TemplateFileException(
                "不支持的模板文件格式版本 $version；本版本支持 " +
                    "$MIN_READABLE_FORMAT_VERSION–$FORMAT_VERSION",
            )
        }
        val template = rootObject["template"]
            ?.objectOrThrow("template 必须是对象")
            ?: throw TemplateFileException("缺少必填字段：template")
        rejectUnknownKeys(template, templateKeysFor(version), "模板")
        val templateId = template.requireId("id")
        val gameName = template.requireString("gameName")
        val templateVersion = template.requireString("templateVersion")
        val notes = template.optionalString("gameVersionNotes")
        val zone = template.requireString("timeZone")
        val dailyRefresh = parseTime(template.requireString("dailyRefresh"), "每日刷新时间")
        val weeklyDay = parseDay(template.requireString("weeklyRefreshDay"))
        val weeklyTime = parseTime(template.requireString("weeklyRefreshTime"), "每周刷新时间")
        val serverOptions = parseServerOptions(template)

        val categoriesArray = template["categories"]
            ?.arrayOrThrow("categories 必须是数组")
            ?: throw TemplateFileException("缺少必填字段：categories")
        if (categoriesArray.isEmpty()) {
            throw TemplateFileException("模板至少需要一个分类")
        }
        if (categoriesArray.size > TemplateDraft.MAX_CATEGORIES) {
            throw TemplateFileException("分类数量超过 ${TemplateDraft.MAX_CATEGORIES} 个上限")
        }
        val seenIds = mutableSetOf<String>()
        fun uniqueId(raw: String): String {
            if (!seenIds.add(raw)) throw TemplateFileException("文件中的标识重复：$raw")
            return raw
        }
        var taskTotal = 0
        val categories = categoriesArray.mapIndexed { index, element ->
            val category = element.objectOrThrow("第 ${index + 1} 个分类必须是对象")
            rejectUnknownKeys(category, categoryKeysFor(version), "分类")
            val categoryId = uniqueId(category.requireId("id"))
            val name = category.requireString("name")
            val tasksArray = category["tasks"]
                ?.arrayOrThrow("tasks 必须是数组")
                ?: throw TemplateFileException("分类「$name」缺少必填字段：tasks")
            if (tasksArray.isEmpty()) {
                throw TemplateFileException("分类「$name」至少需要一个任务选项")
            }
            if (tasksArray.size > TemplateDraft.MAX_TASKS) {
                throw TemplateFileException("单个分类的任务数量超过 ${TemplateDraft.MAX_TASKS} 个上限")
            }
            taskTotal += tasksArray.size
            if (taskTotal > TemplateDraft.MAX_TASKS) {
                throw TemplateFileException("任务总数超过 ${TemplateDraft.MAX_TASKS} 个上限")
            }
            // 分类级默认值：v4 用 hostingMode/targetSessions/serviceWeekdays，
            // 历史网页格式用 serviceMode/serviceCount，缺列表示继承模板（null）。
            val legacyCategoryMode = category.optionalString("serviceMode")
            val categoryHostingMode = when {
                version >= 4 -> when (val raw = category.optionalString("hostingMode")) {
                    null -> null
                    HostingMode.DATE_RANGE.name -> HostingMode.DATE_RANGE
                    HostingMode.COUNT_BASED.name -> HostingMode.COUNT_BASED
                    else -> throw TemplateFileException("不支持的分类托管方式：$raw")
                }

                category.serviceModePresent() -> when (legacyCategoryMode) {
                    // 存 null = 保存路径把 'dates' 归一而来 → 显式按日期，不是继承。
                    null -> HostingMode.DATE_RANGE
                    "weekdays" -> HostingMode.DATE_RANGE
                    "count" -> HostingMode.COUNT_BASED
                    else -> throw TemplateFileException("不支持的分类服务模式：$legacyCategoryMode")
                }

                else -> null
            }
            val categoryTargetSessions = if (version >= 4) {
                category.optionalInt("targetSessions")
            } else {
                category.optionalInt("serviceCount")
            }
            if (categoryHostingMode == HostingMode.COUNT_BASED &&
                (categoryTargetSessions == null || categoryTargetSessions < 1)
            ) {
                throw TemplateFileException("分类计次模式需要大于 0 的目标次数")
            }
            val categoryWeekdays = parseServiceWeekdays(category.optionalString("serviceWeekdays"))
                ?: if (legacyCategoryMode == "weekdays") setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY,
                    DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY) else emptySet()

            val tasks = tasksArray.mapIndexed { taskIndex, taskElement ->
                val task = taskElement.objectOrThrow("第 ${taskIndex + 1} 个任务必须是对象")
                rejectUnknownKeys(task, taskKeysFor(version), "任务")
                val taskId = uniqueId(task.requireId("id"))
                val referencedCategory = task.requireString("categoryId")
                if (referencedCategory != categoryId) {
                    throw TemplateFileException(
                        "任务「${task.optionalString("title").orEmpty()}」的 categoryId 与所在分类不一致",
                    )
                }
                TemplateTaskDraft(
                    id = taskId,
                    title = task.requireString("title"),
                    progressType = parseProgressType(task.requireString("progressType")),
                    startValue = task.requireDecimalText("startValue"),
                    targetValue = task.requireDecimalText("targetValue"),
                    unit = task.optionalString("unit").orEmpty(),
                    recurrence = parseRecurrence(task.requireString("recurrence")),
                    weekdays = parseWeekdays(task),
                    everyNDays = task.optionalInt("everyNDays")?.toString().orEmpty(),
                    monthDay = (task["monthDays"] as? JsonArray)?.joinToString(",") { it.jsonPrimitive.content }
                        ?: task.optionalInt("monthDay")?.toString().orEmpty(),
                    activeFrom = parseOptionalDateText(task.optionalString("activeFrom"), "任务生效起始日"),
                    activeUntil = parseOptionalDateText(task.optionalString("activeUntil"), "任务生效截止日"),
                    numericMode = parseNumericMode(task.optionalString("numericMode")),
                )
            }
            TemplateCategoryDraft(
                id = categoryId,
                name = name,
                tasks = tasks,
                hostingMode = categoryHostingMode,
                targetSessionsText = categoryTargetSessions?.toString().orEmpty(),
                serviceWeekdays = encodeWeekdays(categoryWeekdays),
            )
        }

        // 模板级默认值：v1–v2 没有该字段（按日期）；v3 起可携带计次目标；
        // 历史网页格式还用 serviceMode/serviceCount 表达同一语义。
        val legacyMode = template.optionalString("serviceMode")
        val hostingMode = when (val raw = template.optionalString("hostingMode")) {
            null -> if (legacyMode == "count") HostingMode.COUNT_BASED else HostingMode.DATE_RANGE
            HostingMode.DATE_RANGE.name -> HostingMode.DATE_RANGE
            HostingMode.COUNT_BASED.name -> HostingMode.COUNT_BASED
            else -> throw TemplateFileException("不支持的托管方式：$raw")
        }
        val targetSessions = template.optionalInt("targetSessions")
            ?: template.optionalInt("serviceCount")
        if (hostingMode == HostingMode.COUNT_BASED && (targetSessions == null || targetSessions < 1)) {
            throw TemplateFileException("打卡式托管需要大于 0 的目标次数")
        }
        val templateWeekdays = parseServiceWeekdays(template.optionalString("serviceWeekdays"))
            ?: if (legacyMode == "weekdays") setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY,
                DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY) else emptySet()
        val defaultOrderTitle = template.optionalString("defaultOrderTitle")
            ?.trim()
            ?.take(60)
            ?.takeIf { it.isNotEmpty() }
            .orEmpty()
        val draft = TemplateDraft(
            gameName = gameName,
            templateVersion = templateVersion,
            gameVersionNotes = notes.orEmpty(),
            zoneText = zone,
            dailyRefresh = dailyRefresh,
            weeklyRefreshDay = weeklyDay,
            weeklyRefreshTime = weeklyTime,
            categories = categories,
            pricePresets = parsePricePresets(template, ::uniqueId),
            hostingMode = hostingMode,
            targetSessionsText = targetSessions?.toString().orEmpty(),
            serviceWeekdays = encodeWeekdays(templateWeekdays),
            defaultOrderTitle = defaultOrderTitle,
        )
        val errors = draft.validate()
        if (errors.isNotEmpty()) {
            throw TemplateFileException(errors.values.first())
        }
        return TemplateFileContent(
            templateId = templateId,
            draft = draft,
            categoryCount = categories.size,
            taskCount = taskTotal,
            serverOptions = serverOptions,
        )
    }

    /**
     * Optional per-game 区服 options. An absent field means "the file carries none" and
     * decodes to an empty list; present entries must be non-blank, unique and short.
     */
    private fun parseServerOptions(template: JsonObject): List<String> {
        val array = template["serverOptions"] as? JsonArray ?: return emptyList()
        if (array.size > MAX_SERVER_OPTIONS) {
            throw TemplateFileException("区服选项数量超过 $MAX_SERVER_OPTIONS 个上限")
        }
        val options = array.mapIndexed { index, element ->
            val text = (element as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?: throw TemplateFileException("第 ${index + 1} 个区服选项必须是文本")
            val trimmed = text.trim()
            if (trimmed.isEmpty()) throw TemplateFileException("区服选项不能为空")
            if (trimmed.length > MAX_SERVER_OPTION_LENGTH) {
                throw TemplateFileException("区服选项最多 $MAX_SERVER_OPTION_LENGTH 个字符")
            }
            trimmed
        }
        if (options.distinct().size != options.size) {
            throw TemplateFileException("文件中的区服选项重复")
        }
        return options
    }

    /**
     * Optional price presets (format 2). An absent field means "the file carries none"
     * and decodes to an empty list, which is exactly what a version-1 file holds.
     * Amounts are integer cents so an exported price decodes to the identical value;
     * `draft.validate()` then applies the same money rules the editor uses.
     */
    private fun parsePricePresets(
        template: JsonObject,
        uniqueId: (String) -> String,
    ): List<TemplatePricePresetDraft> {
        val array = template["pricePresets"] as? JsonArray ?: return emptyList()
        if (array.size > TemplateDraft.MAX_PRICE_PRESETS) {
            throw TemplateFileException(
                "价格预设数量超过 ${TemplateDraft.MAX_PRICE_PRESETS} 个上限",
            )
        }
        return array.mapIndexed { index, element ->
            val preset = element.objectOrThrow("第 ${index + 1} 个价格预设必须是对象")
            rejectUnknownKeys(preset, pricePresetKeys, "价格预设")
            TemplatePricePresetDraft(
                id = uniqueId(preset.requireId("id")),
                amountText = Money.centsToYuanText(preset.requireLong("amountCents")),
                label = preset.optionalString("label").orEmpty(),
            )
        }
    }

    private fun parseProgressType(raw: String): ProgressType = when (raw) {
        ProgressType.CHECKBOX.name -> ProgressType.CHECKBOX
        ProgressType.NUMERIC.name -> ProgressType.NUMERIC
        else -> throw TemplateFileException("不支持的任务记录方式：$raw")
    }

    private fun parseRecurrence(raw: String): RecurrenceType = when (raw) {
        RecurrenceType.ONE_TIME.name -> RecurrenceType.ONE_TIME
        RecurrenceType.DAILY.name -> RecurrenceType.DAILY
        RecurrenceType.WEEKLY.name -> RecurrenceType.WEEKLY
        RecurrenceType.SELECTED_WEEKDAYS.name -> RecurrenceType.SELECTED_WEEKDAYS
        RecurrenceType.MONTHLY.name -> RecurrenceType.MONTHLY
        RecurrenceType.EVERY_N_DAYS.name -> RecurrenceType.EVERY_N_DAYS
        else -> throw TemplateFileException("不支持的任务重复周期：$raw")
    }

    private fun parseDay(raw: String): DayOfWeek = runCatching { DayOfWeek.valueOf(raw) }
        .getOrElse { throw TemplateFileException("每周刷新星期无效：$raw") }

    private fun parseTime(raw: String, label: String): LocalTime = try {
        LocalTime.parse(raw)
    } catch (error: DateTimeParseException) {
        throw TemplateFileException("$label 格式无效：$raw")
    }

    private fun parseWeekdays(task: JsonObject): Set<DayOfWeek> {
        val element = task["weekdays"] ?: return emptySet()
        if (element is JsonNull) return emptySet()
        val array = element.arrayOrThrow("weekdays 必须是数组")
        return array.map { day ->
            val raw = day.requireStringValue("weekdays")
            runCatching { DayOfWeek.valueOf(raw) }
                .getOrElse { throw TemplateFileException("执行星期无效：$raw") }
        }.toSet()
    }

    private fun rejectUnknownKeys(objectValue: JsonObject, allowed: Set<String>, label: String) {
        val unknown = objectValue.keys - allowed
        if (unknown.isNotEmpty()) {
            throw TemplateFileException(
                "$label 包含不支持的字段：${unknown.sorted().joinToString("、")}",
            )
        }
    }

    private fun decodeUtf8(bytes: ByteArray): String? = runCatching {
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    }.getOrNull()

    private fun minutesToText(minutes: Int): String =
        "%02d:%02d".format(minutes / 60, minutes % 60)
}

/**
 * 服务星期在文件里用 ISO 1–7 的逗号文本。非法值直接拒绝，绝不静默丢弃。
 */
internal fun parseServiceWeekdays(raw: String?): Set<DayOfWeek>? {
    if (raw.isNullOrBlank()) return null
    val days = raw.split(',').map { it.trim() }
    if (days.isEmpty()) return null
    return days.map { token ->
        val value = token.toIntOrNull()
        if (value == null || value !in 1..7) {
            throw TemplateFileException("服务星期必须是 ISO 1–7：$token")
        }
        DayOfWeek.of(value)
    }.toSet()
}

internal fun encodeWeekdays(days: Set<DayOfWeek>): String =
    days.map { it.value }.sorted().joinToString(",")

private fun parseOptionalDateText(raw: String?, label: String): String {
    if (raw.isNullOrBlank()) return ""
    val date = runCatching { java.time.LocalDate.parse(raw) }.getOrNull()
        ?: throw TemplateFileException("$label 必须为 YYYY-MM-DD 日期")
    return date.toString()
}

private fun parseNumericMode(raw: String?): NumericMode? = when (raw) {
    null -> null
    NumericMode.PER_OCCURRENCE.name -> NumericMode.PER_OCCURRENCE
    NumericMode.CUMULATIVE.name -> NumericMode.CUMULATIVE
    else -> throw TemplateFileException("不支持的数值口径：$raw")
}

/** 该对象是否真的声明了 `serviceMode`（用于区分"缺列"与"存 null"）。 */
private fun JsonObject.serviceModePresent(): Boolean = containsKey("serviceMode")

private class TemplateFileException(message: String) : Exception(message)

private fun JsonElement.objectOrThrow(message: String): JsonObject =
    this as? JsonObject ?: throw TemplateFileException(message)

private fun JsonElement.arrayOrThrow(message: String): JsonArray =
    this as? JsonArray ?: throw TemplateFileException(message)

private fun JsonObject.requireString(key: String): String {
    val element = this[key] ?: throw TemplateFileException("缺少必填字段：$key")
    val primitive = element as? JsonPrimitive
        ?: throw TemplateFileException("字段 $key 必须是文本")
    if (primitive is JsonNull || !primitive.isString) {
        throw TemplateFileException("字段 $key 必须是文本")
    }
    val value = primitive.content
    if (value.isBlank()) throw TemplateFileException("字段 $key 不能为空")
    if (value.length > MAX_TEXT_FIELD_LENGTH) {
        throw TemplateFileException("字段 $key 超过 $MAX_TEXT_FIELD_LENGTH 个字符上限")
    }
    return value
}

private fun JsonObject.optionalString(key: String): String? {
    val element = this[key] ?: return null
    if (element is JsonNull) return null
    val primitive = element as? JsonPrimitive
        ?: throw TemplateFileException("字段 $key 必须是文本")
    if (!primitive.isString) throw TemplateFileException("字段 $key 必须是文本")
    if (primitive.content.length > MAX_NOTES_FIELD_LENGTH) {
        throw TemplateFileException("字段 $key 超过 $MAX_NOTES_FIELD_LENGTH 个字符上限")
    }
    return primitive.content.takeIf { it.isNotBlank() }
}

private fun JsonObject.requireInt(key: String): Int {
    val element = this[key] ?: throw TemplateFileException("缺少必填字段：$key")
    val primitive = element as? JsonPrimitive
        ?: throw TemplateFileException("字段 $key 必须是整数")
    return primitive.intOrNull ?: throw TemplateFileException("字段 $key 必须是整数")
}

private fun JsonObject.optionalInt(key: String): Int? {
    val element = this[key] ?: return null
    if (element is JsonNull) return null
    val primitive = element as? JsonPrimitive
        ?: throw TemplateFileException("字段 $key 必须是整数或 null")
    return primitive.intOrNull ?: throw TemplateFileException("字段 $key 必须是整数或 null")
}

private fun JsonObject.requireLong(key: String): Long {
    val element = this[key] ?: throw TemplateFileException("缺少必填字段：$key")
    val primitive = element as? JsonPrimitive
        ?: throw TemplateFileException("字段 $key 必须是整数")
    if (primitive is JsonNull) throw TemplateFileException("字段 $key 必须是整数")
    return primitive.longOrNull ?: throw TemplateFileException("字段 $key 必须是整数")
}

private fun JsonObject.requireDecimalText(key: String): String {
    val element = this[key] ?: throw TemplateFileException("缺少必填字段：$key")
    val primitive = element as? JsonPrimitive
        ?: throw TemplateFileException("字段 $key 必须是数值")
    if (primitive is JsonNull) throw TemplateFileException("字段 $key 必须是数值")
    val raw = primitive.content
    val value = runCatching { BigDecimal(raw) }.getOrNull()
        ?: throw TemplateFileException("字段 $key 不是有效数值")
    if (value.scale() > TemplateTaskDraft.MAX_DECIMAL_SCALE) {
        throw TemplateFileException("字段 $key 最多 ${TemplateTaskDraft.MAX_DECIMAL_SCALE} 位小数")
    }
    return raw
}

private fun String.requireId(key: String): String {
    if (isBlank()) throw TemplateFileException("字段 $key 不能为空")
    if (length > MAX_ID_LENGTH) throw TemplateFileException("字段 $key 过长")
    return this
}

private fun JsonObject.requireId(key: String): String = requireString(key).requireId(key)

private fun JsonElement.requireStringValue(label: String): String {
    val primitive = this as? JsonPrimitive
        ?: throw TemplateFileException("$label 必须是文本")
    if (primitive is JsonNull || !primitive.isString) {
        throw TemplateFileException("$label 必须是文本")
    }
    return primitive.content
}

private const val MAX_ID_LENGTH = 100
private const val MAX_TEXT_FIELD_LENGTH = 200
private const val MAX_NOTES_FIELD_LENGTH = 2000
