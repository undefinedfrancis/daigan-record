package com.daigan.record.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.daigan.record.data.model.ProgressEventKind
import com.daigan.record.data.model.ProgressScopeType
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

/**
 * 全局扁平账号分类（UF-037，schema 15）。
 *
 * 分类是**全局**的，不属于某个老板：两位老板的账号可以归入同一个分类。名称唯一
 * （trim 后精确查重）。删除分类时在同一个事务里把成员账号置为未分类，绝不级联删除账号；
 * 因此 `game_accounts.categoryId` 是逻辑外键，没有 SQL FK。
 */
@Entity(
    tableName = "account_categories",
    indices = [
        Index(value = ["name"], unique = true, name = "index_account_categories_name"),
        Index(value = ["sortOrder"], name = "index_account_categories_sortOrder"),
    ],
)
data class AccountCategoryEntity(
    @PrimaryKey val id: String,
    val name: String,
    val sortOrder: Int,
    val createdAt: Instant,
    val updatedAt: Instant,
)

/**
 * 一次累计进度观测（UF-038，schema 15）。
 *
 * 追加式、不可改写：`scope` 在写入时冻结（显式不可变 scope），更正/撤回通过**新增**
 * 指向被更正事件的行动作，绝不更新历史行。`totalValue` 是当时累计总值的快照
 * （签名缩放到 3 位小数的十进制文本），`RETRACT` 时为 NULL。
 *
 * `sequence` 是任务内全局递增序号（1 起），并且是唯一的排序权威 —— 时间戳永远不作为
 * 业务解析顺序的依据。
 */
@Entity(
    tableName = "task_progress_events",
    foreignKeys = [
        ForeignKey(
            entity = TaskDefinitionEntity::class,
            parentColumns = ["id"],
            childColumns = ["taskDefinitionId"],
            onDelete = ForeignKey.RESTRICT,
            onUpdate = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = TaskProgressEventEntity::class,
            parentColumns = ["id"],
            childColumns = ["correctsEventId"],
            onDelete = ForeignKey.RESTRICT,
            onUpdate = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["taskDefinitionId"], name = "index_task_progress_events_taskDefinitionId"),
        Index(value = ["operationId"], unique = true, name = "index_task_progress_events_operationId"),
        Index(
            value = ["taskDefinitionId", "sequence"],
            unique = true,
            name = "index_task_progress_events_taskDefinitionId_sequence",
        ),
        Index(value = ["scopePeriodId"], name = "index_task_progress_events_scopePeriodId"),
        Index(value = ["scopeSessionId"], name = "index_task_progress_events_scopeSessionId"),
        Index(value = ["gameDate"], name = "index_task_progress_events_gameDate"),
        Index(value = ["correctsEventId"], name = "index_task_progress_events_correctsEventId"),
    ],
)
data class TaskProgressEventEntity(
    @PrimaryKey val id: String,
    val taskDefinitionId: String,
    val operationId: String,
    val kind: ProgressEventKind,
    val scopeType: ProgressScopeType,
    val scopePeriodId: String?,
    val scopeSessionId: String?,
    val gameDate: LocalDate,
    val totalValue: BigDecimal?,
    val sequence: Int,
    val correctsEventId: String?,
    val recordedAt: Instant,
    val createdAt: Instant,
)
