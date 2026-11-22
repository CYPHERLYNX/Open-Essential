package com.craft.capturespace.capture

import android.app.Activity
import android.app.AlertDialog
import com.craft.capturespace.App

/**
 * Asked once, on the first shared link: may the app read that link's public page?
 * The answer is saved as the "Fetch previews for links I share" setting and can be changed there.
 */
object LinkConsent {
    const val TITLE = "Fetch link previews?"
    const val MESSAGE = "To describe links you share, Open Essential can read the public page of that link: its title, " +
        "description and preview picture. Only the link's address is requested; your captures and notes are never sent. " +
        "Posts that need a login or are private are not read.\n\nYou can change this any time in Settings › Links."

    fun show(a: Activity, onAnswer: (Boolean) -> Unit) {
        var answered = false
        val answer = { fetch: Boolean ->
            if (!answered) {
                answered = true
                App.instance.settings.linkPreviews = if (fetch) "on" else "off"
                onAnswer(fetch)
            }
        }
        AlertDialog.Builder(a)
            .setTitle(TITLE)
            .setMessage(MESSAGE)
            .setPositiveButton("Fetch previews") { _, _ -> answer(true) }
            .setNegativeButton("Not now") { _, _ -> answer(false) }
            // Dismissed without a choice: nothing is fetched and the question comes back next time.
            .setOnCancelListener { if (!answered) { answered = true; onAnswer(false); App.instance.settings.linkPreviews = "ask" } }
            .show()
    }
}
