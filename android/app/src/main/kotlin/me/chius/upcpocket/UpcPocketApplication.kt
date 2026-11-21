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
import me.chius.upcpocket.core.update.AppUpdateChecker
import me.chius.upcpocket.feature.calendar.AcademicCalendarRepository
import me.chius.upcpocket.feature.card.CardBalanceRepository
import me.chius.upcpocket.feature.card.CardWalletRepository
import me.chius.upcpocket.feature.classroom.FreeClassroomRepository
import me.chius.upcpocket.feature.grades.GradesRepository
import me.chius.upcpocket.feature.library.LibrarySeatRepository
import me.chius.upcpocket.feature.power.PowerRepository
import me.chius.upcpocket.feature.shiguang.ShiguangRepository
import me.chius.upcpocket.feature.timetable.TimetableRepository

class AppContainer(context: Context) {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val preferencesRepository = AppPreferencesRepository(context)
    val schoolAccountRepository = SchoolAccountRepository(context)
    val cardBalanceRepository = CardBalanceRepository(
        context = context,
        identityProvider = { schoolAccountRepository.studentIdentity.value },
    )
    val cardWalletRepository = CardWalletRepository(
        context = context,
        schoolAccountRepository = schoolAccountRepository,
    )
    val gradesRepository = GradesRepository(
        context = context,
        schoolAccountRepository = schoolAccountRepository,
    )
    val powerRepository = PowerRepository(context)
    val freeClassroomRepository = FreeClassroomRepository()
    val librarySeatRepository = LibrarySeatRepository(schoolAccountRepository)
    val timetableRepository = TimetableRepository(
        context = context,
        schoolAccountRepository = schoolAccountRepository,
    )
    val shiguangRepository = ShiguangRepository(schoolAccountRepository)
    val academicCalendarRepository = AcademicCalendarRepository(context)
    val appUpdateChecker = AppUpdateChecker()

    init {
        schoolAccountRepository.addInvalidationListener { reason ->
            if (reason == AccountInvalidationReason.ACCOUNT_CHANGED_OR_CLEARED) {
                cardBalanceRepository.clearForAccountChange()
            }
            timetableRepository.onAccountInvalidated(reason)
            librarySeatRepository.onAccountInvalidated(reason)
            gradesRepository.onAccountInvalidated(reason)
            cardWalletRepository.onAccountInvalidated(reason)
            shiguangRepository.onAccountInvalidated(reason)
        }
        appScope.launch {
            schoolAccountRepository.restore()
            cardBalanceRepository.restore()
            powerRepository.restore()
            timetableRepository.restore()
            gradesRepository.restore()
            shiguangRepository.syncAccountStatus()
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
