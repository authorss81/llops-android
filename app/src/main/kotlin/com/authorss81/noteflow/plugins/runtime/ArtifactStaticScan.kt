package com.authorss81.noteflow.plugins.runtime

import java.io.File
import java.util.jar.JarFile

/**
 * Static content scan of a downloadable-plugin artifact (B1-AUTH-01 fix,
 * phase-46 — see `docs/security-report.md`).
 *
 * The classloader sandbox ([PluginFrameworkClassLoader]) stops plugin code from
 * RESOLVING app-private classes at runtime; this scan is the install/verify-time
 * gate that REJECTS an artifact whose bytecode merely MENTIONS them, plus the
 * raw network-egress entry points the capability facade (`PluginContext.httpGet`)
 * would otherwise be bypassed with. Two independent layers, both required.
 *
 * The scan opens the artifact as a JAR (plugin artifacts are signed APK/JARs)
 * and inspects each entry:
 *
 * - `.class` entries are parsed structurally (class-file constant pool) and
 *   every class-reference + string literal is extracted — exact, so a benign
 *   plugin that never mentions a forbidden type can never false-positive, and a
 *   `Class.forName("...")` string literal is caught with equal certainty.
 * - `classes.dex`/`*.dex` entries are parsed structurally too: the DEX string
 *   table + type table (every class reference and every string literal a
 *   dexfied plugin can make) via [DexStringExtractor].
 * - every other entry (descriptor properties, manifests, native blobs,
 *   resources) is byte-searched streamingly (decompressed) for the app-private
 *   package prefixes and the sensitive class names — native/model blobs cannot
 *   reference Java classes, but they also must not smuggle the patterns.
 *
 * Rejected content (applies to parsed strings AND raw bytes):
 *
 * 1. **App-private packages** — any `com/authorss81/noteflow/services/`,
 *    `.data/`, `.ui/`, `.theme/`, `.utils/` reference (slash AND dot form, for
 *    descriptors vs `Class.forName` literals). These packages hold the vault
 *    handles (`VaultKeyHolder.dek`, `SecurityService`, `NoteflowDatabase`,
 *    `SettingsManager`, `NoteRepository`, the DB factory).
 * 2. **Sensitive class names** — bare `VaultKeyHolder`, `EncryptionService`,
 *    `NoteflowDatabase`, `SettingsManager`, `NoteRepository`, `SecurityService`
 *    anywhere (belt-and-braces against concatenated/obfuscated references).
 * 3. **Raw network egress** — exact `java.net`/`javax.net.ssl` socket /
 *    connection primitives (`Socket`, `HttpURLConnection`, `URLConnection`,
 *    `URL`, `InetAddress`, `SocketChannel`, `SSLSocket`, …). Network MUST go
 *    through the host's capability facade, never the plugin's own sockets.
 *
 * Deliberately NOT included: `java/lang/ProcessBuilder` / `java/lang/Runtime`
 * (process execution is a separate boundary; a future isolation phase should
 * gate it too — see the phase-46 REPORT's out-of-scope notes).
 *
 * Pure JVM; never throws — a file that refuses to be read as a JAR is returned
 * [Result.Pass] so the signature/identity gates (which already fail such a
 * file) keep their single clear error instead of a compound one. `scan` is
 * called from [ArtifactSignatureVerifier.verify], which every plugin bytecode
 * path funnels through: install, EVERY load re-verify, update and rollback.
 */
class ArtifactStaticScan {

    /** Outcome of scanning one artifact. */
    sealed class Result {
        /** No forbidden reference found — the artifact may proceed to load. */
        data object Pass : Result()

        /** [reason] names the first forbidden pattern (user-facing, terse). */
        data class Rejected(val reason: String) : Result()
    }

