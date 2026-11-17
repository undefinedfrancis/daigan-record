package com.daigan.record.data.model

import kotlinx.serialization.json.*

/** Structured resources remain inside the backed-up resourceNotes field. */
data class AccountResource(val id: String, val name: String, val value: String)

data class AccountResources(val notes: String = "", val entries: List<AccountResource> = emptyList()) {
    fun displayText(): String = (listOf(notes).filter { it.isNotBlank() } + entries.map {
        "${it.name.ifBlank { "未命名资源" }}：${it.value}"
    }).joinToString("\n")

    companion object {
        fun decode(raw: String?): AccountResources {
            val text = raw.orEmpty()
            return runCatching {
                val root = Json.parseToJsonElement(text).jsonObject
                require(root["format"]?.jsonPrimitive?.content == "daigan-account-resources-v1")
                val entries = root.getValue("entries").jsonArray.map { element ->
                    val item = element.jsonObject
                    AccountResource(item.getValue("id").jsonPrimitive.content,
                        item.getValue("name").jsonPrimitive.content, item.getValue("value").jsonPrimitive.content)
                }
                require(entries.all { it.id.isNotBlank() } && entries.map { it.id }.distinct().size == entries.size)
                AccountResources(root.getValue("notes").jsonPrimitive.content, entries)
            }.getOrElse { AccountResources(notes = text) }
        }

        fun encode(resources: AccountResources): String {
            if (resources.entries.isEmpty()) return resources.notes
            return buildJsonObject {
                put("format", "daigan-account-resources-v1")
                put("notes", resources.notes)
                putJsonArray("entries") {
                    resources.entries.forEach { entry -> add(buildJsonObject {
                        put("id", entry.id); put("name", entry.name); put("value", entry.value)
                    }) }
                }
            }.toString()
        }
    }
}
