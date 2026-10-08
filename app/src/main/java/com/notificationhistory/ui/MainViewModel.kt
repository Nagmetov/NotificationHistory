package com.notificationhistory.ui

import android.app.Application
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.notificationhistory.data.AppExtras
import com.notificationhistory.data.AppGroup
import com.notificationhistory.data.AppSettings
import com.notificationhistory.data.ConversationSummary
import com.notificationhistory.data.FilterCounts
import com.notificationhistory.data.HistoryFilter
import com.notificationhistory.data.MediaRecord
import com.notificationhistory.data.MessageRecord
import com.notificationhistory.data.NotificationRecord
import com.notificationhistory.data.NotificationRepository
import com.notificationhistory.service.MediaBackup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface Screen {
    data object Home : Screen
    data class App(val packageName: String, val label: String) : Screen
    data class Detail(val id: Long) : Screen
    data class Chat(val packageName: String, val convKey: String, val title: String) : Screen
    data class Viewer(val path: String, val kind: Int, val title: String, val deleted: Boolean) : Screen
    data object Settings : Screen
    data object AppFilter : Screen
    data object Setup : Screen
}

data class AppEntry(val packageName: String, val label: String, val inHistory: Boolean)

/** Разделы экрана приложения. */
enum class AppSection(val title: String) { NOTIFICATIONS("Уведомления"), CHATS("Чаты"), MEDIA("Медиа") }

data class AppContent(val hasChats: Boolean = false, val hasMedia: Boolean = false)

class MainViewModel(app: Application) : AndroidViewModel(app) {

    val repo = NotificationRepository.get(app)
    val settings = AppSettings(app)

    /** Простой стек навигации; переживает поворот экрана вместе с ViewModel. */
    val backStack = mutableStateListOf<Screen>(Screen.Home)

    /** Выбранный раздел на экране каждого приложения. */
    val appSection = mutableStateMapOf<String, AppSection>()

    fun navigate(screen: Screen) = backStack.add(screen)
    fun back() {
        if (backStack.size > 1) backStack.removeAt(backStack.lastIndex)
    }

    val filter = MutableStateFlow(HistoryFilter.ALL)

    val counts: StateFlow<FilterCounts> = repo.changes
        .map { repo.counts() }
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FilterCounts())

    val groups: StateFlow<List<AppGroup>?> = combine(filter, repo.changes) { f, _ -> repo.groups(f) }
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val extras: StateFlow<Map<String, AppExtras>> = repo.changes
        .map { repo.appExtras() }
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    fun notificationsFor(packageName: String): Flow<List<NotificationRecord>> =
        combine(filter, repo.changes) { f, _ -> repo.forPackage(packageName, f) }.flowOn(Dispatchers.IO)

    fun appContent(packageName: String): Flow<AppContent> =
        repo.changes.map { AppContent(repo.hasChats(packageName), repo.hasMedia(packageName)) }.flowOn(Dispatchers.IO)

    fun conversations(packageName: String): Flow<List<ConversationSummary>> =
        repo.changes.map { repo.conversations(packageName) }.flowOn(Dispatchers.IO)

    fun messages(packageName: String, convKey: String): Flow<List<MessageRecord>> =
        repo.changes.map { repo.messages(packageName, convKey) }.flowOn(Dispatchers.IO)

    fun media(packageName: String, onlyDeleted: Boolean): Flow<List<MediaRecord>> =
        repo.changes.map { repo.media(packageName, onlyDeleted) }.flowOn(Dispatchers.IO)

    fun record(id: Long): Flow<NotificationRecord?> =
        repo.changes.map { repo.byId(id) }.flowOn(Dispatchers.IO)

    fun knownApps(): Flow<List<Pair<String, String>>> =
        repo.changes.map { repo.knownApps() }.flowOn(Dispatchers.IO)

    // ---------- Выбор приложений ----------

    val excluded = MutableStateFlow(settings.excludedPackages)

    fun setExcluded(packages: Collection<String>, exclude: Boolean) {
        val next = if (exclude) excluded.value + packages else excluded.value - packages.toSet()
        settings.excludedPackages = next
        excluded.value = next
    }

    /** Приложения с иконкой в меню плюс всё, что уже попадало в историю (системные службы и т.п.). */
    val installedApps: StateFlow<List<AppEntry>?> = kotlinx.coroutines.flow.flow {
        val ctx = getApplication<Application>()
        val pm = ctx.packageManager
        val launcher = android.content.Intent(android.content.Intent.ACTION_MAIN)
            .addCategory(android.content.Intent.CATEGORY_LAUNCHER)
        val apps = HashMap<String, AppEntry>()
        pm.queryIntentActivities(launcher, 0).forEach { ri ->
            val pkg = ri.activityInfo.packageName
            if (pkg != ctx.packageName) apps[pkg] = AppEntry(pkg, ri.loadLabel(pm).toString(), inHistory = false)
        }
        repo.knownApps().forEach { (pkg, label) ->
            apps[pkg] = (apps[pkg] ?: AppEntry(pkg, label, inHistory = true)).copy(inHistory = true)
        }
        emit(apps.values.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label }))
    }.flowOn(Dispatchers.IO).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun delete(id: Long) = io { repo.delete(id) }
    fun deletePackage(packageName: String) = io { repo.deletePackage(packageName) }
    fun deleteAll() = io { repo.deleteAll() }
    fun purgeNow() = io { settings.purgeIfDue(repo) }
    fun scanMedia() = io { MediaBackup.scan(getApplication(), repo, settings) }

    private fun io(block: () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) { block() }
    }
}