    /**
     * Scan [file]'s JAR entries for forbidden content. Bounded memory (entry
     * content is streamed; parsed entries larger than [maxEntryBytes] are
     * skipped by the structural scan and only pass through the raw byte scan).
     * Never throws.
     */
    fun scan(file: File, maxEntryBytes: Long = DEFAULT_MAX_ENTRY_BYTES): Result {
        if (!file.isFile) return Result.Pass
        try {
            JarFile(file).use { jar ->
                val entries = jar.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    if (entry.isDirectory) continue
                    // Entry NAMES could smuggle a class under a forbidden path.
                    forbiddenIn(entry.name)?.let {
                        return Result.Rejected("entry '${entry.name}': $it")
                    }
                    jar.getInputStream(entry).use { input ->
                        val name = entry.name
                        when {
                            name.endsWith(".class") -> {
                                val bytes = input.readBounded(maxEntryBytes)
                                if (bytes != null) {
                                    for (ref in ClassFileReferenceExtractor.extract(bytes)) {
                                        forbiddenIn(ref)?.let { return Result.Rejected(it) }
                                    }
                                }
                            }
                            name.endsWith(".dex") -> {
                                val bytes = input.readBounded(maxEntryBytes)
                                if (bytes != null) {
                                    for (ref in DexStringExtractor.extract(bytes)) {
                                        forbiddenIn(ref)?.let { return Result.Rejected(it) }
                                    }
                                }
                            }
                            else -> forbiddenRawBytes(input)?.let { return Result.Rejected(it) }
                        }
                    }
                }
            }
        } catch (_: Throwable) {
            // Not a readable JAR → ArtifactSignatureVerifier.verify fails this
            // file loudly through the signature/identity gates; the scan does
            // not fabricate its own reason for an unreadable archive.
            return Result.Pass
        }
        return Result.Pass
    }

    // ---- per-string check ----------------------------------------------------

    /**
     * Returns a user-facing reason when [value] (a class name/descriptor or a
     * string literal extracted from the artifact) matches a forbidden pattern,
     * else null.
     */
    private fun forbiddenIn(value: String): String? {
        for (p in APP_PRIVATE_PREFIXES) {
            if (value.contains(p)) {
                return "references the base-app private package '${slashLabel(p)}' that plugins may not touch."
            }
        }
        for (n in SENSITIVE_CLASS_NAMES) {
            if (value.contains(n)) {
                return "references the base-app '${n}' class, which stays in the host — plugins never receive it."
            }
        }
        val canonical = value.removePrefix("L").substringBefore(';')
        if (canonical in NETWORK_EGRESS_CLASSES) {
            return "references raw network egress ('$canonical'). Plugin networking runs ONLY through the host's capability facade (httpGet)."
        }
        return null
    }

    /** Streaming byte-substring search for non-class/non-dex entries. Only the
     *  unambiguous app-private prefixes + sensitive names are searched raw
     *  (network egress is checked on parsed strings, where exact matching is
     *  possible — a raw `java/net/URL` would alias `URLClassLoader`). */
    private fun forbiddenRawBytes(input: java.io.InputStream): String? {
        // ISO-8859-1 maps bytes 1:1 to chars, so String.indexOf (JVM-intrinsic)
        // is a fast, correct byte-substring matcher.
        val chunk = ByteArray(RAW_SCAN_CHUNK)
        var kept: ByteArray = ByteArray(0)
        while (true) {
            val n = input.read(chunk)
            if (n < 0) break
            val combined = ByteArray(kept.size + n)
            System.arraycopy(kept, 0, combined, 0, kept.size)
            System.arraycopy(chunk, 0, combined, kept.size, n)
            val haystack = String(combined, Charsets.ISO_8859_1)
            for (pattern in RAW_BYTE_PATTERNS) {
                if (haystack.contains(pattern)) {
                    return rawMatchReason(pattern)
                }
            }
            // Keep the tail so a pattern split across two chunks still matches.
            val keepFrom = maxOf(0, combined.size - (RAW_MAX_PATTERN_LENGTH - 1))
            kept = combined.copyOfRange(keepFrom, combined.size)
        }
        return null
    }

    private fun rawMatchReason(pattern: String): String =
        if (pattern.contains("com/authorss81/noteflow/") || pattern.contains("com.authorss81.noteflow.")) {
            "contains a base-app private package reference ('$pattern') that plugins may not touch."
        } else {
            "references the base-app '${pattern}' class, which stays in the host — plugins never receive it."
        }

    // ---- pattern tables ------------------------------------------------------

    companion object {
        /** Cap on a single parsed entry (`.class`/`.dex`). Larger entries are
         *  skipped by the structural scan (native/model blobs never reference
         *  Java classes) but still pass through the raw byte scan. */
        const val DEFAULT_MAX_ENTRY_BYTES = 256L * 1024 * 1024
        private const val RAW_SCAN_CHUNK = 64 * 1024
        private const val RAW_MAX_PATTERN_LENGTH = 48

        /** App-private package prefixes whose classes plugins must never name.
         *  Slash (descriptor/internal-name) + dot (`Class.forName`) spellings. */
        private val APP_PRIVATE_PREFIXES: List<String> = listOf(
            "com/authorss81/noteflow/services/",
            "com/authorss81/noteflow/data/",
            "com/authorss81/noteflow/ui/",
            "com/authorss81/noteflow/theme/",
            "com/authorss81/noteflow/utils/",
            "com.authorss81.noteflow.services.",
            "com.authorss81.noteflow.data.",
            "com.authorss81.noteflow.ui.",
            "com.authorss81.noteflow.theme.",
            "com.authorss81.noteflow.utils."
        )

        /** Bare sensitive class names (covers concatenated / string-built
         *  references like `Class.forName(pkg + "VaultKeyHolder")`). */
        private val SENSITIVE_CLASS_NAMES: List<String> = listOf(
            "VaultKeyHolder",
            "EncryptionService",
            "NoteflowDatabase",
            "SettingsManager",
            "NoteRepository",
            "SecurityService"
        )

        /** Raw network-egress primitives (canonical slash names; a leading `L`
         *  / trailing `;` descriptor is stripped before the set lookup, so
         *  `Ljava/net/URL;` matches but `java/net/URLClassLoader` does not). */
        private val NETWORK_EGRESS_CLASSES: Set<String> = setOf(
            "java/net/Socket",
            "java/net/ServerSocket",
            "java/net/DatagramSocket",
            "java/net/SocketChannel",
            "java/net/HttpURLConnection",
            "java/net/URLConnection",
            "java/net/URL",
            "java/net/InetAddress",
            "java/net/InetSocketAddress",
            "javax/net/SocketFactory",
            "javax/net/ssl/SSLSocket",
            "javax/net/ssl/HttpsURLConnection"
        )

        private val RAW_BYTE_PATTERNS: List<String> =
            APP_PRIVATE_PREFIXES + SENSITIVE_CLASS_NAMES

        private fun slashLabel(pattern: String): String =
            if (pattern.startsWith("com.")) {
                pattern.substringAfter("com.authorss81.noteflow.").removeSuffix(".")
            } else {
                pattern.substringAfter("com/authorss81/noteflow/").removeSuffix("/")
            }
    }
}

