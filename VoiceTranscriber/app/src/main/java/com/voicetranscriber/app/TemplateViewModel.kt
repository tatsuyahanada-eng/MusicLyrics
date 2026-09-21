package com.voicetranscriber.app

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json

/**
 * 定型文の保存・読み込み・編集を担う ViewModel。
 * データは SharedPreferences に JSON で永続化する。
 */
class TemplateViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = app.getSharedPreferences("templates", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }

    private val _store = MutableStateFlow(load())
    val store: StateFlow<TemplateStore> = _store.asStateFlow()

    private fun load(): TemplateStore {
        val raw = prefs.getString(KEY, null)
        val hasSeeded = prefs.getBoolean(KEY_SEEDED, false)
        return if (raw.isNullOrBlank()) {
            if (hasSeeded) TemplateStore() else defaultTemplateStore().also { save(it) }
        } else {
            runCatching {
                json.decodeFromString(TemplateStore.serializer(), raw)
            }.getOrDefault(TemplateStore())
        }
    }

    private fun save(store: TemplateStore) {
        prefs.edit()
            .putString(KEY, json.encodeToString(TemplateStore.serializer(), store))
            .putBoolean(KEY_SEEDED, true)
            .apply()
    }

    private fun update(transform: (TemplateStore) -> TemplateStore) {
        val next = transform(_store.value)
        _store.value = next
        save(next)
    }

    // ----- カテゴリ -----

    fun addCategory(name: String) {
        if (name.isBlank()) return
        update { it.copy(categories = it.categories + TemplateCategory(name = name.trim())) }
    }

    fun renameCategory(categoryId: String, name: String) {
        if (name.isBlank()) return
        update { store ->
            store.copy(categories = store.categories.map {
                if (it.id == categoryId) it.copy(name = name.trim()) else it
            })
        }
    }

    fun deleteCategory(categoryId: String) {
        update { it.copy(categories = it.categories.filterNot { c -> c.id == categoryId }) }
    }

    // ----- 定型文 -----

    fun addTemplate(categoryId: String, name: String, body: String) {
        if (name.isBlank()) return
        update { store ->
            store.copy(categories = store.categories.map { c ->
                if (c.id == categoryId) {
                    c.copy(templates = c.templates + Template(name = name.trim(), body = body))
                } else c
            })
        }
    }

    fun updateTemplate(categoryId: String, templateId: String, name: String, body: String) {
        if (name.isBlank()) return
        update { store ->
            store.copy(categories = store.categories.map { c ->
                if (c.id == categoryId) {
                    c.copy(templates = c.templates.map { t ->
                        if (t.id == templateId) t.copy(name = name.trim(), body = body) else t
                    })
                } else c
            })
        }
    }

    fun deleteTemplate(categoryId: String, templateId: String) {
        update { store ->
            store.copy(categories = store.categories.map { c ->
                if (c.id == categoryId) {
                    c.copy(templates = c.templates.filterNot { it.id == templateId })
                } else c
            })
        }
    }

    /** フォルダ内で定型文を1つ前後に移動して並び替える。 */
    fun moveTemplate(categoryId: String, templateId: String, delta: Int) {
        update { store ->
            store.copy(categories = store.categories.map { c ->
                if (c.id != categoryId) return@map c
                val fromIndex = c.templates.indexOfFirst { it.id == templateId }
                val toIndex = fromIndex + delta
                if (fromIndex < 0 || toIndex < 0 || toIndex >= c.templates.size) return@map c
                val reordered = c.templates.toMutableList()
                val moved = reordered.removeAt(fromIndex)
                reordered.add(toIndex, moved)
                c.copy(templates = reordered)
            })
        }
    }

    private companion object {
        const val KEY = "store_json"
        const val KEY_SEEDED = "seeded"
    }
}
