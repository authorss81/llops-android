package com.authorss81.noteflow.plugins

/**
 * Where a plugin's own settings are persisted.
 *
 * Every key is namespaced per plugin — the canonical key form is
 * `plugins.<id>.<key>` (see [PluginSettingKey]) — so two plugins can never
 * collide on the same setting name. The production implementation stores these
 * in the app's SharedPreferences via [com.authorss81.noteflow.services.SettingsManager].
 */
interface PluginSettingsStore {
    fun getString(pluginId: String, key: String): String?
    fun setString(pluginId: String, key: String, value: String?)
    fun getInt(pluginId: String, key: String, default: Int): Int
    fun setInt(pluginId: String, key: String, value: Int)
    fun getBoolean(pluginId: String, key: String, default: Boolean): Boolean
    fun setBoolean(pluginId: String, key: String, value: Boolean)
    fun containsKey(pluginId: String, key: String): Boolean

    /**
     * Remove EVERY namespaced setting a plugin owns (used by the store's Delete
     * action). Implementations must clear all keys under `plugins.<id>.*`.
     * Default no-op keeps existing implementations working; the in-memory and
     * SharedPreferences stores implement it for real.
     */
    fun removeAll(pluginId: String) {}
}

/**
 * In-memory store used by JVM unit tests (keeps the framework testable without
 * Android). Deliberately NOT what production uses — that is
 * `SettingsPluginSettingsStore` over SharedPreferences.
 */
class InMemoryPluginSettingsStore : PluginSettingsStore {
    private val values = mutableMapOf<String, String>()

    override fun getString(pluginId: String, key: String): String? = values[PluginSettingKey.key(pluginId, key)]
    override fun setString(pluginId: String, key: String, value: String?) {
        val full = PluginSettingKey.key(pluginId, key)
        if (value == null) values.remove(full) else values[full] = value
    }
    override fun getInt(pluginId: String, key: String, default: Int): Int =
        values[PluginSettingKey.key(pluginId, key)]?.toIntOrNull() ?: default
    override fun setInt(pluginId: String, key: String, value: Int) {
        values[PluginSettingKey.key(pluginId, key)] = value.toString()
    }
    override fun getBoolean(pluginId: String, key: String, default: Boolean): Boolean =
        values[PluginSettingKey.key(pluginId, key)]?.toBoolean() ?: default
    override fun setBoolean(pluginId: String, key: String, value: Boolean) {
        values[PluginSettingKey.key(pluginId, key)] = value.toString()
    }
    override fun containsKey(pluginId: String, key: String): Boolean =
        values.containsKey(PluginSettingKey.key(pluginId, key))

    override fun removeAll(pluginId: String) {
        val prefix = PluginSettingKey.key(pluginId, "")
        values.keys.filter { it.startsWith(prefix) }.forEach { values.remove(it) }
    }
}

/**
 * The namespacing convention for plugin settings. All persisted plugin settings
 * live under `plugins.<id>.<key>` so no two plugins ever share a key. This is
 * also what `SettingsManager` uses, so the convention is enforced by a single
 * pure function that is directly unit-tested.
 */
object PluginSettingKey {
    /** The full pref key: `plugins.<id>.<key>`. */
    fun key(pluginId: String, key: String): String = "plugins.$pluginId.$key"
}

/**
 * A plugin-scoped handle over [PluginSettingsStore]: a plugin gets one of these
 * (built for its own id) in its lifecycle hooks, and only ever sees its own
 * namespaced slice of the store.
 */
class PluginSettings(
    private val store: PluginSettingsStore,
    private val pluginId: String
) {
    fun getString(key: String, default: String? = null): String? = store.getString(pluginId, key) ?: default
    fun setString(key: String, value: String?) = store.setString(pluginId, key, value)
    fun getInt(key: String, default: Int): Int = store.getInt(pluginId, key, default)
    fun setInt(key: String, value: Int) = store.setInt(pluginId, key, value)
    fun getBoolean(key: String, default: Boolean): Boolean = store.getBoolean(pluginId, key, default)
    fun setBoolean(key: String, value: Boolean) = store.setBoolean(pluginId, key, value)
    fun containsKey(key: String): Boolean = store.containsKey(pluginId, key)
}