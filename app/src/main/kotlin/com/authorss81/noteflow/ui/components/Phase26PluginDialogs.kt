package com.authorss81.noteflow.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Book
import androidx.compose.material.icons.outlined.Calculate
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.ListAlt
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.authorss81.noteflow.plugins.CitationOutcome
import com.authorss81.noteflow.plugins.DictionaryLookup
import com.authorss81.noteflow.plugins.DictionaryOutcome
import com.authorss81.noteflow.plugins.OutlineOutcome
import com.authorss81.noteflow.plugins.OutlineStyle
import com.authorss81.noteflow.plugins.PluginResult
import com.authorss81.noteflow.plugins.UnitConversionOutcome
import com.authorss81.noteflow.plugins.WeatherOutcome
import com.authorss81.noteflow.plugins.weather.WeatherSnapshotFormatter
import com.authorss81.noteflow.ui.viewmodel.NoteflowViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

// ---------------------------------------------------------------------------
// Phase 26 — lightweight compile-time plugin dialogs (Dictionary, Weather,
// Unit Converter, Outline & Checklist, Citation Formatter). Pure UI over the
// ViewModel's plugin routing; ALL logic lives in the plugin packages.
// ---------------------------------------------------------------------------

private sealed interface DictionaryStage {
    data object Idle : DictionaryStage
    data object Loading : DictionaryStage
    data class Result(val lookup: DictionaryLookup) : DictionaryStage
    data class Miss(val message: String) : DictionaryStage
    data class Error(val message: String) : DictionaryStage
}

/** The "word — definition" text the spec requires for insertion. */
internal fun DictionaryLookup.insertionText(): String = buildString {
    append("**").append(word).append("**")
    definitions.firstOrNull()?.let { first ->
        append(" — ").append(first.definition)
        definitions.drop(1).forEach { append("\n- ").append(it.definition) }
    }
}

