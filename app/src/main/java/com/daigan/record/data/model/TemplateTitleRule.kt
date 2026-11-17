package com.daigan.record.data.model

/**
 * Pure rules for the template default order title (UF-036).
 *
 * A template may carry a default order title. It is applied only where the user has not
 * manually edited the title:
 *
 * - A blank or whitespace-only current title always accepts the default.
 * - A title equal to [TitleProvenance.lastAutoApplied] is still "the app's own fill", so a
 *   newer template default replaces it.
 * - Any other non-blank title is a manual edit and always wins; switching templates or
 *   accounts never renames it.
 * - A blank template default never changes an existing title, and clearing the title
 *   makes later automatic application possible again.
 *
 * The provenance record ([TitleProvenance]) is form-local UI state only. It is never
 * persisted as business data and never exported: the portable format intentionally does
 * not say which values were automatically filled.
 */
object TemplateTitleRule {

    /** Portable bound: `game_templates.defaultOrderTitle` keeps at most 60 code points. */
    const val MAX_DEFAULT_ORDER_TITLE_CODE_POINTS = 60

    /**
     * Trims a raw form value and maps blank input to null ("store no default").
     * Longer input is not truncated here; [validateDefaultOrderTitle] rejects it so the
     * user sees the problem instead of silently losing characters.
     */
    fun normalizeDefaultOrderTitle(raw: String?): String? {
        val trimmed = raw?.trim().orEmpty()
        return trimmed.ifEmpty { null }
    }

    /** Returns an error message when the value must not be saved, or null when valid. */
    fun validateDefaultOrderTitle(raw: String?): String? {
        val value = raw.orEmpty()
        if (codePoints(value) > MAX_DEFAULT_ORDER_TITLE_CODE_POINTS) {
            return "默认订单标题最多 $MAX_DEFAULT_ORDER_TITLE_CODE_POINTS 个字符"
        }
        return null
    }

    /**
     * The approved pure title rule. Returns the title the form should hold after
     * [defaultTitle] became the active template default.
     */
    fun resolveOrderTitle(
        currentTitle: String,
        lastAutoApplied: String?,
        defaultTitle: String?,
    ): String {
        val default = normalizeDefaultOrderTitle(defaultTitle) ?: return currentTitle
        val manualEdit = currentTitle.isNotBlank() && currentTitle != lastAutoApplied
        return if (manualEdit) currentTitle else default
    }

    /** True when [resolveOrderTitle] would change the form title. */
    fun wouldApply(
        currentTitle: String,
        lastAutoApplied: String?,
        defaultTitle: String?,
    ): Boolean = resolveOrderTitle(currentTitle, lastAutoApplied, defaultTitle) != currentTitle

    /**
     * Form-local record of the last value the app auto-filled. Purely a UI memory: it
     * travels with the form state and is never written to any repository.
     */
    fun nextProvenance(
        currentTitle: String,
        lastAutoApplied: String?,
        defaultTitle: String?,
    ): String? {
        val default = normalizeDefaultOrderTitle(defaultTitle)
        val resolved = resolveOrderTitle(currentTitle, lastAutoApplied, defaultTitle)
        // The default was applied: record it. Otherwise the previous record stands: a
        // blank default changed nothing, and a manual edit is already detected by not
        // matching the record at all.
        return if (default != null && resolved == default) resolved else lastAutoApplied
    }

    private fun codePoints(value: String): Int = value.codePointCount(0, value.length)
}
