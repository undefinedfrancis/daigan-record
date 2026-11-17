package com.daigan.record.data.preferences

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import com.daigan.record.data.model.AccountDraft

/**
 * Device-local user-interface preferences.
 *
 * These are deliberately **not** Room entities and **not** part of a backup: a backup
 * replaces the business dataset of an installation, and restoring it must not silently
 * change how *this device* animates or how it lays out its own screens. They are also
 * deliberately not in `app_settings`, which is the dataset-identity singleton a restore
 * rewrites.
 *
 * Every preference defaults to the pre-existing behaviour, so a user with no stored
 * value sees exactly what they saw before the setting existed.
 */
interface UiPreferenceStore {
    /** The caller supplies a dataset, owner and new/edit identity; never pass an owner ID alone. */
    suspend fun accountDraft(ownerId: String): AccountDraft?
    suspend fun setAccountDraft(ownerId: String, draft: AccountDraft)
    suspend fun clearAccountDraft(ownerId: String)

    /** Emits the current value immediately and on every change. Default is `true`. */
    fun observePageTransitionAnimations(): Flow<Boolean>

    suspend fun pageTransitionAnimationsEnabled(): Boolean

    suspend fun setPageTransitionAnimationsEnabled(enabled: Boolean)

    /**
     * Legacy account-level colour preferences (pre-UF-028).
     *
     * The business colour now belongs to the order (`orders.orderColor`) and is read only
     * from there; these values are **not** migrated onto orders, so an old account colour
     * can never silently recolour every order of that account. They stay declared only so
     * existing installs keep reading their preference file, and are scheduled for removal
     * once no release still reads them.
     */
    fun observeTodayTaskColors(): Flow<Boolean>

    suspend fun setTodayTaskColorsEnabled(enabled: Boolean)

    fun observeTodayAccountColors(): Flow<Map<String, Int>>
    fun observeTodayGroups(): Flow<Map<String, String>>
    suspend fun setTodayAccountColor(accountId: String, color: Int?)
    suspend fun setTodayGroup(key: String, group: String?)

    /**
     * 账号页视图偏好（UF-037）：[ACCOUNT_VIEW_OWNER]（老板视图，默认）或
     * [ACCOUNT_VIEW_ACCOUNT]（跨老板账号视图）。本机保存，不进入备份。
     */
    fun observeAccountViewMode(): Flow<String>

    suspend fun setAccountViewMode(mode: String)

    /** 账号视图的分类筛选：分类 id；null = 全部。本机保存，不进入备份。 */
    fun observeAccountCategoryFilter(): Flow<String?>

    suspend fun setAccountCategoryFilter(categoryId: String?)
}

/**
 * [SharedPreferences] backed store. The flow is seeded once at construction and updated
 * on write, so every collector — the nav host and the settings switch — sees the same
 * value without re-reading the preference file.
 */
