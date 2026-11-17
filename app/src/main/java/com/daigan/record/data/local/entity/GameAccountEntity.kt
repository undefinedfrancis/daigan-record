package com.daigan.record.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.daigan.record.data.model.GameAccount
import com.daigan.record.data.model.LoginMethod
import java.time.Instant

/**
 * A game account owned by one [OwnerEntity].
 *
 * The foreign key uses RESTRICT: owner rows can never be deleted while accounts
 * still reference them. Records are archived instead of deleted.
 *
 * [password] is null unless [loginMethod] is [LoginMethod.PASSWORD]. Verification
 * codes are never stored.
 */
@Entity(
    tableName = "game_accounts",
    foreignKeys = [
        ForeignKey(
            entity = OwnerEntity::class,
            parentColumns = ["id"],
            childColumns = ["ownerId"],
            onDelete = ForeignKey.RESTRICT,
            onUpdate = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["ownerId"]),
        Index(value = ["ownerId", "archivedAt"]),
        Index(value = ["categoryId"], name = "index_game_accounts_categoryId"),
    ],
)
data class GameAccountEntity(
    @PrimaryKey val id: String,
    val ownerId: String,
    val gameName: String,
    val server: String,
    val uid: String,
    val loginAccount: String,
    val loginMethod: LoginMethod,
    val password: String?,
    val createdAt: Instant,
    val updatedAt: Instant,
    val archivedAt: Instant?,
    /**
     * Set only when the account was archived as part of an owner archive batch, and
     * cleared when the account is restored. Accounts archived on their own always
     * keep this null, so timestamp equality is never used as an identity.
     */
    val archiveBatchId: String? = null,
    /**
     * Optional template preselected when a new order is started for this account.
     *
     * Deliberately **not** a foreign key: deleting a template must never require
     * touching or cascading through business rows. The template repository clears this
     * value in the same transaction when its template is deleted, and a dangling value
     * is treated as "no default" on read.
     */
    val defaultTemplateId: String? = null,
    /** Optional free-text login device (手机/平板/电脑/自定义); null on legacy accounts. */
    val loginDevice: String? = null,
    val resourceNotes: String? = null,
    /**
     * 账号分类（UF-037，schema 15）。NULL = 未分类。
     *
     * 逻辑外键，没有 SQL FK：删除分类的路径在同一事务里把账号置为未分类，而不是级联删除。
     */
    val categoryId: String? = null,
) {
    fun toModel(): GameAccount = GameAccount(
        id = id,
        ownerId = ownerId,
        gameName = gameName,
        server = server,
        uid = uid,
        loginAccount = loginAccount,
        loginMethod = loginMethod,
        password = password,
        createdAt = createdAt,
        archived = archivedAt != null,
        archivedWithOwner = archiveBatchId != null,
        defaultTemplateId = defaultTemplateId,
        loginDevice = loginDevice,
        resourceNotes = resourceNotes,
        categoryId = categoryId,
    )
}
