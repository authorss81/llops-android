# NoteFlow ProGuard & R8 Optimization Rules

# Keep Room generated code and entities
-keep class * extends androidx.room.RoomDatabase
-dontwarn androidx.room.paging.**

# Keep NoteFlow models for serialization/deserialization
-keepclassmembers class com.authorss81.noteflow.data.model.** { *; }
-keepclassmembers class com.authorss81.noteflow.data.db.** { *; }

# Keep AndroidX Ink classes
-keep class androidx.ink.** { *; }

# Keep Gson annotations & fields
-keepattributes Signature,*Annotation*
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# Phase 22: Gson reflection over the plugin-entry wire DTO. The internal
# PluginEntryCodec$PluginEntryDto fields are read/written reflectively with no
# @SerializedName annotations, so R8 must not rename/remove them in release.
-keep class com.authorss81.noteflow.plugins.runtime.PluginEntryCodec$PluginEntryDto { *; }

# Phase 199 (PERF 2.3, R8 fullMode): Gson DTOs whose JSON key contract is the
# raw Kotlin property name (NO @SerializedName annotation — the generic
# `@SerializedName <fields>` rule above therefore does not cover them).
# Scoped per package; each entry was verified by source scan to contain
# Gson-(de)serialized wire/state types:
#  - plugins.weather: OpenMeteoForecastParser.RawForecast/Daily,
#    OpenMeteoGeocoderParser.Place/RawResult/RawResponse (WeatherCore.kt)
#  - plugins.dictionary: DictionaryCore RawEntry/Phonetic/Meaning/Definition
#  - plugins.websearch: DuckDuckGoClient RawAnswer/RelatedTopic
#  - services.localsend: LocalSendProtocol/LocalSendPairing wire DTOs
#    (mostly @SerializedName-annotated already; kept wholesale as belt and
#    braces so a future un-annotated field cannot silently break the wire)
#  - PluginManifestParser's ManifestDto/PluginOfferDto fetched over HTTPS via
#    Gson with plain property names.
# Verified NOT to need rules by source scan: BrushPresetFileCodec builds its
# JSON via JsonObject calls (no reflective DTO); PluginEntryCodec$PluginEntryDto
# is covered by the explicit rule above; data.model/** by the existing rule.
# Only member NAMES are pinned (<fields>) — classes remain shrinkable and
# obfuscatable, so the size cost is negligible.
-keepclassmembers class com.authorss81.noteflow.plugins.weather.** { <fields>; }
-keepclassmembers class com.authorss81.noteflow.plugins.dictionary.** { <fields>; }
-keepclassmembers class com.authorss81.noteflow.plugins.websearch.** { <fields>; }
-keepclassmembers class com.authorss81.noteflow.services.localsend.** { <fields>; }
# Hosted-plugin manifest wire DTOs (nested in PluginManifestParser; fetched
# over HTTPS via Gson with plain property-name keys).
-keepclassmembers class com.authorss81.noteflow.plugins.runtime.PluginManifestParser$* { <fields>; }

# Coroutines rules are handled automatically by kotlinx-coroutines-core R8 rules

# MediaPipe tasks-genai (on-device LLM) references protobuf classes that R8
# would otherwise strip during release minify, causing:
#   Missing class com.google.protobuf.Internal$ProtoMethodMayReturnNull
-keep class com.google.protobuf.** { *; }
-dontwarn com.google.protobuf.**
