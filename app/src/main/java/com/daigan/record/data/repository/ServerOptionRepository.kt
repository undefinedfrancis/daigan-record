package com.daigan.record.data.repository

import com.daigan.record.data.local.RecordDatabase
import com.daigan.record.data.local.dao.ServerOptionDao
import com.daigan.record.data.local.entity.ServerOptionEntity
import com.daigan.record.data.template.TemplateFileCodec
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Per-game 区服 options (UF-005).
 *
 * Options are keyed by plain game name — deliberately not by template — so they survive
 * template deletion and can be maintained for games without a template. Every write only
 * touches `game_server_options`; `game_accounts.server` keeps whatever the user actually
 * saved on each account, even when an option is renamed or removed.
 */
interface ServerOptionRepository {
    fun observeForGame(gameName: String): Flow<List<String>>
    fun observeGameNames(): Flow<List<String>>
    suspend fun setOptions(gameName: String, options: List<String>)
    suspend fun renameOption(gameName: String, oldName: String, newName: String)
    suspend fun removeOption(gameName: String, name: String)
}

@Singleton
class DefaultServerOptionRepository @Inject constructor(
    private val database: RecordDatabase,
    private val serverOptionDao: ServerOptionDao,
) : ServerOptionRepository {

    override fun observeForGame(gameName: String): Flow<List<String>> =
        serverOptionDao.observeForGame(gameName.trim()).map { rows -> rows.map { it.name } }

    override fun observeGameNames(): Flow<List<String>> = serverOptionDao.observeGameNames()

    override suspend fun setOptions(gameName: String, options: List<String>) {
        val game = validatedGameName(gameName)
        val names = validatedNames(options)
        database.withDatasetTransaction {
            serverOptionDao.deleteForGame(game)
            serverOptionDao.insertAll(names.mapIndexed { index, name ->
                ServerOptionEntity(
                    id = UUID.randomUUID().toString(),
                    gameName = game,
                    name = name,
                    sortOrder = index,
                )
            })
        }
    }

    override suspend fun renameOption(gameName: String, oldName: String, newName: String) {
        val game = validatedGameName(gameName)
        val from = oldName.trim()
        val to = validatedNames(listOf(newName)).single()
        require(from.isNotEmpty()) { "原区服选项不能为空" }
        database.withDatasetTransaction {
            val existing = serverOptionDao.findForGame(game).map { it.name }
            require(existing.contains(from)) { "原区服选项不存在" }
            require(existing.filter { it != from }.none { it == to }) { "区服选项不能重复" }
            serverOptionDao.renameOption(game, from, to)
        }
    }

    override suspend fun removeOption(gameName: String, name: String) {
        val game = validatedGameName(gameName)
        database.withDatasetTransaction {
            serverOptionDao.deleteOption(game, name.trim())
        }
    }

    private fun validatedGameName(gameName: String): String {
        val game = gameName.trim()
        require(game.isNotEmpty()) { "请填写游戏名称" }
        require(game.length <= 60) { "游戏名称最多 60 个字符" }
        return game
    }

    /**
     * The same limits as the template exchange codec, so a saved option list can always
     * round-trip through an exported template file.
     */
    private fun validatedNames(options: List<String>): List<String> {
        val names = options.map { it.trim() }
        require(names.size <= TemplateFileCodec.MAX_SERVER_OPTIONS) {
            "区服选项最多 ${TemplateFileCodec.MAX_SERVER_OPTIONS} 个"
        }
        names.forEach { name ->
            require(name.isNotEmpty()) { "区服选项不能为空" }
            require(name.length <= TemplateFileCodec.MAX_SERVER_OPTION_LENGTH) {
                "区服选项最多 ${TemplateFileCodec.MAX_SERVER_OPTION_LENGTH} 个字符"
            }
        }
        require(names.distinct().size == names.size) { "区服选项不能重复" }
        return names
    }
}
