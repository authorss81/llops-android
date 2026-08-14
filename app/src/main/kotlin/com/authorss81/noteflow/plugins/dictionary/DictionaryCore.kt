package com.authorss81.noteflow.plugins.dictionary

import com.authorss81.noteflow.plugins.DictionaryDefinition
import com.authorss81.noteflow.plugins.DictionaryLookup
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException

/**
 * Pure-JVM dictionary core: parses the keyless [dictionaryapi.dev] JSON payload
 * and provides a small bundled OFFLINE word list as an honest fallback so a
 * lookup genuinely works with no network. Nothing here touches Android — the
 * whole path is unit-tested with sample payloads.
 */

/** Thrown when the dictionary service returns an unusable payload. */
class DictionaryServiceException(message: String) : java.io.IOException(message)

/** Source labels surfaced in [DictionaryLookup.source]. */
object DictionarySource {
    const val ONLINE = "online"
    const val OFFLINE = "offline"
}

/** Parses a `dictionaryapi.dev` response into a [DictionaryLookup]. */
object DictionaryResponseParser {

    private val gson = Gson()

    /**
     * @param json raw response body (an ARRAY of entries for one word).
     * @return the parsed lookup, or null when the payload is empty/blank.
     * @throws DictionaryServiceException on malformed JSON.
     */
    fun parse(json: String, requestedWord: String): DictionaryLookup? {
        if (json.isBlank()) return null
        val entries: List<RawEntry> = try {
            gson.fromJson(json, Array<RawEntry>::class.java)?.toList().orEmpty()
        } catch (e: JsonSyntaxException) {
            throw DictionaryServiceException("The dictionary service returned an unreadable response.")
        }
        if (entries.isEmpty()) return null

        val word = entries.firstNotNullOfOrNull { it.word }
            ?.takeIf { it.isNotBlank() } ?: requestedWord.trim()
        val phonetic = entries.firstNotNullOfOrNull { it.phonetic }
            ?.takeIf { it.isNotBlank() }
            ?: entries.firstNotNullOfOrNull { e -> e.phonetics?.firstNotNullOfOrNull { p -> p.text?.takeIf { it.isNotBlank() } } }

        val definitions = mutableListOf<DictionaryDefinition>()
        entries.forEach { entry ->
            entry.meanings.orEmpty().forEach { meaning ->
                meaning.definitions.orEmpty().forEach { def ->
                    val text = def.definition?.trim().orEmpty()
                    if (text.isNotBlank()) {
                        definitions.add(
                            DictionaryDefinition(
                                partOfSpeech = meaning.partOfSpeech?.takeIf { it.isNotBlank() },
                                definition = text
                            )
                        )
                    }
                }
            }
        }
        if (definitions.isEmpty()) return null
        return DictionaryLookup(
            word = word,
            phonetic = phonetic,
            definitions = definitions.take(5),
            source = DictionarySource.ONLINE
        )
    }

    /** Minimal `dictionaryapi.dev` JSON shape (unknown fields ignored). */
    data class RawEntry(
        val word: String? = null,
        val phonetic: String? = null,
        val phonetics: List<Phonetic>? = null,
        val meanings: List<Meaning>? = null
    )

    data class Phonetic(val text: String? = null)

    data class Meaning(
        val partOfSpeech: String? = null,
        val definitions: List<Definition>? = null
    )

    data class Definition(val definition: String? = null)
}

/**
 * The bundled OFFLINE word list. Deliberately small (a few KB) — it exists to
 * make lookups honest when the device is offline or the service is unreachable.
 * The result is labelled `source = offline` so the UI never pretends it came from
 * the online service. Words are stored lowercase; matching is case-insensitive.
 */
object OfflineWordList {

