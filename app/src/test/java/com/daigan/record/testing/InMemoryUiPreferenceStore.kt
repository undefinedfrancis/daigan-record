package com.daigan.record.testing

import com.daigan.record.data.preferences.UiPreferenceStore
import com.daigan.record.data.model.AccountDraft
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * In-memory [UiPreferenceStore] for JVM tests.
 *
 * [backing] stands in for the preference file: constructing a second store over the
 * same map is how a test proves a value survives the process restart the user sees as
 * "closing and reopening the app".
 */
class InMemoryUiPreferenceStore(
    private val backing: MutableMap<String, Boolean> = mutableMapOf(),
    private val customBacking: MutableMap<String, String> = mutableMapOf(),
) : UiPreferenceStore {
    override suspend fun accountDraft(ownerId: String): AccountDraft? =
        customBacking["account_draft_$ownerId"]?.let(com.daigan.record.data.preferences.AccountDraftCodec::decode)
    override suspend fun setAccountDraft(ownerId: String, draft: AccountDraft) {
        customBacking["account_draft_$ownerId"] = com.daigan.record.data.preferences.AccountDraftCodec.encode(draft)
    }
    override suspend fun clearAccountDraft(ownerId: String) { customBacking.remove("account_draft_$ownerId") }

    private val pageTransitions =
        MutableStateFlow(backing[KEY_PAGE_TRANSITIONS] ?: DEFAULT_PAGE_TRANSITIONS)
    private val todayTaskColors =
        MutableStateFlow(backing[KEY_TODAY_TASK_COLORS] ?: DEFAULT_TODAY_TASK_COLORS)
    private val accountColors: MutableStateFlow<Map<String, Int>> = MutableStateFlow(decodeColors(customBacking[KEY_TODAY_ACCOUNT_COLORS]))
    private val groups: MutableStateFlow<Map<String, String>> = MutableStateFlow(decodeGroups(customBacking[KEY_TODAY_GROUPS]))

    override fun observePageTransitionAnimations(): Flow<Boolean> = pageTransitions

    override suspend fun pageTransitionAnimationsEnabled(): Boolean = pageTransitions.value

    override suspend fun setPageTransitionAnimationsEnabled(enabled: Boolean) {
        backing[KEY_PAGE_TRANSITIONS] = enabled
        pageTransitions.value = enabled
    }

    override fun observeTodayTaskColors(): Flow<Boolean> = todayTaskColors

    override suspend fun setTodayTaskColorsEnabled(enabled: Boolean) {
        backing[KEY_TODAY_TASK_COLORS] = enabled
        todayTaskColors.value = enabled
    }

    override fun observeTodayAccountColors(): Flow<Map<String, Int>> = accountColors
    override fun observeTodayGroups(): Flow<Map<String, String>> = groups
    override suspend fun setTodayAccountColor(accountId: String, color: Int?) {
        val next = accountColors.value.toMutableMap().apply { if (color == null) remove(accountId) else put(accountId, color) }
        customBacking[KEY_TODAY_ACCOUNT_COLORS] = encode(next.mapValues { it.value.toString() }); accountColors.value = next
    }
    override suspend fun setTodayGroup(key: String, group: String?) {
        val next = groups.value.toMutableMap().apply { if (group.isNullOrBlank()) remove(key) else put(key, group.trim()) }
        customBacking[KEY_TODAY_GROUPS] = encode(next); groups.value = next
    }

    private val accountViewMode = MutableStateFlow(
        customBacking[KEY_ACCOUNT_VIEW_MODE] ?: ACCOUNT_VIEW_OWNER,
    )
    private val accountCategoryFilter = MutableStateFlow(
        customBacking[KEY_ACCOUNT_CATEGORY_FILTER],
    )

    override fun observeAccountViewMode(): Flow<String> = accountViewMode

    override suspend fun setAccountViewMode(mode: String) {
        val next = if (mode == ACCOUNT_VIEW_ACCOUNT) ACCOUNT_VIEW_ACCOUNT else ACCOUNT_VIEW_OWNER
        customBacking[KEY_ACCOUNT_VIEW_MODE] = next
        accountViewMode.value = next
    }

    override fun observeAccountCategoryFilter(): Flow<String?> = accountCategoryFilter

    override suspend fun setAccountCategoryFilter(categoryId: String?) {
        val next = categoryId?.takeIf { it.isNotBlank() }
        if (next == null) customBacking.remove(KEY_ACCOUNT_CATEGORY_FILTER) else customBacking[KEY_ACCOUNT_CATEGORY_FILTER] = next
        accountCategoryFilter.value = next
    }

    companion object {
        const val KEY_PAGE_TRANSITIONS = "page_transition_animations"
        const val DEFAULT_PAGE_TRANSITIONS = true
        const val KEY_TODAY_TASK_COLORS = "today_task_colors"
        const val DEFAULT_TODAY_TASK_COLORS = true
        const val KEY_TODAY_ACCOUNT_COLORS = "today_account_colors"
        const val KEY_TODAY_GROUPS = "today_groups"
        const val KEY_ACCOUNT_VIEW_MODE = "account_view_mode"
        const val KEY_ACCOUNT_CATEGORY_FILTER = "account_category_filter"
        const val ACCOUNT_VIEW_OWNER = "OWNER"
        const val ACCOUNT_VIEW_ACCOUNT = "ACCOUNT"
        private fun encode(map: Map<String, String>) = map.entries.joinToString("\u001e") { "${it.key}\u001f${it.value}" }
        private fun decode(raw: String?): Map<String, String> = raw.orEmpty().split("\u001e").mapNotNull { it.split("\u001f", limit = 2).takeIf { p -> p.size == 2 }?.let { it[0] to it[1] } }.toMap()
        private fun decodeColors(raw: String?): Map<String, Int> = decode(raw).mapValues { it.value.toIntOrNull() ?: 0 }.filterValues { it != 0 }
        private fun decodeGroups(raw: String?): Map<String, String> = decode(raw)
    }
}
