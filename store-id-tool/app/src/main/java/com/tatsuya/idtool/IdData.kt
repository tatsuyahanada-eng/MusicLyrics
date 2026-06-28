package com.tatsuya.idtool

/**
 * 元データ（Relier「ﾃﾞﾆｰｽﾞ」シート / MONSTERA「Sheet1」）の構造を再現したロジック。
 *
 * 9桁の並び（Excel の F〜N 列）:
 *   F        : 規格      … ブランドで固定（MONSTERA=4 / Relier=5）
 *   G,H,I    : Ch設定値  … ch 番号ごとに固定の 3 桁コード
 *   J,K,L,M,N: 店舗番号  … ユーザー入力の 5 桁（中央の「67200」）。全 ch 行で共通
 *
 * CD（Excel の O 列） = RIGHTB(SUM(F:N), 1)
 *   → 9 桁すべてを 1 桁ずつ足した合計の末尾 1 桁。
 *     店舗番号が未入力（5 桁未満）なら空白。
 */
enum class Brand(val label: String, val kikaku: Int) {
    MONSTERA("MONSTERA（モンステラ）", 4),
    RELIER("Relier（ルリエ）", 5)
}

/** ch 番号 → Ch設定値（G,H,I の 3 桁コード）。両ブランド共通の固定表。 */
data class ChannelDef(val ch: Int, val code: String)

val CHANNELS: List<ChannelDef> = listOf(
    ChannelDef(1, "101"),
    ChannelDef(6, "106"),
    ChannelDef(13, "113"),
    ChannelDef(36, "001"),
    ChannelDef(40, "002"),
    ChannelDef(44, "003"),
    ChannelDef(48, "004"),
    ChannelDef(100, "009"),
    ChannelDef(120, "014"),
    ChannelDef(124, "015"),
)

data class IdRow(
    val ch: Int,
    val kikaku: String,   // F
    val chCode: String,   // G,H,I
    val store: String,    // J,K,L,M,N（未入力なら空文字）
    val cd: String        // CD（未入力なら空文字）
) {
    /** 9 桁 + CD を連結した完成 ID（未入力なら空文字）。 */
    val fullId: String get() = if (store.isEmpty()) "" else kikaku + chCode + store + cd
    /** 9 桁部分（CD なし）。 */
    val nineDigits: String get() = if (store.isEmpty()) "" else kikaku + chCode + store
}

/** 店舗番号が有効な 5 桁の数字かどうか。 */
fun isValidStoreNumber(storeNumber: String): Boolean =
    storeNumber.length == 5 && storeNumber.all { it.isDigit() }

/**
 * 選択ブランドと店舗番号から、全 ch の行を生成する。
 * 店舗番号が 5 桁の数字でない場合は CD・店舗番号を空欄で返す。
 */
fun buildRows(brand: Brand, storeNumber: String): List<IdRow> {
    val valid = isValidStoreNumber(storeNumber)
    return CHANNELS.map { c ->
        if (!valid) {
            IdRow(ch = c.ch, kikaku = brand.kikaku.toString(), chCode = c.code, store = "", cd = "")
        } else {
            val nine = "${brand.kikaku}${c.code}$storeNumber" // 1 + 3 + 5 = 9 桁
            val sum = nine.sumOf { it - '0' }
            val cd = (sum % 10).toString()
            IdRow(ch = c.ch, kikaku = brand.kikaku.toString(), chCode = c.code, store = storeNumber, cd = cd)
        }
    }
}