    /** word → definitions. Deliberately curated common words. */
    val words: Map<String, List<DictionaryDefinition>> = listOf(
        "abandon" to "give up completely (a course of action, a practice, or a way of thinking).",
        "abundant" to "existing or available in large quantities; plentiful.",
        "accurate" to "(of information, measurements, or predictions) correct in all details.",
        "analyze" to "examine (something) methodically and in detail, typically for explanation.",
        "approach" to "come near or nearer to (someone or something) in distance or time.",
        "assume" to "suppose to be the case, without proof.",
        "aware" to "having knowledge or perception of a situation or fact.",
        "benefit" to "an advantage or profit gained from something.",
        "brief" to "of short duration; not lasting long.",
        "calm" to "not showing or feeling nervousness, anger, or other strong emotions.",
        "certain" to "able to be firmly relied on; convinced in one's mind.",
        "challenge" to "a task or situation that tests someone's abilities.",
        "common" to "occurring, found, or done often; familiar.",
        "concept" to "an abstract idea; a general notion.",
        "consider" to "think carefully about (something), typically before making a decision.",
        "create" to "bring (something) into existence.",
        "curious" to "eager to know or learn something.",
        "decline" to "become smaller, fewer, or less; refuse politely.",
        "dedicate" to "devote (time, effort, or oneself) to a particular task or purpose.",
        "define" to "state or describe exactly the nature, scope, or meaning of.",
        "develop" to "grow or cause to grow and become more mature, advanced, or elaborate.",
        "efficient" to "achieving maximum productivity with minimum wasted effort or expense.",
        "enable" to "give (someone) the ability or means to do something.",
        "estimate" to "roughly calculate or judge the value, number, quantity, or extent of.",
        "explain" to "make (an idea, situation, or problem) clear to someone by describing in detail.",
        "explore" to "travel in or through (an unfamiliar area) in order to learn about it.",
        "focus" to "the centre of interest or activity; pay particular attention to.",
        "generate" to "produce or create (something).",
        "goal" to "the object of a person's ambition or effort; an aim or desired result.",
        "habit" to "a settled or regular tendency or practice.",
        "impact" to "a marked effect or influence.",
        "improve" to "make or become better.",
        "include" to "comprise or contain as part of a whole.",
        "indicate" to "point out; show; suggest as a desirable or necessary course of action.",
        "insight" to "the capacity to gain an accurate and deep understanding of someone or something.",
        "intend" to "have (a course of action) as one's purpose or intention; plan.",
        "maintain" to "cause or enable (a condition or state of affairs) to continue; keep in good order.",
        "measure" to "ascertain the size, amount, or degree of (something) by using an instrument.",
        "necessary" to "needed to be done, achieved, or present; essential.",
        "observe" to "notice or perceive (something) and register it as being significant; watch.",
        "obtain" to "get, acquire, or secure (something).",
        "opportunity" to "a set of circumstances that makes it possible to do something.",
        "participate" to "take part in an action or endeavour.",
        "persuade" to "induce (someone) to do something through reasoning or argument.",
        "potential" to "having or showing the capacity to become or develop into something in the future.",
        "predict" to "say or estimate that (a specified thing) will happen in the future.",
        "principle" to "a fundamental truth or proposition that serves as the foundation for a system of belief.",
        "purpose" to "the reason for which something is done or created or for which something exists.",
        "quality" to "the standard of something as measured against other things of a similar kind.",
        "require" to "need for a particular purpose; make necessary.",
        "resolve" to "settle or find a solution to (a problem, dispute, or contentious matter); decide firmly.",
        "significant" to "sufficiently great or important to be worthy of attention; notable.",
        "similar" to "resembling without being identical.",
        "source" to "a place, person, or thing from which something comes or can be obtained.",
        "specific" to "clearly defined or identified; precise and clear.",
        "strategy" to "a plan of action or policy designed to achieve a major or overall aim.",
        "structure" to "the arrangement of and relations between the parts or elements of something.",
        "sufficient" to "enough; adequate.",
        "therefore" to "for that reason; consequently.",
        "undertake" to "commit oneself to and begin (an enterprise or responsibility); promise to do.",
        "unique" to "being the only one of its kind; unlike anything else.",
        "utilize" to "make practical and effective use of.",
        "verify" to "make sure or demonstrate that (something) is true, accurate, or justified.",
        "visible" to "able to be seen.",
        "willing" to "ready, eager, or prepared to do something."
    ).map { (word, definition) ->
        word to listOf(DictionaryDefinition(partOfSpeech = null, definition = definition))
    }.toMap()

    /** Case-insensitive lookup; returns null when the word is not bundled. */
    fun lookup(word: String): DictionaryLookup? {
        val key = word.trim().lowercase()
        val definitions = words[key] ?: return null
        return DictionaryLookup(
            word = key,
            phonetic = null,
            definitions = definitions,
            source = DictionarySource.OFFLINE
        )
    }
}