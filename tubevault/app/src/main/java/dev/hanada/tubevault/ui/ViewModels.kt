package dev.hanada.tubevault.ui

import android.net.Uri
import android.os.Bundle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.hanada.tubevault.AppContainer
import dev.hanada.tubevault.TubeVaultApp
import dev.hanada.tubevault.browser.CookieExporter
import dev.hanada.tubevault.core.MediaKind
import dev.hanada.tubevault.core.SearchResult
import dev.hanada.tubevault.core.Storage
import dev.hanada.tubevault.core.VideoQuality
import dev.hanada.tubevault.data.CategoryEntity
import dev.hanada.tubevault.data.CategoryWithStats
import dev.hanada.tubevault.data.PlayerClient
import dev.hanada.tubevault.data.MediaItemEntity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private fun CreationExtras.container(): AppContainer {
    val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as TubeVaultApp
    return app.container
}

val AppViewModelFactory: ViewModelProvider.Factory = viewModelFactory {
    initializer { SearchViewModel(container()) }
    initializer { BrowseViewModel(container()) }
    initializer { LibraryViewModel(container()) }
    initializer { DownloadsViewModel(container()) }
    initializer { SettingsViewModel(container()) }
}

private const val SUBSCRIBE_TIMEOUT_MS = 5_000L

class SearchViewModel(private val container: AppContainer) : ViewModel() {

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _results = MutableStateFlow<List<SearchResult>>(emptyList())
    val results: StateFlow<List<SearchResult>> = _results.asStateFlow()

    private val _isSearching = MutableStateFlow(false)
    val isSearching: StateFlow<Boolean> = _isSearching.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _hasSearched = MutableStateFlow(false)
    val hasSearched: StateFlow<Boolean> = _hasSearched.asStateFlow()

    val categories: StateFlow<List<CategoryEntity>> = container.library.observeCategoryList()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIBE_TIMEOUT_MS), emptyList())

    /** `"<videoId>:<kind>"` set, used to badge rows that are already saved. */
    val downloadedKeys: StateFlow<Set<String>> = container.library.observeDownloadedKeys()
        .map { it.toSet() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIBE_TIMEOUT_MS), emptySet())

    val settings = container.settings.state

    val sharedLink = container.sharedLink

    fun onQueryChange(value: String) {
        _query.value = value
    }

    fun consumeSharedLink(url: String) {
        container.sharedLink.value = null
        _query.value = url
        search()
    }

    fun search() {
        val text = _query.value.trim()
        if (text.isEmpty() || _isSearching.value) return
        viewModelScope.launch {
            _isSearching.value = true
            _error.value = null
            try {
                _results.value = container.engine.search(text, container.settings.current.searchLimit)
                _hasSearched.value = true
            } catch (e: Exception) {
                _error.value = e.message ?: "検索に失敗しました"
                _results.value = emptyList()
            } finally {
                _isSearching.value = false
            }
        }
    }

    fun clear() {
        _query.value = ""
        _results.value = emptyList()
        _hasSearched.value = false
        _error.value = null
    }

    fun download(result: SearchResult, kind: MediaKind, quality: VideoQuality, categoryId: Long) {
        container.downloads.enqueue(result, kind, quality, categoryId)
    }

    fun rememberDefaults(kind: MediaKind, quality: VideoQuality, categoryId: Long) {
        container.settings.update {
            it.copy(defaultKind = kind, defaultQuality = quality, defaultCategoryId = categoryId)
        }
    }
}

/**
 * Backs the in-app browser. It holds the WebView's serialised history so
 * switching tabs does not throw the user back to the YouTube home page, and it
 * is where the browser's cookies get handed to the download engine.
 */
class BrowseViewModel(private val container: AppContainer) : ViewModel() {

    private var savedState: Bundle? = null

    private val _currentUrl = MutableStateFlow<String?>(null)
    val currentUrl: StateFlow<String?> = _currentUrl.asStateFlow()

    private val _pageTitle = MutableStateFlow("")
    val pageTitle: StateFlow<String> = _pageTitle.asStateFlow()

    fun onNavigated(url: String?, title: String?) {
        if (!url.isNullOrBlank() && url != "about:blank") _currentUrl.value = url
        onTitle(title)
    }

    fun onTitle(title: String?) {
        if (!title.isNullOrBlank()) _pageTitle.value = title
    }

    /** Called as pages settle, so the newest session is always on disk. */
    fun captureCookies() {
        CookieExporter.export(container.appContext)
    }

