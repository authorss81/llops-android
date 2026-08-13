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

# Coroutines rules are handled automatically by kotlinx-coroutines-core R8 rules
