package com.daigan.record.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.daigan.record.data.model.HostingMode
import com.daigan.record.data.model.NumericMode
import com.daigan.record.data.model.ProgressType
import com.daigan.record.data.model.RecurrenceType
import com.daigan.record.data.model.TemplateOrigin
import java.math.BigDecimal
import java.time.Instant

/**
 * One editable game template.
 *
 * Templates are **creation defaults**, not business records: orders copy their tasks and
 * refresh snapshot at creation and keep no foreign key back to this table. Deleting a
 * template therefore cannot delete, rewrite or re-date an order, period or receipt.
 *
 * [builtInKey] is the stable identity of a shipped template. A unique index makes
 * double-seeding impossible; the seed marker below remembers that a built-in was
 * already installed once, so deleting it is a user decision and is not undone on the
 * next launch.
 */
@Entity(
    tableName = "game_templates",
    indices = [
        Index(
            value = ["builtInKey"],
            unique = true,
            name = "index_game_templates_builtInKey",
        ),
    ],
)
data class GameTemplateEntity(
    @PrimaryKey val id: String,
    val gameName: String,
    val templateVersion: String,
    val gameVersionNotes: String?,
    val zoneId: String,
    val dailyRefreshMinutes: Int,
    val weeklyRefreshDay: Int,
    val weeklyRefreshMinutes: Int,
    val origin: TemplateOrigin,
    val builtInKey: String?,
    /** Stable display order; built-ins use 0/1/2 in the requested preparation order. */
    val sortOrder: Int,
    val createdAt: Instant,
    val updatedAt: Instant,
    /**
     * Hosting mode proposed for new orders (UF-034). NULL = date-based, including every
     * template written before schema 13; a template edit never rewrites an order.
     */
    val hostingMode: HostingMode? = null,
    /** Proposed check-in round count; NULL unless [hostingMode] is count-based. */
    val targetSessions: Int? = null,
    /** 服务星期集合（逗号分隔 ISO 1–7，schema 15）；配合 DATE_RANGE 表示"仅工作日"。 */
    val serviceWeekdays: String? = null,
    /** 建单默认标题（UF-036，schema 15）；NULL = 不预填。 */
    val defaultOrderTitle: String? = null,
)

@Entity(
    tableName = "template_categories",
    foreignKeys = [
        ForeignKey(
            entity = GameTemplateEntity::class,
            parentColumns = ["id"],
            childColumns = ["templateId"],
            onDelete = ForeignKey.RESTRICT,
            onUpdate = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["templateId"], name = "index_template_categories_templateId")],
)
data class TemplateCategoryEntity(
    @PrimaryKey val id: String,
    val templateId: String,
    val name: String,
    val sortOrder: Int,
    /**
     * 分类级托管方式（schema 15）。NULL = **继承模板**；DATE_RANGE 是显式按日期。
     *
     * 历史网页分类的 null 来自保存路径把 'dates' 归一，因此迁移时被解释为显式
     * DATE_RANGE，绝不当成继承（见 `category-service-defaults`）。
     */
    val hostingMode: HostingMode? = null,
    val targetSessions: Int? = null,
    val serviceWeekdays: String? = null,
)

/**
 * One task option inside a template category. [recurrence] uses the same enum as order
 * tasks; `ONE_TIME` explicitly means "short order only" and is rejected by a hosting
 * order instead of being reinterpreted as a daily occurrence.
 */
@Entity(
    tableName = "template_tasks",
    foreignKeys = [
        ForeignKey(
            entity = GameTemplateEntity::class,
            parentColumns = ["id"],
            childColumns = ["templateId"],
            onDelete = ForeignKey.RESTRICT,
            onUpdate = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = TemplateCategoryEntity::class,
            parentColumns = ["id"],
            childColumns = ["categoryId"],
            onDelete = ForeignKey.RESTRICT,
            onUpdate = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["templateId"], name = "index_template_tasks_templateId"),
        Index(value = ["categoryId"], name = "index_template_tasks_categoryId"),
    ],
)
data class TemplateTaskEntity(
    @PrimaryKey val id: String,
    val templateId: String,
    val categoryId: String,
    val title: String,
    val progressType: ProgressType,
    val startValue: BigDecimal,
    val targetValue: BigDecimal,
    val unit: String?,
    val recurrence: RecurrenceType,
    val weekdays: String?,
    val everyNDays: Int?,
    val sortOrder: Int,
    val monthDay: Int? = null,
    val monthDays: String? = null,
    /** 任务生效起止日期（schema 15），建单时作为默认值。 */
    val activeFrom: String? = null,
    val activeUntil: String? = null,
    /** 数值口径默认值（schema 15）；NULL 读作 PER_OCCURRENCE。 */
    val numericMode: NumericMode? = null,
)

/**
 * One price option a template offers when an order is created from it.
 *
 * Like categories and tasks, presets are **creation defaults**: an order copies the
 * chosen amount into `orders.priceCents` and never references this row. A preset is
 * therefore free to be edited or deleted at any time without touching a stored order.
 */
@Entity(
    tableName = "template_price_presets",
    foreignKeys = [
        ForeignKey(
            entity = GameTemplateEntity::class,
            parentColumns = ["id"],
            childColumns = ["templateId"],
            onDelete = ForeignKey.RESTRICT,
            onUpdate = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["templateId"], name = "index_template_price_presets_templateId")],
)
data class TemplatePricePresetEntity(
    @PrimaryKey val id: String,
    val templateId: String,
    /** Integer cents, so a stored amount can never drift by a rounding step. */
    val amountCents: Long,
    val label: String?,
    val sortOrder: Int,
)

/**
 * One row per built-in template key that was already installed. The row survives
 * deleting the template, so initialization cannot resurrect a user's deletion or
 * overwrite edited content on a later launch.
 */
@Entity(tableName = "template_seed_state")
data class TemplateSeedStateEntity(
    @PrimaryKey val seedKey: String,
    val appliedAt: Instant,
)
