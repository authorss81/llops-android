package com.authorss81.noteflow.llm.policy

/**
 * PURE JVM — prompt assembly for the four on-device assistant tasks. The
 * prompts are user-facing text; the platform engine applies the model's own
 * chat template. All assembly is unit-tested with a fake engine.
 *
 * Privacy + size discipline:
 *  - The source [noteText] is truncated to [MAX_CONTEXT_CHARS] before any
 *    prompt is built (long notes are summarized from their head).
 *  - Outputs never echo prompts back — tasks ask for plain, direct language.
 */
object AssistantPrompts {

    /** Rough char cap for note context (≈1.5k–2k tokens — fits tiny models). */
    const val MAX_CONTEXT_CHARS = 6000

    fun summarize(noteText: String): String =
        buildSystem(
            task = "Write a concise 4–6 bullet summary of the note below. " +
                "Single line per bullet, no markdown headers.",
            noteText = noteText
        )

    fun extractActionItems(noteText: String): String =
        buildSystem(
            task = "List the action items (tasks, todos, follow-ups) in the note below. " +
                "One item per line prefixed with '- '. If there are none, reply 'No action items.'",
            noteText = noteText
        )

    fun answerQuestion(noteText: String, question: String): String =
        buildSystem(
            task = "Answer the question using ONLY the note below. " +
                "If the note doesn't contain the answer, say so — don't invent details.",
            noteText = noteText,
            user = "Question: $question"
        )

    fun suggestTags(noteText: String): String =
        buildSystem(
            task = "Suggest 3–5 short comma-separated tags describing the note below. " +
                "Reply only with the tags (no explanations, no numbering).",
            noteText = noteText
        )

    /** Truncate a possibly-huge note to the context cap, cut at a word boundary. */
    fun truncate(noteText: String): String {
        val text = noteText.trim()
        if (text.length <= MAX_CONTEXT_CHARS) return text
        var cut = text.substring(0, MAX_CONTEXT_CHARS)
        val lastSpace = cut.lastIndexOf(' ')
        if (lastSpace > MAX_CONTEXT_CHARS * 0.8) cut = cut.substring(0, lastSpace)
        return cut.trimEnd() + "\n[…note truncated]"
    }

    private fun buildSystem(task: String, noteText: String, user: String? = null): String {
        val system = "You are a privacy-first assistant running entirely on the user's device. " +
            "$task"
        return if (user == null) {
            system + "\n\n--- Note ---\n${truncate(noteText)}"
        } else {
            system + "\n\n--- Note ---\n${truncate(noteText)}\n\n$user"
        }
    }
}