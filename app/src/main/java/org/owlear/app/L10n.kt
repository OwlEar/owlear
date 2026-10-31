package org.owlear.app

import android.content.Context
import android.text.BidiFormatter

/**
 * Looks up on-screen text in the phone's (or the app's chosen) language.
 *
 * Tech: wraps Context.getString so code without a Context (Detection, Repo, Category)
 * can still produce translated text. The screen and the scanning service both call
 * [init], so it always holds a Context with the current language configuration.
 * Mechanic version: one sticker sheet per language; this picks the sheet that matches
 * the phone and hands out stickers by part number.
 *
 * [ltr] keeps addresses, device names and numbers readable inside right-to-left sentences
 * (Arabic, Persian, Hebrew) by fencing them off with Unicode direction isolates.
 */
object L10n {
    @Volatile private var ctx: Context? = null

    fun init(context: Context) {
        ctx = context.applicationContext.createConfigurationContext(context.resources.configuration)
    }

    fun s(id: Int, vararg args: Any): String = ctx?.getString(id, *args) ?: ""

    fun p(id: Int, count: Int): String =
        ctx?.resources?.getQuantityString(id, count, count) ?: ""

    /** Fences off text whose direction may differ from the sentence around it. */
    fun ltr(text: String): String = BidiFormatter.getInstance().unicodeWrap(text)

    /** Joins detail fragments with the dot separator used across the app. */
    fun dots(vararg parts: String?): String = parts.filter { !it.isNullOrEmpty() }.joinToString(" · ")
}
