package com.daigan.record.data.model

import java.time.Instant

/**
 * A user-defined account category (UF-037).
 *
 * One account belongs to at most one category; `null` means unclassified, which is also
 * the state of every account that predates this feature. A category is a label only: it
 * never owns orders, tasks, receipts or history, so renaming it changes nothing else and
 * deleting it must never delete an account.
 */
data class AccountCategory(
    val id: String,
    val name: String,
    val sortOrder: Int,
    val createdAt: Instant,
    val updatedAt: Instant,
)

/**
 * Pure validation for category names. The identity check is trim-then-exact: no
 * case folding, no locale-dependent normalization, so a category can never be renamed
 * as a side effect of a different language's casing rules.
 */
object AccountCategoryRules {

    const val MAX_NAME_LENGTH = 30

    /** Trimmed name, or `null` when the input is blank. */
    fun normalizeName(raw: String?): String? {
        val trimmed = raw?.trim().orEmpty()
        return trimmed.ifEmpty { null }
    }

    /**
     * Returns an error message when the name cannot be saved, or `null` when valid.
     *
     * [existingNames] holds the other categories' names (excluding the one being
     * renamed); a trimmed exact match is rejected because the database also enforces a
     * unique index and a raw SQLite error is not a usable message.
     */
    fun validateName(raw: String?, existingNames: Collection<String> = emptyList()): String? {
        val name = normalizeName(raw) ?: return "请填写分类名称"
        if (name.codePointCount(0, name.length) > MAX_NAME_LENGTH) {
            return "分类名称最多 $MAX_NAME_LENGTH 个字符"
        }
        if (existingNames.any { it == name }) return "已存在同名分类"
        return null
    }
}
