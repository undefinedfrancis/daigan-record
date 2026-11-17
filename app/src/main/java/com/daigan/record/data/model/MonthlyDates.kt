package com.daigan.record.data.model

/** A non-empty, sorted set of month days; invalid input is never silently truncated. */
fun parseMonthlyDates(raw: String?): Set<Int>? {
    if (raw.isNullOrBlank()) return null
    val values = raw.trim().split(Regex("[,，、;；\\s]+"))
    val parsed = values.map { it.toIntOrNull() ?: return null }
    if (parsed.any { it !in 1..31 }) return null
    return parsed.toSortedSet().takeIf { it.isNotEmpty() }
}
fun encodeMonthlyDates(days: Set<Int>): String? = days.takeIf { it.isNotEmpty() }?.sorted()?.joinToString(",")
