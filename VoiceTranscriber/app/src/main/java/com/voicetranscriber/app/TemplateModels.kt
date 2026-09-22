package com.voicetranscriber.app

import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * 定型文の種類。コピー用とメール用は文章がまったく別物なので、
 * フォルダ・定型文・共通項目・金額プリセットをそれぞれ独立して持つ。
 */
enum class TemplateKind { COPY, MAIL }

/**
 * 定型文（コピペ／メール送信用テンプレート）のデータモデル。
 *
 * 本文 [body]・件名 [subject] には差し込み用のトークンを埋め込む:
 *   {日付} {時間1} {時間2} {金額1} {金額2} {氏名}
 * 使用時にこれらを実際の値へ置き換えて、コピーまたはメール送信する。
 *
 * [email] [subject] はメール用の定型文でのみ使う。
 * 既定値を持たせてあるので、これらを含まない古い保存データも読み込める。
 */
@Serializable
data class Template(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val body: String,
    /** メール用：既定の宛先。空なら送信時に手入力する。 */
    val email: String = "",
    /** メール用：既定の件名。トークンの差し込みに対応。 */
    val subject: String = "",
)

/** フォルダ（メイン項目）。中に複数の定型文を持つ。 */
@Serializable
data class TemplateCategory(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val templates: List<Template> = emptyList(),
)

/**
 * 保存データ全体。
 * [categories] / [commonInsert] / [amountPresets] がコピー用、
 * [mailCategories] / [mailCommonInsert] / [mailAmountPresets] がメール用。
 * 後から追加したフィールドはすべて既定値つきで、後方互換を保っている。
 */
@Serializable
data class TemplateStore(
    val categories: List<TemplateCategory> = emptyList(),
    val commonInsert: String = "",
    val mailCategories: List<TemplateCategory> = emptyList(),
    val mailCommonInsert: String = "",
    /** {金額1}{金額2} の入力でよく使う値をあらかじめ登録しておき、選ぶだけで使えるようにする。 */
    val amountPresets: List<String> = emptyList(),
    val mailAmountPresets: List<String> = emptyList(),
)

/** 種類に応じたフォルダ一覧。 */
fun TemplateStore.categoriesOf(kind: TemplateKind): List<TemplateCategory> =
    if (kind == TemplateKind.MAIL) mailCategories else categories

/** 種類に応じた共通項目。 */
fun TemplateStore.commonInsertOf(kind: TemplateKind): String =
    if (kind == TemplateKind.MAIL) mailCommonInsert else commonInsert

/** 種類に応じた金額プリセット一覧。 */
fun TemplateStore.amountPresetsOf(kind: TemplateKind): List<String> =
    if (kind == TemplateKind.MAIL) mailAmountPresets else amountPresets

/** 本文に埋め込む差し込みトークン。 */
object TemplateTokens {
    const val DATE = "{日付}"
    const val TIME1 = "{時間1}"
    const val TIME2 = "{時間2}"
    const val AMOUNT1 = "{金額1}"
    const val AMOUNT2 = "{金額2}"
    const val NAME = "{氏名}"
    val all = listOf(DATE, TIME1, TIME2, AMOUNT1, AMOUNT2, NAME)

    /** そのテキストにトークンが含まれているか。 */
    fun contains(body: String, token: String): Boolean = body.contains(token)
}

/** 差し込み項目の入力値一式。トークン文字列をキーに持つ。 */
data class TokenValues(
    val date: String = "",
    val time1: String = "",
    val time2: String = "",
    val amount1: String = "",
    val amount2: String = "",
    val name: String = "",
) {
    private val map: Map<String, String> = mapOf(
        TemplateTokens.DATE to date,
        TemplateTokens.TIME1 to time1,
        TemplateTokens.TIME2 to time2,
        TemplateTokens.AMOUNT1 to amount1,
        TemplateTokens.AMOUNT2 to amount2,
        TemplateTokens.NAME to name,
    )

    /** 本文・件名のトークンを実際の値で置き換える。 */
    fun fill(text: String): String = map.entries.fold(text) { acc, (token, value) -> acc.replace(token, value) }
}

/** 本文と件名を合わせて、そのトークンが使われているか判定する。 */
fun Template.usesToken(token: String): Boolean =
    TemplateTokens.contains(body, token) || TemplateTokens.contains(subject, token)

/** 初回起動時の状態。サンプルは入れず空のフォルダ一覧から始める。 */
fun defaultTemplateStore(): TemplateStore = TemplateStore()
