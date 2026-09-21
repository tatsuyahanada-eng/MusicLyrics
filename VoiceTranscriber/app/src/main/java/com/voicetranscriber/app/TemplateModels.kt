package com.voicetranscriber.app

import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * 定型文（コピペ用テンプレート）のデータモデル。
 *
 * 本文 [body] には差し込み用のトークンを埋め込む:
 *   {日付} {時間1} {時間2} {氏名}
 * 使用時にこれらを実際の値へ置き換えてコピーする。
 */
@Serializable
data class Template(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val body: String,
)

/** フォルダ（メイン項目）。中に複数の定型文を持つ。 */
@Serializable
data class TemplateCategory(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val templates: List<Template> = emptyList(),
)

@Serializable
data class TemplateStore(
    val categories: List<TemplateCategory> = emptyList(),
)

/** 本文に埋め込む差し込みトークン。 */
object TemplateTokens {
    const val DATE = "{日付}"
    const val TIME1 = "{時間1}"
    const val TIME2 = "{時間2}"
    const val NAME = "{氏名}"
    val all = listOf(DATE, TIME1, TIME2, NAME)

    /** 本文にそのトークンが含まれているか。 */
    fun contains(body: String, token: String): Boolean = body.contains(token)
}

/** 本文のトークンを実際の値で置き換える。 */
fun fillTemplate(
    body: String,
    date: String,
    time1: String,
    time2: String,
    name: String,
): String = body
    .replace(TemplateTokens.DATE, date)
    .replace(TemplateTokens.TIME1, time1)
    .replace(TemplateTokens.TIME2, time2)
    .replace(TemplateTokens.NAME, name)

/** 初回起動時の状態。サンプルは入れず空のフォルダ一覧から始める。 */
fun defaultTemplateStore(): TemplateStore = TemplateStore(categories = emptyList())