    fun saveState(bundle: Bundle) {
        savedState = bundle
    }

    fun consumeSavedState(): Bundle? = savedState
}

class LibraryViewModel(private val container: AppContainer) : ViewModel() {

    val categories: StateFlow<List<CategoryWithStats>> = container.library.observeCategories()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIBE_TIMEOUT_MS), emptyList())

    val settings = container.settings.state

    private val _importing = MutableStateFlow(false)
    val importing: StateFlow<Boolean> = _importing.asStateFlow()

    /** One-shot message about the last import, cleared once shown. */
    private val _importStatus = MutableStateFlow<String?>(null)
    val importStatus: StateFlow<String?> = _importStatus.asStateFlow()

    private val _openCategoryId = MutableStateFlow<Long?>(null)
    val openCategoryId: StateFlow<Long?> = _openCategoryId.asStateFlow()

    @OptIn(ExperimentalCoroutinesApi::class)
    val items: StateFlow<List<MediaItemEntity>> = _openCategoryId
        .flatMapLatest { id ->
            if (id == null) flowOf(emptyList()) else container.library.observeItems(id)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIBE_TIMEOUT_MS), emptyList())

    val openCategory: StateFlow<CategoryWithStats?> =
        combine(categories, _openCategoryId) { list, id ->
            list.firstOrNull { it.category.id == id }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIBE_TIMEOUT_MS), null)

    fun open(categoryId: Long) {
        _openCategoryId.value = categoryId
    }

    fun closeCategory() {
        _openCategoryId.value = null
    }

    fun createCategory(name: String) {
        viewModelScope.launch { container.library.createCategory(name) }
    }

    fun renameCategory(id: Long, name: String) {
        viewModelScope.launch { container.library.renameCategory(id, name) }
    }

    fun recolorCategory(id: Long, colorArgb: Int) {
        viewModelScope.launch { container.library.setCategoryColor(id, colorArgb) }
    }

    fun deleteCategory(id: Long, moveItemsTo: Long?) {
        viewModelScope.launch {
            if (_openCategoryId.value == id) _openCategoryId.value = null
            container.library.deleteCategory(id, moveItemsTo)
        }
    }

    fun moveItem(itemId: Long, targetCategoryId: Long) {
        viewModelScope.launch { container.library.moveItem(itemId, targetCategoryId) }
    }

    fun deleteItem(itemId: Long) {
        viewModelScope.launch {
            container.playback.stopIfPlaying(itemId)
            container.library.deleteItem(itemId)
        }
    }

    /**
     * Bulk versions run sequentially inside one coroutine: each move rewrites
     * rows and shuffles files between folders, so letting them overlap would
     * race the same directories against each other.
     */
    fun moveItems(itemIds: Collection<Long>, targetCategoryId: Long) {
        viewModelScope.launch {
            itemIds.forEach { container.library.moveItem(it, targetCategoryId) }
        }
    }

    fun deleteItems(itemIds: Collection<Long>) {
        viewModelScope.launch {
            itemIds.forEach {
                container.playback.stopIfPlaying(it)
                container.library.deleteItem(it)
            }
        }
    }

    fun toggleCompactLibrary() {
        container.settings.update { it.copy(compactLibrary = !it.compactLibrary) }
    }

    /**
     * Copies files chosen in the system picker into the open folder. Refuses to
     * overlap with itself: a second run while the first is still copying would
     * have both writing into the same directory, and the picker is easy to
     * trigger twice while a large video is still being read.
     */
    fun importFiles(uris: List<Uri>) {
        val target = _openCategoryId.value ?: return
        if (uris.isEmpty() || _importing.value) return
        viewModelScope.launch {
            _importing.value = true
            val outcome = runCatching { container.importer.import(uris, target) }.getOrNull()
            _importing.value = false
            _importStatus.value = when {
                outcome == null || outcome.imported == 0 -> "取り込めませんでした"
                outcome.failed > 0 ->
                    "${outcome.imported} 件を取り込みました（${outcome.failed} 件は失敗）"
                else -> "${outcome.imported} 件を取り込みました"
            }
        }
    }

    fun consumeImportStatus() {
        _importStatus.value = null
    }

    /** Plays the whole folder starting at [index] — a category doubles as a playlist. */
    fun play(items: List<MediaItemEntity>, index: Int) {
        container.playback.playQueue(items, index)
    }

    /** Plays the folder in random order, starting from a random track. */
    fun shuffle(items: List<MediaItemEntity>) {
        container.playback.playQueue(items, 0, shuffle = true)
    }
}

