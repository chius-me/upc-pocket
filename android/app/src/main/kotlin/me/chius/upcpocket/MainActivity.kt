package me.chius.upcpocket

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.chius.upcpocket.core.designsystem.theme.UpcPocketTheme
import me.chius.upcpocket.navigation.UpcPocketNavHost

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        val container = (application as UpcPocketApplication).container
        setContent {
            val preferences by container.preferencesRepository.state.collectAsStateWithLifecycle()
            UpcPocketTheme(
                themePreference = preferences.theme,
                colorSeed = preferences.colorSeed,
                dynamicColorEnabled = preferences.dynamicColorEnabled,
            ) {
                UpcPocketNavHost(container = container)
            }
        }
    }
}
