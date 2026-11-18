package com.jpweytjens.barberfish.extension

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private const val BACKUP_FORMAT_VERSION = 1

private val backupJson = Json {
    prettyPrint = true
    ignoreUnknownKeys = true
    encodeDefaults = true
}

/** Portable snapshot of every stored Barberfish+ DataStore preference key. */
@Serializable
data class BarberfishBackup(
    val version: Int = BACKUP_FORMAT_VERSION,
    val preferences: Map<String, String>,
)

/** Encodes [entries] into a portable JSON backup string. */
internal fun encodeSettingsBackup(entries: Map<String, String>): String =
    backupJson.encodeToString(BarberfishBackup(preferences = entries.toSortedMap()))

/** Decodes a portable JSON backup string into its key-value map, rejecting invalid payloads. */
internal fun decodeSettingsBackup(raw: String): Map<String, String> {
    val parsed = backupJson.decodeFromString<BarberfishBackup>(raw)
    require(parsed.version in 1..BACKUP_FORMAT_VERSION) {
        "Unsupported backup version: ${parsed.version}"
    }
    return parsed.preferences
}

/** Exports all string preferences currently stored in DataStore as `(jsonText, entryCount)`. */
suspend fun Context.exportSettingsJson(): Pair<String, Int> {
    val prefs: Preferences = dataStore.data.first()
    val entries = buildMap {
        for ((key, value) in prefs.asMap()) {
            if (value is String) put(key.name, value)
        }
    }
    return encodeSettingsBackup(entries) to entries.size
}

/**
 * Restores all string preferences from [rawJson], replacing current DataStore settings so removed
 * keys revert to defaults. Returns the number of restored preference entries.
 */
suspend fun Context.importSettingsJson(rawJson: String): Int {
    val entries = decodeSettingsBackup(rawJson)
    dataStore.edit { mutable ->
        mutable.clear()
        for ((name, value) in entries) {
            mutable[stringPreferencesKey(name)] = value
        }
    }
    return entries.size
}
