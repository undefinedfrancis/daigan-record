package com.daigan.record.backup

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase
import com.daigan.record.core.time.TimeProvider
import com.daigan.record.data.local.RecordDatabase
import com.daigan.record.data.local.entity.AppSettingsEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

/**
 * Raw, catalog-driven access to the durable tables.
 *
 * The backup path deliberately does not rely on hand-written per-entity mappers: the
 * catalog is the single list of durable columns, so a newly added table cannot silently
 * be left out of an export. Generic inserts are only ever executed after the whole
 * payload has been validated in an isolated staging database.
 */
@Singleton
class BackupDatabaseAccess @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: RecordDatabase,
    private val timeProvider: TimeProvider,
) {

    /** One coherent read of every durable row, inside a single database transaction. */
    suspend fun dump(): BackupDataSet = database.withTransaction {
        dumpRaw(database.openHelper.writableDatabase)
    }

    fun dumpRaw(db: SupportSQLiteDatabase): BackupDataSet {
        val tables = BackupTableCatalog.includedTables.map { table ->
            // 只查询**真实存在**的列：历史网页扩展列（serviceMode/serviceCount）在便携
            // 目录里保留读取能力，但本机 schema 没有它们。
            val written = table.columns.filterNot { it.legacyOnly }
            val columns = written.joinToString(", ") { "`${it.name}`" }
            val rows = ArrayList<List<JsonElement?>>()
            db.query("SELECT $columns FROM `${table.name}`").use { cursor ->
                while (cursor.moveToNext()) {
                    val full = MutableList<JsonElement?>(table.columns.size) { null }
                    written.forEachIndexed { index, column ->
                        val position = table.columnNames.indexOf(column.name)
                        full[position] = when (cursor.getType(index)) {
                            android.database.Cursor.FIELD_TYPE_NULL -> null
                            android.database.Cursor.FIELD_TYPE_INTEGER -> {
                                if (column.type != BackupTableCatalog.ColumnType.INTEGER) {
                                    throw BackupException(
                                        "数据表「${table.name}」的「${column.name}」类型与预期不一致，" +
                                            "已停止导出。",
                                    )
                                }
                                JsonPrimitive(cursor.getLong(index))
                            }

                            android.database.Cursor.FIELD_TYPE_STRING -> {
                                if (column.type != BackupTableCatalog.ColumnType.TEXT) {
                                    throw BackupException(
                                        "数据表「${table.name}」的「${column.name}」类型与预期不一致，" +
                                            "已停止导出。",
                                    )
                                }
                                JsonPrimitive(cursor.getString(index))
                            }

                            else -> throw BackupException(
                                "数据表「${table.name}」包含无法备份的二进制或浮点值，已停止导出。",
                            )
                        }
                    }
                    rows += full
                }
            }
            BackupDataSet.TableRows(table, rows)
        }
        return BackupDataSet(tables)
    }

    fun deleteAll(db: SupportSQLiteDatabase) {
        // `task_progress_events.correctsEventId` 是自引用 RESTRICT 外键，SQLite 会**逐行**
        // 立即检查，因此必须由叶子向内逐层删除。事件链在契约里保证无环，这里的循环次数
        // 只是防御上限；最后仍会无条件清空整表。
        var remaining = 1
        var guard = 0
        while (remaining > 0 && guard++ < 64) {
            db.execSQL(
                "DELETE FROM `task_progress_events` WHERE `id` NOT IN (" +
                    "SELECT `correctsEventId` FROM `task_progress_events` " +
                    "WHERE `correctsEventId` IS NOT NULL)",
            )
            db.query("SELECT COUNT(*) FROM `task_progress_events`").use { cursor ->
                remaining = if (cursor.moveToFirst()) cursor.getInt(0) else 0
            }
        }
        BackupTableCatalog.deletionOrder.forEach { name ->
            db.execSQL("DELETE FROM `$name`")
        }
    }

    fun insertAll(db: SupportSQLiteDatabase, dataSet: BackupDataSet) {
        // 便携契约只固定**表**顺序，不固定表内行顺序。`task_progress_events.correctsEventId`、
        // `receipts.replacedByReceiptId`、`receipt_corrections.newReceiptId` 等自引用外键，
        // 以及同一张表里互相引用的行，可能由别的平台按主键序写出（被引用行在后面）。
        // 这里把外键检查推迟到同一事务提交时：仍然严格（提交时悬空即整笔回滚），
        // 但不再依赖写入行的先后。调用方都在事务内（staging 校验与整体替换）。
        db.execSQL("PRAGMA defer_foreign_keys = ON")
        dataSet.tables.forEach { tableRows ->
            val table = tableRows.table
            if (tableRows.rows.isEmpty()) return@forEach
            // 目标库没有 legacy-only 列：只写便携目录中真实存在的列。
            val written = table.columns.filterNot { it.legacyOnly }
            val positions = written.map { table.columnNames.indexOf(it.name) }
            val columns = written.joinToString(", ") { "`${it.name}`" }
            val placeholders = written.joinToString(", ") { "?" }
            val statement = db.compileStatement(
                "INSERT INTO `${table.name}` ($columns) VALUES ($placeholders)",
            )
            try {
                tableRows.rows.forEach { row ->
                    statement.clearBindings()
                    positions.forEachIndexed { index, position ->
                        val value = row.getOrNull(position)
                        val bindIndex = index + 1
                        when {
                            // 归一化/重建阶段用 `JsonNull` 表示空值，解码阶段用 Kotlin null；
                            // 两者都必须是 SQL NULL（`JsonNull` 不是 Kotlin null）。
                            value == null || value is JsonNull -> statement.bindNull(bindIndex)
                            value is JsonPrimitive && value.isString ->
                                statement.bindString(bindIndex, value.content)

                            else -> statement.bindLong(
                                bindIndex,
                                (value as JsonPrimitive).content.toLongOrNull()
                                    ?: throw BackupException(
                                        "备份的数据表「${table.name}」的「${written[index].name}」" +
                                            "包含无法写入的数值（${(value as JsonPrimitive).content}），无法恢复。",
                                    ),
                            )
                        }
                    }
                    statement.executeInsert()
                }
            } catch (error: Throwable) {
                // 引用关系（含延迟到提交的 RESTRICT 外键）不成立时，数据库异常本身对用户
                // 没有意义；按表给对象级说明，并把原始异常保留为 cause。
                if (error is BackupException) throw error
                throw BackupException(
                    "备份的数据表「${table.name}」的记录与其它表不一致，无法恢复（数据未写入本机）。",
                    error,
                )
            } finally {
                statement.close()
            }
        }
        // 延迟外键的检查发生在提交时；这里显式先跑一次，让失败也落在"按表"的诊断上。
        db.query("PRAGMA foreign_key_check").use { cursor ->
            if (cursor.moveToFirst()) {
                val table = cursor.getString(0)
                throw BackupException(
                    "备份的数据表「$table」的引用关系不成立，无法恢复（数据未写入本机）。",
                )
            }
        }
    }

    /**
     * The destructive step. One Room transaction deletes every durable row, inserts the
     * validated dataset, and writes the new dataset identity together with the restore
     * marker. `lastRestoreId` is the durable commit point: its presence proves on the
     * next start that the database replacement committed, regardless of which journal
     * phase was persisted last.
     *
     * If this transaction fails for any reason, SQLite rolls it back and the original
     * dataset remains intact.
     */
    suspend fun replaceAll(
        dataSet: BackupDataSet,
        restoreId: String,
        newDatasetInstanceId: String = UUID.randomUUID().toString(),
    ) = database.withTransaction {
        val raw = database.openHelper.writableDatabase
        deleteAll(raw)
        insertAll(raw, dataSet)
        database.appSettingsDao().upsert(
            AppSettingsEntity(
                id = AppSettingsEntity.SINGLETON_ID,
                datasetInstanceId = newDatasetInstanceId,
                lastRestoreId = restoreId,
                createdAt = timeProvider.now(),
            ),
        )
        database.reminderDeliveryDao().deleteAll()
        Unit
    }

    /**
     * Opens an isolated staging database with the production schema. It never touches
     * the live file; validation runs against this copy and the file is deleted afterwards.
     */
    fun openStagingDatabase(stagingName: String): RecordDatabase = Room.databaseBuilder(
        context,
        RecordDatabase::class.java,
        stagingName,
    ).build()

    /** The files Room creates for a staging database name (main, WAL and SHM). */
    fun stagingFiles(stagingName: String): List<File> {
        val base = context.getDatabasePath(stagingName)
        return listOf(base, File("${base.path}-wal"), File("${base.path}-shm"))
    }

    /** Ensures a local-only identity exists, creating one on first use. */
    suspend fun ensureDatasetIdentity(): String {
        val existing = database.appSettingsDao().datasetInstanceId()
        if (existing != null) return existing
        val created = UUID.randomUUID().toString()
        database.withTransaction {
            if (database.appSettingsDao().datasetInstanceId() == null) {
                database.appSettingsDao().upsert(
                    AppSettingsEntity(
                        id = AppSettingsEntity.SINGLETON_ID,
                        datasetInstanceId = created,
                        lastRestoreId = null,
                        createdAt = timeProvider.now(),
                    ),
                )
            }
        }
        return database.appSettingsDao().datasetInstanceId() ?: created
    }

    suspend fun currentRestoreMarker(): String? = withContext(Dispatchers.IO) {
        database.appSettingsDao().lastRestoreId()
    }

    suspend fun clearRestoreMarker() {
        database.appSettingsDao().setLastRestoreId(null)
    }

    suspend fun now(): Instant = timeProvider.now()
}
