package me.chius.upcpocket

import android.app.Application
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import me.chius.upcpocket.core.data.AppPreferencesRepository
import me.chius.upcpocket.core.data.SchoolAccountRepository
import me.chius.upcpocket.core.model.AccountInvalidationReason
import me.chius.upcpocket.feature.card.CardBalanceRepository
import me.chius.upcpocket.feature.classroom.FreeClassroomRepository
import me.chius.upcpocket.feature.focus.FocusTimerController
import me.chius.upcpocket.feature.power.PowerRepository
import me.chius.upcpocket.feature.timetable.TimetableRepository

class AppContainer(context: Context) {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val preferencesRepository = AppPreferencesRepository(context)
    val schoolAccountRepository = SchoolAccountRepository(context)
    val cardBalanceRepository = CardBalanceRepository(
        context = context,
        identityProvider = { schoolAccountRepository.studentIdentity.value },
    )
    val powerRepository = PowerRepository(context)
    val freeClassroomRepository = FreeClassroomRepository()
    val timetableRepository = TimetableRepository(
        context = context,
        schoolAccountRepository = schoolAccountRepository,
    )
    val focusTimerController = FocusTimerController()

    init {
        schoolAccountRepository.addInvalidationListener { reason ->
            if (reason == AccountInvalidationReason.ACCOUNT_CHANGED_OR_CLEARED) {
                cardBalanceRepository.clearForAccountChange()
            }
            timetableRepository.onAccountInvalidated(reason)
        }
        appScope.launch {
            schoolAccountRepository.restore()
            cardBalanceRepository.restore()
            powerRepository.restore()
            timetableRepository.restore()
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
