package dev.whynet.app.service

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class SavedAppProfile(
    val packageName: String,
    val label: String,
    val domains: List<String>,
    val savedAtEpochMs: Long,
)

class AppPreferencesStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var simpleModeEnabled: Boolean
        get() = prefs.getBoolean(KEY_SIMPLE_MODE, false)
        set(value) {
            prefs.edit().putBoolean(KEY_SIMPLE_MODE, value).apply()
        }

    var helperContactName: String
        get() = prefs.getString(KEY_HELPER_NAME, "").orEmpty().trim()
        set(value) {
            prefs.edit().putString(KEY_HELPER_NAME, value.trim().take(32)).apply()
        }

    var relayServerUrl: String
        get() = prefs.getString(KEY_RELAY_URL, "").orEmpty().trim()
        set(value) {
            prefs.edit().putString(KEY_RELAY_URL, value.trim().trimEnd('/')).apply()
        }

    var themeMode: String
        get() = prefs.getString(KEY_THEME_MODE, "DARK").orEmpty().ifBlank { "DARK" }
        set(value) {
            prefs.edit().putString(KEY_THEME_MODE, value).apply()
        }

    fun loadSavedAppProfiles(): List<SavedAppProfile> {
        val raw = prefs.getString(KEY_APP_PROFILES, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val pkg = obj.optString("pkg")
                    val label = obj.optString("label")
                    if (pkg.isBlank() || label.isBlank()) continue
                    val domArr = obj.optJSONArray("domains")
                    val domains = buildList {
                        if (domArr != null) {
                            for (j in 0 until domArr.length()) {
                                val d = domArr.optString(j)
                                if (d.isNotBlank()) add(d)
                            }
                        }
                    }
                    if (domains.isNotEmpty()) {
                        add(
                            SavedAppProfile(
                                packageName = pkg,
                                label = label,
                                domains = domains,
                                savedAtEpochMs = obj.optLong("ts", 0L),
                            ),
                        )
                    }
                }
            }.sortedByDescending { it.savedAtEpochMs }
        }.getOrDefault(emptyList())
    }

    fun saveAppProfile(profile: SavedAppProfile): List<SavedAppProfile> {
        val updated = (listOf(profile) + loadSavedAppProfiles().filterNot { it.packageName == profile.packageName })
            .take(15)
        persistProfiles(updated)
        return updated
    }

    fun deleteAppProfile(packageName: String): List<SavedAppProfile> {
        val updated = loadSavedAppProfiles().filterNot { it.packageName == packageName }
        persistProfiles(updated)
        return updated
    }

    private fun persistProfiles(list: List<SavedAppProfile>) {
        val arr = JSONArray()
        list.forEach { p ->
            val obj = JSONObject()
            obj.put("pkg", p.packageName)
            obj.put("label", p.label)
            obj.put("ts", p.savedAtEpochMs)
            val domArr = JSONArray()
            p.domains.forEach { domArr.put(it) }
            obj.put("domains", domArr)
            arr.put(obj)
        }
        prefs.edit().putString(KEY_APP_PROFILES, arr.toString()).apply()
    }

    companion object {
        private const val PREFS_NAME = "whynet_prefs"
        private const val KEY_SIMPLE_MODE = "simple_mode"
        private const val KEY_HELPER_NAME = "helper_name"
        private const val KEY_RELAY_URL = "relay_url"
        private const val KEY_APP_PROFILES = "saved_app_profiles"
        private const val KEY_THEME_MODE = "theme_mode"
    }
}
