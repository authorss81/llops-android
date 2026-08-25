package com.authorss81.noteflow.services

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import java.io.File

/**
 * Phase 212: pure-JVM fake [SharedPreferences] + fake [Context] so the
 * production `SettingsPlugin*Store` adapters (and anything else that only needs
 * prefs-backed state) can be exercised against a REAL [SettingsManager]
 * without Robolectric. The map survives "process restarts" as long as tests
 * share one [FakePrefs] instance across [SettingsManager] constructions.
 */
class FakePrefs : SharedPreferences {

    val map = LinkedHashMap<String, Any?>()
    val removed = mutableListOf<String>()
    var failNextCommit = false

    @Suppress("UNCHECKED_CAST")
    override fun getAll(): MutableMap<String, *> = map.clone() as MutableMap<String, Any?>

    override fun getString(key: String, defValue: String?): String? =
        map[key] as String? ?: defValue

    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? =
        map[key] as MutableSet<String>? ?: defValues

    override fun getInt(key: String, defValue: Int): Int = map[key] as Int? ?: defValue

    override fun getLong(key: String, defValue: Long): Long = map[key] as Long? ?: defValue

    override fun getFloat(key: String, defValue: Float): Float = map[key] as Float? ?: defValue

    override fun getBoolean(key: String, defValue: Boolean): Boolean =
        map[key] as Boolean? ?: defValue

    override fun contains(key: String): Boolean = map.containsKey(key)

    override fun edit(): SharedPreferences.Editor = FakeEditor()

    override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?
    ) {}

    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?
    ) {}

    private inner class FakeEditor : SharedPreferences.Editor {
        private val pending = LinkedHashMap<String, Any?>()
        private val pendingRemovals = mutableSetOf<String>()
        private var clearAll = false

        override fun putString(key: String, value: String?): SharedPreferences.Editor {
            pending[key] = value
            return this
        }

        override fun putStringSet(
            key: String,
            values: MutableSet<String>?
        ): SharedPreferences.Editor {
            pending[key] = values
            return this
        }

        override fun putInt(key: String, value: Int): SharedPreferences.Editor {
            pending[key] = value
            return this
        }

        override fun putLong(key: String, value: Long): SharedPreferences.Editor {
            pending[key] = value
            return this
        }

        override fun putFloat(key: String, value: Float): SharedPreferences.Editor {
            pending[key] = value
            return this
        }

        override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor {
            pending[key] = value
            return this
        }

        override fun remove(key: String): SharedPreferences.Editor {
            pendingRemovals.add(key)
            return this
        }

        override fun clear(): SharedPreferences.Editor {
            clearAll = true
            return this
        }

        override fun commit(): Boolean {
            if (failNextCommit) {
                failNextCommit = false
                return false
            }
            applyChanges()
            return true
        }

        override fun apply() = applyChanges()

        private fun applyChanges() {
            if (clearAll) map.clear()
            pendingRemovals.forEach {
                map.remove(it)
                removed.add(it)
            }
            pending.forEach { (k, v) ->
                if (v == null) map.remove(k) else map[k] = v
            }
            pending.clear()
            pendingRemovals.clear()
            clearAll = false
        }
    }
}

/**
 * A [Context] whose [getSharedPreferences] returns the given [FakePrefs] and
 * whose [filesDir] is a caller-supplied temp directory. Everything else
 * forwards to the (unused) wrapped base, mirroring ContextWrapper semantics.
 */
open class FakeContext(
    private val fakePrefs: FakePrefs,
    private val filesDirValue: File
) : ContextWrapper(null as Context?) {

    private val appContext: Context by lazy { this }

    override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = fakePrefs

    override fun getApplicationContext(): Context = appContext

    override fun getFilesDir(): File = filesDirValue
}

/** Fresh [SettingsManager] over [prefs] (simulates one construction). */
fun settingsOver(prefs: FakePrefs, filesDir: File = File("build/fake-files")): SettingsManager =
    SettingsManager(FakeContext(prefs, filesDir))