@Singleton
class SharedPreferencesUiPreferenceStore @Inject constructor(
    @ApplicationContext context: Context,
) : UiPreferenceStore {

    private val preferences: SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    private val pageTransitionAnimations =
        MutableStateFlow(readBoolean(KEY_PAGE_TRANSITIONS, DEFAULT_PAGE_TRANSITIONS))
    private val todayTaskColors =
        MutableStateFlow(readBoolean(KEY_TODAY_TASK_COLORS, DEFAULT_TODAY_TASK_COLORS))
    private val todayAccountColors = MutableStateFlow(readMap(KEY_TODAY_ACCOUNT_COLORS).mapValues { it.value.toIntOrNull() ?: 0 }.filterValues { it != 0 })
    private val todayGroups = MutableStateFlow(readMap(KEY_TODAY_GROUPS))
    private val accountViewMode = MutableStateFlow(
        preferences.getString(KEY_ACCOUNT_VIEW_MODE, ACCOUNT_VIEW_OWNER) ?: ACCOUNT_VIEW_OWNER,
    )
    private val accountCategoryFilter = MutableStateFlow(
        preferences.getString(KEY_ACCOUNT_CATEGORY_FILTER, null)?.takeIf { it.isNotBlank() },
    )

    override fun observePageTransitionAnimations(): Flow<Boolean> = pageTransitionAnimations

    override suspend fun pageTransitionAnimationsEnabled(): Boolean =
        pageTransitionAnimations.value

    override suspend fun setPageTransitionAnimationsEnabled(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_PAGE_TRANSITIONS, enabled).apply()
        pageTransitionAnimations.value = enabled
    }

    override suspend fun accountDraft(ownerId: String): AccountDraft? =
        preferences.getString("account_draft_$ownerId", null)?.let(AccountDraftCodec::decode)
    override suspend fun setAccountDraft(ownerId: String, draft: AccountDraft) {
        preferences.edit().putString("account_draft_$ownerId", AccountDraftCodec.encode(draft)).apply()
    }
    override suspend fun clearAccountDraft(ownerId: String) {
        preferences.edit().remove("account_draft_$ownerId").apply()
    }

    override fun observeTodayTaskColors(): Flow<Boolean> = todayTaskColors

    override suspend fun setTodayTaskColorsEnabled(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_TODAY_TASK_COLORS, enabled).apply()
        todayTaskColors.value = enabled
    }

    override fun observeTodayAccountColors(): Flow<Map<String, Int>> = todayAccountColors
    override fun observeTodayGroups(): Flow<Map<String, String>> = todayGroups
    override suspend fun setTodayAccountColor(accountId: String, color: Int?) {
        val next = todayAccountColors.value.toMutableMap().apply { if (color == null) remove(accountId) else put(accountId, color) }
        preferences.edit().putString(KEY_TODAY_ACCOUNT_COLORS, encodeMap(next.mapValues { it.value.toString() })).apply(); todayAccountColors.value = next
    }
    override suspend fun setTodayGroup(key: String, group: String?) {
        val next = todayGroups.value.toMutableMap().apply { if (group.isNullOrBlank()) remove(key) else put(key, group.trim()) }
        preferences.edit().putString(KEY_TODAY_GROUPS, encodeMap(next)).apply(); todayGroups.value = next
    }

    override fun observeAccountViewMode(): Flow<String> = accountViewMode

    override suspend fun setAccountViewMode(mode: String) {
        val next = if (mode == ACCOUNT_VIEW_ACCOUNT) ACCOUNT_VIEW_ACCOUNT else ACCOUNT_VIEW_OWNER
        preferences.edit().putString(KEY_ACCOUNT_VIEW_MODE, next).apply()
        accountViewMode.value = next
    }

    override fun observeAccountCategoryFilter(): Flow<String?> = accountCategoryFilter

    override suspend fun setAccountCategoryFilter(categoryId: String?) {
        val next = categoryId?.takeIf { it.isNotBlank() }
        if (next == null) {
            preferences.edit().remove(KEY_ACCOUNT_CATEGORY_FILTER).apply()
        } else {
            preferences.edit().putString(KEY_ACCOUNT_CATEGORY_FILTER, next).apply()
        }
        accountCategoryFilter.value = next
    }

    /** An absent key is "no preference", which means the historical behaviour. */
    private fun readBoolean(key: String, defaultValue: Boolean): Boolean =
        if (preferences.contains(key)) preferences.getBoolean(key, defaultValue) else defaultValue

    companion object {
        const val FILE_NAME = "ui-preferences"
        const val KEY_PAGE_TRANSITIONS = "page_transition_animations"
        const val DEFAULT_PAGE_TRANSITIONS = true
        const val KEY_TODAY_TASK_COLORS = "today_task_colors"
        const val DEFAULT_TODAY_TASK_COLORS = true
        const val KEY_TODAY_ACCOUNT_COLORS = "today_account_colors"
        const val KEY_TODAY_GROUPS = "today_groups"
        const val KEY_ACCOUNT_VIEW_MODE = "account_view_mode"
        const val KEY_ACCOUNT_CATEGORY_FILTER = "account_category_filter"

        /** 老板视图：按老板分组（默认，保持既有体验）。 */
        const val ACCOUNT_VIEW_OWNER = "OWNER"

        /** 跨老板账号视图：所有账号平铺，显示老板身份并按分类筛选。 */
        const val ACCOUNT_VIEW_ACCOUNT = "ACCOUNT"
    }

    private fun readMap(key: String): Map<String, String> = preferences.getString(key, null).orEmpty().split("\u001e").mapNotNull { row ->
        row.split("\u001f", limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] }
    }.toMap()
    private fun encodeMap(map: Map<String, String>): String = map.entries.joinToString("\u001e") { "${it.key}\u001f${it.value}" }
}

internal object AccountDraftCodec {
    /**
     * Ten base64 fields: the original nine plus the account category id (UF-037).
     * Drafts written by older builds have nine fields and decode with "no category";
     * the encoded value is form recovery state only and never leaves the device.
     */
    fun encode(draft: AccountDraft): String = listOf(draft.gameName, draft.server, draft.uid,
        draft.loginAccount, draft.loginMethod.name, draft.password, draft.defaultTemplateId.orEmpty(),
        draft.loginDevice, draft.resourceNotes, draft.categoryId.orEmpty()).joinToString("\u001f") {
        java.util.Base64.getEncoder().encodeToString(it.toByteArray())
    }
    fun decode(value: String): AccountDraft? = runCatching {
        val f = value.split("\u001f").map { String(java.util.Base64.getDecoder().decode(it)) }
        require(f.size == 9 || f.size == 10)
        AccountDraft(f[0], f[1], f[2], f[3], com.daigan.record.data.model.LoginMethod.valueOf(f[4]),
            f[5], f[6].ifEmpty { null }, f[7], f[8],
            categoryId = f.getOrNull(9)?.ifEmpty { null })
    }.getOrNull()
}
