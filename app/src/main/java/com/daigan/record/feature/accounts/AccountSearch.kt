package com.daigan.record.feature.accounts

import com.daigan.record.data.model.OwnerSummary

/** Deliberate allow-list: credentials, device notes and resources are never searched. */
fun matchesAccountSearch(summary: OwnerSummary, rawQuery: String): Boolean {
    val query = rawQuery.trim()
    return query.isBlank() || listOf(summary.owner.nickname, summary.owner.displayNumber,
        summary.owner.number.toString()).any { it.contains(query, ignoreCase = true) } ||
        summary.accounts.any { account ->
            listOf(account.uid, account.gameName, account.server, account.loginAccount)
                .any { it.contains(query, ignoreCase = true) }
        }
}
