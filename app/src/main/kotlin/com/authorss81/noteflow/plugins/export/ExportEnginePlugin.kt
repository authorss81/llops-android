package com.authorss81.noteflow.plugins.export

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.authorss81.noteflow.plugins.ExportFormat
import com.authorss81.noteflow.plugins.ExportOutcome
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** MIME type used when sharing an [ExportFormat] file. */
val ExportFormat.mimeType: String
    get() = when (this) {
        ExportFormat.MARKDOWN -> "text/markdown"
        ExportFormat.HTML -> "text/html"
        ExportFormat.PDF -> "application/pdf"
    }

/**
 * Platform-only writer for the Export Engine: materializes an [ExportPayload]
 * into a real file under `cacheDir/exports` (also reachable by the FileProvider,
 * see res/xml/file_paths.xml) and returns it as an [ExportOutcome].
 */
internal object ExportFileWriter {

    fun writeFile(
        context: Context,
        payload: ExportPayload,
        format: ExportFormat
    ): ExportOutcome {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val file = File(dir, payload.fileName)
        return try {
            when (format) {
                ExportFormat.MARKDOWN -> file.writeBytes(payload.markdown.toByteArray(Charsets.UTF_8))
                ExportFormat.HTML -> file.writeBytes(payload.html.toByteArray(Charsets.UTF_8))
                ExportFormat.PDF -> {
                    val body = payload.plainText.ifBlank { payload.markdown }
                    if (!TextPdfWriter.write(file, payload.title, body)) {
                        return ExportOutcome.Error("Could not render the note to PDF.")
                    }
                }
            }
            ExportOutcome.Success(file, format)
        } catch (e: Exception) {
            ExportOutcome.Error("Export failed (${e::class.java.simpleName}).")
        }
    }
}

/**
 * Platform-only helper that builds an ACTION_SEND intent for an exported file,
 * exposing it through the app's FileProvider so any app can receive it. The
 * caller keeps hold of the read permission grant for as long as the intent
 * lives.
 */
object ExportShareHelper {

    fun shareFile(context: Context, file: File, mime: String): Intent {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        return Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, file.name)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}

/**
 * The Export Engine plugin (Phase 15, capability `Export`).
 *
 * Serves [com.authorss81.noteflow.plugins.PluginCapability.Export] via the
 * [com.authorss81.noteflow.plugins.ExportPlugin] interface. The note payload is
 * assembled by the pure-JVM [ExportPayloadAssembler] (+ [MarkdownHtmlConverter]
 * on CommonMark); only file/PDF/materialization is platform-side, on
 * `Dispatchers.IO`. Exporting requires no INTERNET and no permissions.
 */
class ExportEnginePlugin(
    internal val writer: (Context, ExportPayload, ExportFormat) -> ExportOutcome = ExportFileWriter::writeFile
) : com.authorss81.noteflow.plugins.NoteflowPlugin,
    com.authorss81.noteflow.plugins.ExportPlugin {

    override val manifest = com.authorss81.noteflow.plugins.PluginManifest(
        id = "com.authorss81.noteflow.plugins.export.engine",
        name = "Export Engine",
        version = com.authorss81.noteflow.plugins.SemanticVersion(1, 0, 0),
        minSupportedApi = 26,
        description = "Exports any note to Markdown, HTML or PDF and shares it via the system share sheet.",
        capabilities = setOf(com.authorss81.noteflow.plugins.PluginCapability.Export)
    )

    override fun availability(context: Context?): com.authorss81.noteflow.plugins.PluginAvailability =
        com.authorss81.noteflow.plugins.PluginAvailability.Ok

    override fun onEnable(context: Context?, settings: com.authorss81.noteflow.plugins.PluginSettings) {
        // Stateless; no warm-up or persisted settings for v1.
    }

    override suspend fun exportNote(
        context: Context?,
        request: com.authorss81.noteflow.plugins.ExportRequest,
        format: ExportFormat
    ): ExportOutcome {
        val ctx = context
            ?: return ExportOutcome.Error("Export requires a device context.")
        val payload = ExportPayloadAssembler.assemble(request.title, request.markdown, request.plainText, format)
        return withContext(Dispatchers.IO) { writer(ctx, payload, format) }
    }
}