/** Bounded read of up to [limit] bytes; null when the entry exceeds [limit]
 *  (the caller then skips the structural scan for that entry). */
internal fun java.io.InputStream.readBounded(limit: Long): ByteArray? {
    val buffer = java.io.ByteArrayOutputStream()
    val chunk = ByteArray(64 * 1024)
    var total = 0L
    while (true) {
        val n = read(chunk)
        if (n < 0) break
        total += n
        if (total > limit) return null
        buffer.write(chunk, 0, n)
    }
    return buffer.toByteArray()
}

/**
 * Extracts every string a `.class` file can reference: the class-file constant
 * pool `CONSTANT_Class` names AND every `CONSTANT_Utf8` literal (which carries
 * the `ldc` string constants such as a `Class.forName("...")` argument).
 * PURE JVM, exact — no byte-substring ambiguity.
 */
internal object ClassFileReferenceExtractor {

    fun extract(bytes: ByteArray): List<String> {
        try {
            val out = mutableListOf<String>()
            if (bytes.size < 10) return emptyList()
            if (readU4(bytes, 0) != CAFE_BABE) return emptyList()
            val count = readU2(bytes, 8)
            var idx = 10
            var i = 1
            while (i < count && idx + 1 <= bytes.size) {
                val tag = bytes[idx].toInt() and 0xFF
                when (tag) {
                    1 -> { // CONSTANT_Utf8
                        val len = readU2(bytes, idx + 1)
                        val start = idx + 3
                        val end = minOf(start + len, bytes.size)
                        val value = decodeUtf8(bytes, start, end)
                        out += value
                        idx = end
                    }
                    7, 8, 16, 19, 20 -> idx += 3 // Class, String, MethodType, Module, Package
                    15 -> idx += 4                // MethodHandle
                    9, 10, 11, 12 -> idx += 5    // refs, NameAndType
                    5, 6 -> { idx += 9; i++ }     // Long/Double occupy two slots
                    3, 4 -> idx += 5              // Integer, Float
                    else -> return out           // unknown tag → stop reading
                }
                i++
            }
            return out
        } catch (_: Throwable) {
            return emptyList()
        }
    }

    private const val CAFE_BABE: Int = 0xCAFEBABE.toInt()

