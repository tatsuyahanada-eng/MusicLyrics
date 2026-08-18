package dev.hanada.tubevault.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.hanada.tubevault.AppContainer
import dev.hanada.tubevault.TubeVaultApp
import dev.hanada.tubevault.core.MediaKind
import dev.hanada.tubevault.core.SearchResult
import dev.hanada.tubevault.core.Storage
import dev.hanada.tubevault.core.VideoQuality
import dev.hanada.tubevault.data.CategoryEntity
import dev.hanada.tubevault.data.CategoryWithStats
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

class LibraryViewModel(private val container: AppContainer) : ViewModel() {

    val categories: StateFlow<List<CategoryWithStats>> = container.library.observeCategories()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIBE_TIMEOUT_MS), emptyList())

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

    /** Plays the whole folder starting at [index] — a category doubles as a playlist. */
    fun play(items: List<MediaItemEntity>, index: Int) {
        container.playback.playQueue(items, index)
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

    init {
        refresh()
    }

    fun refresh() {
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

    fun consumeToast() {
        _toast.value = null
    }
}
