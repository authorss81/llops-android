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

# Coroutines rules are handled automatically by kotlinx-coroutines-core R8 rules

# MediaPipe tasks-genai (on-device LLM) references protobuf classes that R8
# would otherwise strip during release minify, causing:
#   Missing class com.google.protobuf.Internal$ProtoMethodMayReturnNull
-keep class com.google.protobuf.** { *; }
-dontwarn com.google.protobuf.**
