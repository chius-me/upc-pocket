package me.chius.upcpocket.core.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import me.chius.upcpocket.core.model.CampusPreference
import me.chius.upcpocket.core.model.ThemePreference

data class AppPreferencesState(
    val campus: CampusPreference = CampusPreference.ALL,
    val theme: ThemePreference = ThemePreference.SYSTEM,
    val dynamicColorEnabled: Boolean = true,
)

class AppPreferencesRepository internal constructor(
    private val dataStore: DataStore<Preferences>,
    scope: CoroutineScope,
) {
    constructor(
        context: Context,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    ) : this(
        dataStore = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(SupervisorJob() + ioDispatcher),
            produceFile = {
                File(context.applicationContext.noBackupFilesDir, "datastore/app_preferences.preferences_pb")
                    .apply { parentFile?.mkdirs() }
            },
        ),
        scope = CoroutineScope(SupervisorJob() + ioDispatcher),
    )

    val state: StateFlow<AppPreferencesState> = dataStore.data
        .map { prefs ->
            AppPreferencesState(
                campus = CampusPreference.fromStorageKey(prefs[KEY_CAMPUS]),
                theme = ThemePreference.fromStorageKey(prefs[KEY_THEME]),
                dynamicColorEnabled = prefs[KEY_DYNAMIC_COLOR] ?: true,
            )
        }
        .stateIn(
            scope = scope,
            started = SharingStarted.Eagerly,
            initialValue = AppPreferencesState(),
        )

    suspend fun setCampus(campus: CampusPreference) {
        dataStore.edit { prefs ->
            prefs[KEY_CAMPUS] = campus.storageKey
        }
    }

    suspend fun setTheme(theme: ThemePreference) {
        dataStore.edit { prefs ->
            prefs[KEY_THEME] = theme.storageKey
        }
    }

    suspend fun setDynamicColorEnabled(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[KEY_DYNAMIC_COLOR] = enabled
        }
    }

    private companion object {
        val KEY_CAMPUS = stringPreferencesKey("campus_preference")
        val KEY_THEME = stringPreferencesKey("theme_preference")
        val KEY_DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color_enabled")
    }
}
