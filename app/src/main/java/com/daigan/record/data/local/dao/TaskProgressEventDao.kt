package com.daigan.record.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.daigan.record.data.local.entity.TaskProgressEventEntity
import kotlinx.coroutines.flow.Flow

/**
 * 累计进度事件（UF-038，schema 15）。
 *
 * 只有追加与读取：没有 UPDATE、没有 DELETE。更正与撤回都通过**新增**指向旧事件的行
 * 表达，因此历史永远不会被改写。
 */
@Dao
interface TaskProgressEventDao {

    @Query("SELECT * FROM task_progress_events WHERE taskDefinitionId = :taskId ORDER BY sequence ASC")
    fun observeForTask(taskId: String): Flow<List<TaskProgressEventEntity>>

    @Query("SELECT * FROM task_progress_events WHERE taskDefinitionId = :taskId ORDER BY sequence ASC")
    suspend fun findForTask(taskId: String): List<TaskProgressEventEntity>

    /** 按任务批量读取（只覆盖给定任务，按任务分组、组内升序），供一次历史请求复用。 */
    @Query("SELECT * FROM task_progress_events WHERE taskDefinitionId IN (:taskIds) ORDER BY taskDefinitionId ASC, sequence ASC")
    suspend fun findForTasks(taskIds: List<String>): List<TaskProgressEventEntity>

    @Query("SELECT * FROM task_progress_events ORDER BY taskDefinitionId ASC, sequence ASC")
    suspend fun findAll(): List<TaskProgressEventEntity>

    @Query("SELECT * FROM task_progress_events WHERE operationId = :operationId LIMIT 1")
    suspend fun findByOperationId(operationId: String): TaskProgressEventEntity?

    @Query("SELECT MAX(sequence) FROM task_progress_events WHERE taskDefinitionId = :taskId")
    suspend fun maxSequence(taskId: String): Int?

    @Query("SELECT COUNT(*) FROM task_progress_events WHERE taskDefinitionId = :taskId")
    suspend fun countForTask(taskId: String): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(event: TaskProgressEventEntity)
}