/** Word definitions via the keyless dictionaryapi.dev API + offline fallback. */
@Composable
fun DictionaryDialog(
    viewModel: NoteflowViewModel,
    onInsert: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var word by remember { mutableStateOf("") }
    var stage by remember { mutableStateOf<DictionaryStage>(DictionaryStage.Idle) }
    var job by remember { mutableStateOf<Job?>(null) }

    fun lookup() {
        val trimmed = word.trim()
        if (trimmed.isEmpty()) {
            stage = DictionaryStage.Error("Enter a word to look up first.")
            return
        }
        job?.cancel()
        stage = DictionaryStage.Loading
        job = scope.launch {
            val result = viewModel.lookupDictionaryWord(trimmed)
            if (coroutineContext.isActive) {
                stage = when (result) {
                    is PluginResult.Success -> when (val outcome = result.value) {
                        is DictionaryOutcome.Success -> DictionaryStage.Result(outcome.lookup)
                        is DictionaryOutcome.NotFound -> DictionaryStage.Miss(outcome.message)
                        is DictionaryOutcome.Error -> DictionaryStage.Error(outcome.message)
                    }
                    is PluginResult.Failure -> DictionaryStage.Error(result.message)
                    is PluginResult.Unavailable -> DictionaryStage.Error(result.message)
                }
            }
        }
    }
    DisposableEffect(Unit) { onDispose { job?.cancel() } }

    AlertDialog(
        onDismissRequest = { job?.cancel(); onDismiss() },
        icon = { Icon(Icons.Outlined.Book, contentDescription = null) },
        title = { Text("Dictionary") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = word,
                        onValueChange = { word = it },
                        modifier = Modifier.weight(1f),
                        label = { Text("Word") },
                        singleLine = true
                    )
                    IconButton(onClick = ::lookup, enabled = stage !is DictionaryStage.Loading) {
                        Icon(Icons.Outlined.Book, contentDescription = "Look up")
                    }
                }
                when (val s = stage) {
                    DictionaryStage.Idle -> Text(
                        "Defined via the keyless dictionaryapi.dev API with an offline " +
                            "fallback word list. The definition is inserted as \"word — definition\".",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    DictionaryStage.Loading -> {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Text("Looking up…", style = MaterialTheme.typography.bodySmall)
                    }
                    is DictionaryStage.Result -> {
                        Text(
                            "${s.lookup.word}${s.lookup.phonetic?.let { " · $it" }.orEmpty()}",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        s.lookup.definitions.forEach { def ->
                            val pos = def.partOfSpeech?.let { "($it) " }.orEmpty()
                            Text(
                                "${pos}${def.definition}",
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                        Text(
                            "Source: ${s.lookup.source}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                    is DictionaryStage.Miss -> Text(
                        s.message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                    is DictionaryStage.Error -> Text(
                        s.message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            when (val s = stage) {
                is DictionaryStage.Result -> {
                    TextButton(onClick = {
                        onInsert(s.lookup.insertionText())
                        job?.cancel()
                        onDismiss()
                    }) { Text("Insert into note") }
                    TextButton(onClick = { job?.cancel(); onDismiss() }) { Text("Close") }
                }
                else -> TextButton(onClick = { job?.cancel(); onDismiss() }) { Text("Close") }
            }
        }
    )
}

private sealed interface WeatherStage {
    data object Idle : WeatherStage
    data object Loading : WeatherStage
    data class Result(val snapshot: com.authorss81.noteflow.plugins.WeatherSnapshot) : WeatherStage
    data class Error(val message: String) : WeatherStage
}

/** Dated weather snapshot via keyless Open-Meteo (no GPS, user-initiated). */
@Composable
fun WeatherDialog(
    viewModel: NoteflowViewModel,
    onInsert: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var stage by remember { mutableStateOf<WeatherStage>(WeatherStage.Idle) }
    var job by remember { mutableStateOf<Job?>(null) }

    fun fetch() {
        job?.cancel()
        stage = WeatherStage.Loading
        job = scope.launch {
            val result = viewModel.fetchWeatherSnapshot()
            if (coroutineContext.isActive) {
                stage = when (result) {
                    is PluginResult.Success -> when (val outcome = result.value) {
                        is WeatherOutcome.Success -> WeatherStage.Result(outcome.snapshot)
                        is WeatherOutcome.Error -> WeatherStage.Error(outcome.message)
                    }
                    is PluginResult.Failure -> WeatherStage.Error(result.message)
                    is PluginResult.Unavailable -> WeatherStage.Error(result.message)
                }
            }
        }
    }
    DisposableEffect(Unit) { onDispose { job?.cancel() } }

    AlertDialog(
        onDismissRequest = { job?.cancel(); onDismiss() },
        icon = { Icon(Icons.Outlined.WbSunny, contentDescription = null) },
        title = { Text("Weather snapshot") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when (val s = stage) {
                    WeatherStage.Idle -> Text(
                        "Fetched from the keyless Open-Meteo API for the default city " +
                            "(no GPS). Configure the city or latitude/longitude in the plugin's settings.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    WeatherStage.Loading -> {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Text("Fetching weather…", style = MaterialTheme.typography.bodySmall)
                    }
                    is WeatherStage.Result -> {
                        Text(
                            WeatherSnapshotFormatter.toNoteText(s.snapshot),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            "Location: ${s.snapshot.sourceNote}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                    is WeatherStage.Error -> Text(
                        s.message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            when (val s = stage) {
                is WeatherStage.Result -> {
                    TextButton(onClick = {
                        onInsert(WeatherSnapshotFormatter.toNoteText(s.snapshot))
                        job?.cancel()
                        onDismiss()
                    }) { Text("Insert into note") }
                    TextButton(onClick = { job?.cancel(); onDismiss() }) { Text("Close") }
                }
                WeatherStage.Idle -> TextButton(onClick = ::fetch) { Text("Fetch weather") }
                else -> TextButton(onClick = { job?.cancel(); onDismiss() }) { Text("Close") }
            }
        }
    )
}

private sealed interface ConvertStage {
    data object Idle : ConvertStage
    data class Result(val text: String) : ConvertStage
    data class Error(val message: String) : ConvertStage
}

/** Inline unit conversion — "2 km to mi" → "2 km = 1.2427 mi". Fully offline. */
@Composable
fun UnitConverterDialog(
    viewModel: NoteflowViewModel,
    onInsert: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var stage by remember { mutableStateOf<ConvertStage>(ConvertStage.Idle) }

    fun convert() {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            stage = ConvertStage.Error("Enter a conversion like \"2 km to mi\".")
            return
        }
        scope.launch {
            val result = viewModel.convertUnits(trimmed)
            stage = when (result) {
                is PluginResult.Success -> when (val outcome = result.value) {
                    is UnitConversionOutcome.Success -> ConvertStage.Result(outcome.text)
                    is UnitConversionOutcome.Error -> ConvertStage.Error(outcome.message)
                }
                is PluginResult.Failure -> ConvertStage.Error(result.message)
                is PluginResult.Unavailable -> ConvertStage.Error(result.message)
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.Calculate, contentDescription = null) },
        title = { Text("Unit Converter") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier.weight(1f),
                        label = { Text("2 km to mi") },
                        singleLine = true
                    )
                    IconButton(onClick = ::convert) {
                        Icon(Icons.Outlined.Calculate, contentDescription = "Convert")
                    }
                }
                when (val s = stage) {
                    ConvertStage.Idle -> Text(
                        "Offline, no dependencies. Length, mass, temperature and basic " +
                            "(fixed reference-rate) currency.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    is ConvertStage.Result -> Text(
                        s.text,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    is ConvertStage.Error -> Text(
                        s.message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            when (val s = stage) {
                is ConvertStage.Result -> {
                    TextButton(onClick = { onInsert(s.text); onDismiss() }) { Text("Insert into note") }
                    TextButton(onClick = onDismiss) { Text("Close") }
                }
                else -> TextButton(onClick = onDismiss) { Text("Close") }
            }
        }
    )
}

private sealed interface OutlineStage {
    data object Idle : OutlineStage
    data class Result(val text: String, val style: OutlineStyle) : OutlineStage
    data class Error(val message: String) : OutlineStage
}

/** Outline / checklist generator from the current note text. Pure Kotlin. */
@Composable
fun OutlineGeneratorDialog(
    viewModel: NoteflowViewModel,
    sourceText: String,
    onInsert: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var style by remember { mutableStateOf(OutlineStyle.OUTLINE) }
    var stage by remember { mutableStateOf<OutlineStage>(OutlineStage.Idle) }

    fun generate() {
        scope.launch {
            val result = viewModel.generateOutline(sourceText, style)
            stage = when (result) {
                is PluginResult.Success -> when (val outcome = result.value) {
                    is OutlineOutcome.Success -> OutlineStage.Result(outcome.text, style)
                    is OutlineOutcome.Error -> OutlineStage.Error(outcome.message)
                }
                is PluginResult.Failure -> OutlineStage.Error(result.message)
                is PluginResult.Unavailable -> OutlineStage.Error(result.message)
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.ListAlt, contentDescription = null) },
        title = { Text("Outline & Checklist") },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 420.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = style == OutlineStyle.OUTLINE,
                        onClick = { style = OutlineStyle.OUTLINE },
                        label = { Text("Outline") }
                    )
                    FilterChip(
                        selected = style == OutlineStyle.CHECKLIST,
                        onClick = { style = OutlineStyle.CHECKLIST },
                        label = { Text("Checklist") }
                    )
                }
                when (val s = stage) {
                    OutlineStage.Idle -> Text(
                        "Generates a structured outline or a checkbox checklist from the " +
                            "note's current text. Previewed here before insertion.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    is OutlineStage.Result -> Text(
                        s.text,
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState()),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    is OutlineStage.Error -> Text(
                        s.message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            when (val s = stage) {
                is OutlineStage.Result -> {
                    TextButton(onClick = { onInsert("\n\n${s.text}\n"); onDismiss() }) { Text("Insert into note") }
                    TextButton(onClick = onDismiss) { Text("Close") }
                }
                OutlineStage.Idle, is OutlineStage.Error -> TextButton(onClick = ::generate) { Text("Generate") }
            }
        }
    )
}

private sealed interface CitationStage {
    data object Idle : CitationStage
    data object Loading : CitationStage
    data class Result(val markdown: String, val titleFetched: Boolean) : CitationStage
    data class Error(val message: String) : CitationStage
}

/** Format a pasted URL into a clean Markdown [title](url) link. */
@Composable
fun CitationFormatterDialog(
    viewModel: NoteflowViewModel,
    onInsert: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf("") }
    var title by remember { mutableStateOf("") }
    var stage by remember { mutableStateOf<CitationStage>(CitationStage.Idle) }
    var job by remember { mutableStateOf<Job?>(null) }

    fun format() {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) {
            stage = CitationStage.Error("Paste a URL first.")
            return
        }
        job?.cancel()
        stage = CitationStage.Loading
        job = scope.launch {
            val result = viewModel.formatCitation(trimmed, title.trim().ifEmpty { null })
            if (coroutineContext.isActive) {
                stage = when (result) {
                    is PluginResult.Success -> when (val outcome = result.value) {
                        is CitationOutcome.Success -> CitationStage.Result(outcome.markdown, outcome.titleFetched)
                        is CitationOutcome.Error -> CitationStage.Error(outcome.message)
                    }
                    is PluginResult.Failure -> CitationStage.Error(result.message)
                    is PluginResult.Unavailable -> CitationStage.Error(result.message)
                }
            }
        }
    }
    DisposableEffect(Unit) { onDispose { job?.cancel() } }

    AlertDialog(
        onDismissRequest = { job?.cancel(); onDismiss() },
        icon = { Icon(Icons.Outlined.Link, contentDescription = null) },
        title = { Text("Citation Formatter") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("URL") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Title (optional)") },
                    singleLine = true
                )
                when (val s = stage) {
                    CitationStage.Idle -> Text(
                        "Without a title the page <title> is fetched over HTTPS; when that " +
                            "fails an honest host-derived label is used.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    CitationStage.Loading -> {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Text("Fetching title…", style = MaterialTheme.typography.bodySmall)
                    }
                    is CitationStage.Result -> {
                        Text(
                            s.markdown,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            if (s.titleFetched) "Title fetched from the page" else "Title from your input / host fallback",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                    is CitationStage.Error -> Text(
                        s.message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            when (val s = stage) {
                is CitationStage.Result -> {
                    TextButton(onClick = { onInsert(s.markdown); job?.cancel(); onDismiss() }) { Text("Insert into note") }
                    TextButton(onClick = { job?.cancel(); onDismiss() }) { Text("Close") }
                }
                CitationStage.Loading -> TextButton(onClick = { job?.cancel(); stage = CitationStage.Idle }) { Text("Cancel") }
                else -> TextButton(onClick = ::format) { Text("Format") }
            }
        }
    )
}