package com.daigan.record.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One selectable 区服 option for a game, keyed by game name (UF-005).
 *
 * Deliberately **not** a foreign key to game templates: options must survive template
 * deletion and must be maintainable for games that have no template at all. Renaming or
 * removing an option never touches `game_accounts.server`, which keeps the value the
 * user actually saved on each account.
 */
@Entity(
    tableName = "game_server_options",
    indices = [
        Index(
            value = ["gameName", "sortOrder"],
            name = "index_game_server_options_gameName_sortOrder",
        ),
    ],
)
data class ServerOptionEntity(
    @PrimaryKey val id: String,
    val gameName: String,
    val name: String,
    val sortOrder: Int,
)
