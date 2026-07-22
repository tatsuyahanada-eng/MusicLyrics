package com.tatsuya.idtool

import android.content.Context
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * 所定様式「無線テスト結果シート」を、値だけ差し替えて出力する。
 *
 * assets/result_template.xlsx（提出先の正式フォーム）を読み込み、結果登録タブで
 * 最終入力した値を該当セルに流し込む。結合セル・罫線・多行レイアウトはテンプレートの
 * ままなので、体裁は原本と完全に一致する。
 *
 * テンプレの構成（固定）:
 *   列 → Ch : C=ch1(ｂ1) D=ch6(ｂ6) E=ch13(ｂ13) G=ch36(ａ36) H=ch40(ａ40) I=ch44(ａ44) K=ch48(ａ48)
 *   行 → 場所: 9=場所1 / 10=場所2 / 11=場所3 / 12=場所4（5以降は同じ様式で行を追加）
 */
object XlsxExport {

    private const val TEMPLATE_ASSET = "result_template.xlsx"
    private const val SHEET_PART = "xl/worksheets/sheet1.xml"

    // テンプレの列→Ch対応（アプリ既定の48ch表示と一致）
    private val COL_CH = listOf("C" to 1, "D" to 6, "E" to 13, "G" to 36, "H" to 40, "I" to 44, "K" to 48)
    private val TPL_ROWS = listOf(9, 10, 11, 12) // 場所1〜4

    private fun esc(s: String): String = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&apos;")

    /** 各結果セルの多行テキストを、テンプレートと同じ並びで組み立てる。 */
    private fun cellText(data: Map<String, String>, loc: Int, ch: Int): String {
        fun v(f: String) = data["loc${loc}_ch${ch}_$f"].orEmpty()
        return "結果：${v("結果")}　\n\n" +
            "電波強度 平均　\n" +
            "送信：${v("電波強度送信")}　\n受信：${v("電波強度受信")}　\n" +
            "ノイズ\n送信：${v("ノイズ送信")}　\n受信：${v("ノイズ受信")}　\n" +
            "送信ﾊﾟｹ：${v("送信ﾊﾟｹ")}　\n受信ﾊﾟｹ：${v("受信ﾊﾟｹ")}　"
    }

    /** 日付 "yyyy/MM/dd" → "yyyy年M月d日"（変換できなければ原文のまま）。 */
    private fun formatDate(raw: String): String {
        val m = Regex("""(\d{4})[/\-](\d{1,2})[/\-](\d{1,2})""").find(raw) ?: return raw
        val (y, mo, d) = m.destructured
        return "${y}年${mo.toInt()}月${d.toInt()}日"
    }

    /** 指定セルを inlineStr へ差し替える（style は維持）。1回だけ置換されることを前提とする。 */
    private fun setCell(xml: String, ref: String, text: String): String {
        val pat = Regex("<c r=\"$ref\"([^>]*?)(?:/>|>.*?</c>)", RegexOption.DOT_MATCHES_ALL)
        return pat.replace(xml) { m ->
            val attrs = m.groupValues[1]
            val s = Regex("s=\"(\\d+)\"").find(attrs)?.groupValues?.get(1)
            val sAttr = if (s != null) " s=\"$s\"" else ""
            "<c r=\"$ref\"$sAttr t=\"inlineStr\"><is><t xml:space=\"preserve\">${esc(text)}</t></is></c>"
        }
    }

    /** 場所5以降の追加行を、テンプレ最終行（行12）と同じ様式で組み立てる。 */
    private fun buildExtraRow(r: Int, loc: Int, locLabel: String, data: Map<String, String>): String {
        fun rc(col: String, ch: Int, style: String): String =
            "<c r=\"$col$r\" s=\"$style\" t=\"inlineStr\"><is><t xml:space=\"preserve\">${esc(cellText(data, loc, ch))}</t></is></c>"
        val sb = StringBuilder()
        sb.append("<row r=\"$r\" ht=\"152.25\" customHeight=\"1\">")
        sb.append("<c r=\"B$r\" s=\"3\" t=\"inlineStr\"><is><t xml:space=\"preserve\">${esc(locLabel)}</t></is></c>")
        sb.append(rc("C", 1, "8")); sb.append(rc("D", 6, "8"))
        sb.append(rc("E", 13, "29")); sb.append("<c r=\"F$r\" s=\"30\"/>")
        sb.append(rc("G", 36, "8")); sb.append(rc("H", 40, "8"))
        sb.append(rc("I", 44, "29")); sb.append("<c r=\"J$r\" s=\"31\"/>")
        sb.append(rc("K", 48, "29")); sb.append("<c r=\"L$r\" s=\"31\"/>")
        sb.append("</row>")
        return sb.toString()
    }

