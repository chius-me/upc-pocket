package me.chius.upcpocket

import android.app.Application
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import me.chius.upcpocket.core.data.AppPreferencesRepository
import me.chius.upcpocket.core.data.SchoolAccountRepository
import me.chius.upcpocket.feature.focus.FocusTimerController

class AppContainer(context: Context) {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val preferencesRepository = AppPreferencesRepository(context)
    val schoolAccountRepository = SchoolAccountRepository(context)
    val focusTimerController = FocusTimerController()

    init {
        appScope.launch {
            schoolAccountRepository.restore()
        }
    }
}

class UpcPocketApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
