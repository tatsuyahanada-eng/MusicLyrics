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
 *
 * コピー用（[TemplateKind.COPY]）とメール用（[TemplateKind.MAIL]）は
 * 別々のフォルダ一覧として持つので、すべての操作が kind を受け取る。
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

    /** 種類に対応するフォルダ一覧だけを差し替える。 */
    private fun updateCategories(
        kind: TemplateKind,
        transform: (List<TemplateCategory>) -> List<TemplateCategory>,
    ) {
        update { s ->
            if (kind == TemplateKind.MAIL) {
                s.copy(mailCategories = transform(s.mailCategories))
            } else {
                s.copy(categories = transform(s.categories))
            }
        }
    }

    // ----- フォルダ -----

    fun addCategory(kind: TemplateKind, name: String) {
        if (name.isBlank()) return
        updateCategories(kind) { it + TemplateCategory(name = name.trim()) }
    }

    fun renameCategory(kind: TemplateKind, categoryId: String, name: String) {
        if (name.isBlank()) return
        updateCategories(kind) { cats ->
            cats.map { if (it.id == categoryId) it.copy(name = name.trim()) else it }
        }
    }

    fun deleteCategory(kind: TemplateKind, categoryId: String) {
        updateCategories(kind) { cats -> cats.filterNot { it.id == categoryId } }
    }

    /** フォルダを1つ前後に動かす。実際に動いたら true。 */
    fun moveCategory(kind: TemplateKind, categoryId: String, delta: Int): Boolean {
        var moved = false
        updateCategories(kind) { cats ->
            val from = cats.indexOfFirst { it.id == categoryId }
            val to = from + delta
            if (from < 0 || to < 0 || to >= cats.size) return@updateCategories cats
            val list = cats.toMutableList()
            list.add(to, list.removeAt(from))
            moved = true
            list
        }
        return moved
    }

    // ----- 定型文 -----

    fun addTemplate(
        kind: TemplateKind,
        categoryId: String,
        name: String,
        body: String,
        email: String = "",
        subject: String = "",
    ) {
        if (name.isBlank()) return
        updateCategories(kind) { cats ->
            cats.map { c ->
                if (c.id == categoryId) {
                    c.copy(
                        templates = c.templates + Template(
                            name = name.trim(),
                            body = body,
                            email = email.trim(),
                            subject = subject.trim(),
                        ),
                    )
                } else c
            }
        }
    }

    fun updateTemplate(
        kind: TemplateKind,
        categoryId: String,
        templateId: String,
        name: String,
        body: String,
        email: String = "",
        subject: String = "",
    ) {
        if (name.isBlank()) return
        updateCategories(kind) { cats ->
            cats.map { c ->
                if (c.id == categoryId) {
                    c.copy(templates = c.templates.map { t ->
                        if (t.id == templateId) {
                            t.copy(
                                name = name.trim(),
                                body = body,
                                email = email.trim(),
                                subject = subject.trim(),
                            )
                        } else t
                    })
                } else c
            }
        }
    }

    fun deleteTemplate(kind: TemplateKind, categoryId: String, templateId: String) {
        updateCategories(kind) { cats ->
            cats.map { c ->
                if (c.id == categoryId) {
                    c.copy(templates = c.templates.filterNot { it.id == templateId })
                } else c
            }
        }
    }

    /**
     * フォルダ内で定型文を1つ前後に移動して並び替える。実際に動いたら true。
     * 長押しドラッグでの並び替えから、1行ぶんまたぐたびに呼ばれる。
     */
    fun moveTemplate(
        kind: TemplateKind,
        categoryId: String,
        templateId: String,
        delta: Int,
    ): Boolean {
        var moved = false
        updateCategories(kind) { cats ->
            cats.map { c ->
                if (c.id != categoryId) return@map c
                val from = c.templates.indexOfFirst { it.id == templateId }
                val to = from + delta
                if (from < 0 || to < 0 || to >= c.templates.size) return@map c
                val list = c.templates.toMutableList()
                list.add(to, list.removeAt(from))
                moved = true
                c.copy(templates = list)
            }
        }
        return moved
    }

    // ----- 共通項目 -----

    /** 複数の定型文をまとめるときに間へ差し込む共通の文言を更新する。 */
    fun setCommonInsert(kind: TemplateKind, text: String) {
        update { s ->
            if (kind == TemplateKind.MAIL) s.copy(mailCommonInsert = text) else s.copy(commonInsert = text)
        }
    }

    private companion object {
        const val KEY = "store_json"
        const val KEY_SEEDED = "seeded"
    }
}