    /** 結果登録の最終入力値を、テンプレに流し込んだ xlsx（ByteArray）を返す。 */
    fun buildResultXlsx(
        context: Context, idInfo: IdInfo, data: Map<String, String>,
        locCount: Int, ordered: List<Record>, unit: DistUnit
    ): ByteArray {
        // テンプレを解凍（順序保持）
        val parts = LinkedHashMap<String, ByteArray>()
        ZipInputStream(context.assets.open(TEMPLATE_ASSET)).use { zin ->
            var e = zin.nextEntry
            while (e != null) {
                parts[e.name] = zin.readBytes()
                zin.closeEntry()
                e = zin.nextEntry
            }
        }
        var xml = String(parts[SHEET_PART]!!, Charsets.UTF_8)

        // ── ヘッダー情報 ──
        xml = setCell(xml, "C2", idInfo.storeNumber)                  // 店番
        xml = setCell(xml, "C3", data["店舗名"].orEmpty())            // 店舗名
        xml = setCell(xml, "J1", formatDate(data["日付"].orEmpty()))  // 日付
        xml = setCell(xml, "L4", data["作業員"].orEmpty())            // 作業員

        // 場所ラベル（場所名＋距離メモ）
        fun locLabel(loc: Int): String {
            val rec = ordered.getOrNull(loc - 1)
            return "場所$loc" + if (rec != null) "\n${rec.memo}\n${rec.display(unit)}" else ""
        }

        // ── 行9〜12（場所1〜4）を差し替え ──
        for ((i, rn) in TPL_ROWS.withIndex()) {
            val loc = i + 1
            if (loc <= locCount) {
                xml = setCell(xml, "B$rn", locLabel(loc))
                for ((col, ch) in COL_CH) xml = setCell(xml, "$col$rn", cellText(data, loc, ch))
            }
        }

        // ── 場所5以降は同じ様式で行を追加 ──
        if (locCount > 4) {
            val extraRows = StringBuilder()
            val extraMerges = StringBuilder()
            var r = 13
            for (loc in 5..locCount) {
                extraRows.append(buildExtraRow(r, loc, locLabel(loc), data))
                extraMerges.append("<mergeCell ref=\"E$r:F$r\"/><mergeCell ref=\"I$r:J$r\"/><mergeCell ref=\"K$r:L$r\"/>")
                r++
            }
            xml = xml.replace("</sheetData>", "$extraRows</sheetData>")
            val cm = Regex("<mergeCells count=\"(\\d+)\">").find(xml)
            if (cm != null) {
                val newCount = cm.groupValues[1].toInt() + (locCount - 4) * 3
                xml = xml.replace(cm.value, "<mergeCells count=\"$newCount\">")
                xml = xml.replace("</mergeCells>", "$extraMerges</mergeCells>")
            }
        }

        parts[SHEET_PART] = xml.toByteArray(Charsets.UTF_8)

        // 再圧縮
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { zout ->
            for ((name, bytes) in parts) {
                zout.putNextEntry(ZipEntry(name))
                zout.write(bytes)
                zout.closeEntry()
            }
        }
        return bos.toByteArray()
    }

    /** 「無線テスト結果シート_日付_店舗名.xlsx」形式のファイル名を作る。 */
    fun fileName(data: Map<String, String>): String {
        val rawDate = data["日付"].orEmpty()
        val date = if (rawDate.isBlank())
            java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.JAPAN).format(java.util.Date())
        else rawDate.replace("/", "").replace("-", "")
        val store = data["店舗名"].orEmpty().ifBlank { "店舗" }
            .replace(Regex("[\\\\/:*?\"<>|\\n\\r]"), "_").trim()
        return "無線テスト結果シート_${date}_$store.xlsx"
    }
}