class DownloadsViewModel(private val container: AppContainer) : ViewModel() {

    val jobs = container.downloads.jobs

    fun cancel(jobId: String) = container.downloads.cancel(jobId)

    fun retry(jobId: String) = container.downloads.retry(jobId)

    fun remove(jobId: String) = container.downloads.remove(jobId)

    fun clearFinished() = container.downloads.clearFinished()
}

class SettingsViewModel(private val container: AppContainer) : ViewModel() {

    val settings = container.settings.state

    val categories: StateFlow<List<CategoryEntity>> = container.library.observeCategoryList()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIBE_TIMEOUT_MS), emptyList())

    private val _ytDlpVersion = MutableStateFlow("確認中…")
    val ytDlpVersion: StateFlow<String> = _ytDlpVersion.asStateFlow()

    private val _busyMessage = MutableStateFlow<String?>(null)
    val busyMessage: StateFlow<String?> = _busyMessage.asStateFlow()

    private val _storageBytes = MutableStateFlow(0L)
    val storageBytes: StateFlow<Long> = _storageBytes.asStateFlow()

    private val _toast = MutableStateFlow<String?>(null)
    val toast: StateFlow<String?> = _toast.asStateFlow()

    /** How many cookies the in-app browser has handed over, for status display. */
    private val _cookieCount = MutableStateFlow(0)
    val cookieCount: StateFlow<Int> = _cookieCount.asStateFlow()

    private val _signedIn = MutableStateFlow(false)
    val signedIn: StateFlow<Boolean> = _signedIn.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        _cookieCount.value = CookieExporter.cookieCount(container.appContext)
        _signedIn.value = CookieExporter.isSignedIn(container.appContext)
        viewModelScope.launch {
            _ytDlpVersion.value = container.engine.version()
        }
        viewModelScope.launch {
            _storageBytes.value = withContext(Dispatchers.IO) {
                Storage.directorySize(Storage.rootDir(container.appContext))
            }
        }
    }

    fun updateYtDlp() {
        if (_busyMessage.value != null) return
        viewModelScope.launch {
            _busyMessage.value = "yt-dlp を更新しています…"
            val message = runCatching { container.engine.updateYtDlp() }
                .fold(
                    onSuccess = { "更新結果: $it" },
                    onFailure = { "更新に失敗しました: ${it.message}" },
                )
            _busyMessage.value = null
            _toast.value = message
            _ytDlpVersion.value = container.engine.version()
            container.settings.update { it.copy(ytDlpUpdatedAt = System.currentTimeMillis()) }
        }
    }

    fun pruneMissing() {
        viewModelScope.launch {
            val removed = runCatching { container.library.pruneMissingFiles() }.getOrDefault(0)
            _toast.value = "見つからないファイル ${removed} 件を整理しました"
            refresh()
        }
    }

    fun setDefaultKind(kind: MediaKind) = container.settings.update { it.copy(defaultKind = kind) }

    fun setDefaultQuality(quality: VideoQuality) =
        container.settings.update { it.copy(defaultQuality = quality) }

    fun setDefaultCategory(id: Long) = container.settings.update { it.copy(defaultCategoryId = id) }

    fun setSearchLimit(limit: Int) = container.settings.update { it.copy(searchLimit = limit) }

    fun setPlayerClient(client: PlayerClient) =
        container.settings.update { it.copy(playerClient = client) }

    fun setUseCookies(enabled: Boolean) =
        container.settings.update { it.copy(useCookies = enabled) }

    fun setUsePoToken(enabled: Boolean) =
        container.settings.update { it.copy(usePoToken = enabled) }

    /**
     * Mints a token on demand and reports what happened. Attestation either
     * works on a given device's WebView or fails quietly, so there needs to be
     * a way to find out which without running a download to see.
     */
    fun probePoToken() {
        if (_busyMessage.value != null) return
        viewModelScope.launch {
            _busyMessage.value = "PO Token を生成しています…"
            val result = container.poTokens.probe()
            _busyMessage.value = null
            _toast.value = result.fold(
                onSuccess = { "成功: トークンを生成できました（${it.token.take(12)}…）" },
                onFailure = { "失敗: ${it.message}" },
            )
        }
    }

    fun clearCookies() {
        CookieExporter.clear(container.appContext)
        _cookieCount.value = 0
        _signedIn.value = false
        _toast.value = "ブラウザのログイン情報を消しました"
    }

    fun consumeToast() {
        _toast.value = null
    }
}