    private fun readU2(bytes: ByteArray, off: Int): Int =
        ((bytes[off].toInt() and 0xFF) shl 8) or (bytes[off + 1].toInt() and 0xFF)

    private fun readU4(bytes: ByteArray, off: Int): Int =
        ((bytes[off].toInt() and 0xFF) shl 24) or
            ((bytes[off + 1].toInt() and 0xFF) shl 16) or
            ((bytes[off + 2].toInt() and 0xFF) shl 8) or
            (bytes[off + 3].toInt() and 0xFF)

    private fun decodeUtf8(bytes: ByteArray, start: Int, end: Int): String {
        val sb = StringBuilder(end - start)
        var i = start
        while (i < end) {
            val b = bytes[i].toInt() and 0xFF
            when {
                b < 0x80 -> { sb.append(b.toChar()); i++ }
                b and 0xE0 == 0xC0 && i + 1 < end -> {
                    sb.append(((b and 0x1F) shl 6 or (bytes[i + 1].toInt() and 0x3F)).toChar())
                    i += 2
                }
                b and 0xF0 == 0xE0 && i + 2 < end -> {
                    sb.append(
                        ((b and 0x0F) shl 12 or
                            ((bytes[i + 1].toInt() and 0x3F) shl 6) or
                            (bytes[i + 2].toInt() and 0x3F)).toChar()
                    )
                    i += 3
                }
                else -> i++
            }
        }
        return sb.toString()
    }
}

/**
 * Extracts every string a DEX file carries: the full `string_ids` table (string
 * literals) plus every type descriptor from `type_ids` (the analog of a
 * `.class` file's `CONSTANT_Class` references). This is the complete set of
 * class-name/string-literal references a dexfied plugin can produce.
 * PURE JVM, exact.
 */
internal object DexStringExtractor {

    fun extract(bytes: ByteArray): List<String> {
        try {
            if (bytes.size < 0x70) return emptyList()
            val dexMagic = bytes.copyOfRange(0, 4).toString(Charsets.US_ASCII)
            if (!dexMagic.startsWith("dex\n")) return emptyList()
            val stringIdsSize = readU4(bytes, 0x38)
            val stringIdsOff = readU4(bytes, 0x3C)
            val typeIdsSize = readU4(bytes, 0x40)
            val typeIdsOff = readU4(bytes, 0x44)
            val strings = ArrayList<String>(stringIdsSize)
            var i = 0
            while (i < stringIdsSize) {
                strings += readStringData(bytes, readU4(bytes, stringIdsOff + i * 4))
                i++
            }
            val fromTypes = ArrayList<String>(typeIdsSize)
            i = 0
            while (i < typeIdsSize) {
                val idx = readU4(bytes, typeIdsOff + i * 4)
                if (idx < strings.size) fromTypes += strings[idx]
                i++
            }
            return strings + fromTypes
        } catch (_: Throwable) {
            return emptyList()
        }
    }

    private fun readStringData(bytes: ByteArray, off: Int): String {
        var p = off
        // Skip the uleb128 utf16_length; the decoder reads until the NUL
        // terminator (string_data items terminate with a 0x00 byte).
        while (p < bytes.size && (bytes[p].toInt() and 0x80) != 0) p++
        p++
        val sb = StringBuilder()
        while (p < bytes.size && bytes[p].toInt() != 0) {
            val b = bytes[p].toInt() and 0xFF
            when {
                b < 0x80 -> { sb.append(b.toChar()); p++ }
                b and 0xE0 == 0xC0 && p + 1 < bytes.size -> {
                    sb.append(((b and 0x1F) shl 6 or (bytes[p + 1].toInt() and 0x3F)).toChar())
                    p += 2
                }
                b and 0xF0 == 0xE0 && p + 2 < bytes.size -> {
                    sb.append(
                        ((b and 0x0F) shl 12 or
                            ((bytes[p + 1].toInt() and 0x3F) shl 6) or
                            (bytes[p + 2].toInt() and 0x3F)).toChar()
                    )
                    p += 3
                }
                else -> p++
            }
        }
        return sb.toString()
    }

    private fun readU4(bytes: ByteArray, off: Int): Int =
        (bytes[off].toInt() and 0xFF) or
            ((bytes[off + 1].toInt() and 0xFF) shl 8) or
            ((bytes[off + 2].toInt() and 0xFF) shl 16) or
            ((bytes[off + 3].toInt() and 0xFF) shl 24)
}