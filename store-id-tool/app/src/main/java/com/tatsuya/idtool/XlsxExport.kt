package com.tatsuya.idtool

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 依存ライブラリなしで「無線テスト結果シート」形式の .xlsx を生成する軽量ライター。
 *
 * 添付テンプレート（結合セル・罫線・多行セル）を踏襲し、結果登録タブで最終入力した値を流し込む。
 * 列＝Ch（内部アンテナ=ｂ / 外部アンテナ=ａ）、行＝検証場所。各セルに
 * 「結果／電波強度 送信・受信／ノイズ 送信・受信／送信ﾊﾟｹ・受信ﾊﾟｹ」を配置する。
 */
object XlsxExport {

    // ── OOXML 固定パーツ ──
    private const val CONTENT_TYPES =
        "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n" +
        "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
        "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>" +
        "<Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
        "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>" +
        "<Override PartName=\"/xl/worksheets/sheet1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>" +
        "<Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/>" +
        "</Types>"

    private const val RELS =
        "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n" +
        "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
        "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/></Relationships>"

    private const val WORKBOOK =
        "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n" +
        "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">" +
        "<sheets><sheet name=\"結果シート\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>"

    private const val WB_RELS =
        "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n" +
        "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
        "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet1.xml\"/>" +
        "<Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/></Relationships>"

    // スタイル: 0=既定 1=タイトル 2=情報ラベル 3=情報値 4=角見出し 5=Ch見出し 6=場所見出し 7=結果セル 8=凡例
    private const val STYLES =
        "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n" +
        "<styleSheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">" +
        "<fonts count=\"5\">" +
        "<font><sz val=\"11\"/><name val=\"ＭＳ Ｐゴシック\"/></font>" +
        "<font><sz val=\"20\"/><name val=\"ＭＳ Ｐゴシック\"/></font>" +
        "<font><b/><sz val=\"11\"/><name val=\"ＭＳ Ｐゴシック\"/></font>" +
        "<font><b/><sz val=\"14\"/><name val=\"ＭＳ Ｐゴシック\"/></font>" +
        "<font><b/><sz val=\"12\"/><name val=\"ＭＳ Ｐゴシック\"/></font>" +
        "</fonts>" +
        "<fills count=\"4\">" +
        "<fill><patternFill patternType=\"none\"/></fill>" +
        "<fill><patternFill patternType=\"gray125\"/></fill>" +
        "<fill><patternFill patternType=\"solid\"><fgColor rgb=\"FFCFD8DC\"/></patternFill></fill>" +
        "<fill><patternFill patternType=\"solid\"><fgColor rgb=\"FFB0BEC5\"/></patternFill></fill>" +
        "</fills>" +
        "<borders count=\"2\">" +
        "<border><left/><right/><top/><bottom/><diagonal/></border>" +
        "<border><left style=\"thin\"><color rgb=\"FF607D8B\"/></left><right style=\"thin\"><color rgb=\"FF607D8B\"/></right>" +
        "<top style=\"thin\"><color rgb=\"FF607D8B\"/></top><bottom style=\"thin\"><color rgb=\"FF607D8B\"/></bottom><diagonal/></border>" +
        "</borders>" +
        "<cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs>" +
        "<cellXfs count=\"9\">" +
        "<xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\"/>" +
        "<xf numFmtId=\"0\" fontId=\"1\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyFont=\"1\" applyAlignment=\"1\"><alignment horizontal=\"left\" vertical=\"center\"/></xf>" +
        "<xf numFmtId=\"0\" fontId=\"2\" fillId=\"2\" borderId=\"1\" xfId=\"0\" applyFont=\"1\" applyFill=\"1\" applyBorder=\"1\" applyAlignment=\"1\"><alignment horizontal=\"left\" vertical=\"center\" wrapText=\"1\"/></xf>" +
        "<xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"1\" xfId=\"0\" applyBorder=\"1\" applyAlignment=\"1\"><alignment horizontal=\"left\" vertical=\"center\" wrapText=\"1\"/></xf>" +
        "<xf numFmtId=\"0\" fontId=\"2\" fillId=\"3\" borderId=\"1\" xfId=\"0\" applyFont=\"1\" applyFill=\"1\" applyBorder=\"1\" applyAlignment=\"1\"><alignment horizontal=\"center\" vertical=\"center\" wrapText=\"1\"/></xf>" +
        "<xf numFmtId=\"0\" fontId=\"3\" fillId=\"2\" borderId=\"1\" xfId=\"0\" applyFont=\"1\" applyFill=\"1\" applyBorder=\"1\" applyAlignment=\"1\"><alignment horizontal=\"center\" vertical=\"center\" wrapText=\"1\"/></xf>" +
        "<xf numFmtId=\"0\" fontId=\"4\" fillId=\"2\" borderId=\"1\" xfId=\"0\" applyFont=\"1\" applyFill=\"1\" applyBorder=\"1\" applyAlignment=\"1\"><alignment horizontal=\"center\" vertical=\"center\" wrapText=\"1\"/></xf>" +
        "<xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"1\" xfId=\"0\" applyBorder=\"1\" applyAlignment=\"1\"><alignment horizontal=\"left\" vertical=\"top\" wrapText=\"1\"/></xf>" +
        "<xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyAlignment=\"1\"><alignment horizontal=\"left\" vertical=\"center\"/></xf>" +
        "</cellXfs>" +
        "<cellStyles count=\"1\"><cellStyle name=\"Normal\" xfId=\"0\" builtinId=\"0\"/></cellStyles>" +
        "</styleSheet>"

    private fun esc(s: String): String = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&apos;")

    private fun colName(n: Int): String {
        var x = n
        val sb = StringBuilder()
        while (x > 0) {
            val r = (x - 1) % 26
            sb.insert(0, ('A' + r))
            x = (x - 1) / 26
        }
        return sb.toString()
    }

    private fun ref(col: Int, row: Int) = "${colName(col)}$row"

    private fun cellStr(col: Int, row: Int, style: Int, text: String): String =
        "<c r=\"${ref(col, row)}\" s=\"$style\" t=\"inlineStr\"><is><t xml:space=\"preserve\">${esc(text)}</t></is></c>"

    private fun cellEmpty(col: Int, row: Int, style: Int): String =
        "<c r=\"${ref(col, row)}\" s=\"$style\"/>"

    /** 各結果セルの多行テキストを、テンプレートと同じ並びで組み立てる。 */
    private fun cellText(
        data: Map<String, String>, loc: Int, ch: Int
    ): String {
        fun v(f: String) = data["loc${loc}_ch${ch}_$f"].orEmpty()
        return "結果：${v("結果")}　\n\n" +
            "電波強度 平均　\n" +
            "送信：${v("電波強度送信")}　\n受信：${v("電波強度受信")}　\n" +
            "ノイズ\n送信：${v("ノイズ送信")}　\n受信：${v("ノイズ受信")}　\n" +
            "送信ﾊﾟｹ：${v("送信ﾊﾟｹ")}　\n受信ﾊﾟｹ：${v("受信ﾊﾟｹ")}　"
    }

    /** Ch 番号からアンテナ種別ラベルを付ける（2.4GHz=内部ｂ / 5GHz=外部ａ）。 */
    private fun chLabel(ch: Int): String = (if (ch <= 14) "ｂ" else "ａ") + ch

    /** 結果登録の最終入力値から xlsx（ByteArray）を生成する。 */
    fun buildResultXlsx(
        idInfo: IdInfo, rows: List<IdRow>, data: Map<String, String>,
        locCount: Int, ordered: List<Record>, unit: DistUnit
    ): ByteArray {
        val nCol = 1 + rows.size
        val last = nCol
        val merges = ArrayList<String>()
        val sb = StringBuilder()

        // 行1: タイトル
        sb.append("<row r=\"1\" ht=\"30\" customHeight=\"1\">")
        sb.append(cellStr(1, 1, 1, "無線テスト結果表"))
        for (c in 2..last) sb.append(cellEmpty(c, 1, 1))
        sb.append("</row>")
        if (last >= 2) merges.add("A1:${colName(last)}1")

        // 行2〜7: 情報ブロック
        val info = listOf(
            "店番" to idInfo.storeNumber,
            "店舗名" to data["店舗名"].orEmpty(),
            "変更後システムID" to data["変更後システムID"].orEmpty(),
            "日付" to data["日付"].orEmpty(),
            "作業員" to data["作業員"].orEmpty(),
            "提供元" to "日本リテイルシステム株式会社　首都圏カスタマサポート部"
        )
        var r = 2
        for ((label, value) in info) {
            sb.append("<row r=\"$r\">")
            sb.append(cellStr(1, r, 2, label))
            sb.append(cellStr(2, r, 3, value))
            for (c in 3..last) sb.append(cellEmpty(c, r, 3))
            sb.append("</row>")
            if (last >= 3) merges.add("B$r:${colName(last)}$r")
            r++
        }

        // 行8: 凡例
        val note = "ａ：外部アンテナ　ｂ：内部アンテナ　／　結果：最適・良・圏外・送受信エラー・無線リンクエラー"
        sb.append("<row r=\"8\">")
        sb.append(cellStr(1, 8, 8, note))
        for (c in 2..last) sb.append(cellEmpty(c, 8, 8))
        sb.append("</row>")
        if (last >= 2) merges.add("A8:${colName(last)}8")

        // 行9: ヘッダー（角見出し＋各Ch）
        sb.append("<row r=\"9\" ht=\"44\" customHeight=\"1\">")
        sb.append(cellStr(1, 9, 4, "場所＼Ch"))
        rows.forEachIndexed { i, row ->
            val label = chLabel(row.ch) + if (row.fullId.isNotEmpty()) "\n${row.fullId}" else ""
            sb.append(cellStr(2 + i, 9, 5, label))
        }
        sb.append("</row>")

        // 行10〜: 検証場所ごと
        var rr = 10
        for (loc in 1..locCount) {
            val rec = ordered.getOrNull(loc - 1)
            val left = "場所$loc" + if (rec != null) "\n${rec.memo}\n${rec.display(unit)}" else ""
            sb.append("<row r=\"$rr\" ht=\"210\" customHeight=\"1\">")
            sb.append(cellStr(1, rr, 6, left))
            rows.forEachIndexed { i, row ->
                sb.append(cellStr(2 + i, rr, 7, cellText(data, loc, row.ch)))
            }
            sb.append("</row>")
            rr++
        }
        val lastRow = rr - 1

        val cols = "<cols><col min=\"1\" max=\"1\" width=\"16\" customWidth=\"1\"/>" +
            "<col min=\"2\" max=\"$last\" width=\"22\" customWidth=\"1\"/></cols>"
        val mc = if (merges.isEmpty()) "" else
            "<mergeCells count=\"${merges.size}\">" +
                merges.joinToString("") { "<mergeCell ref=\"$it\"/>" } + "</mergeCells>"

        val sheet = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n" +
            "<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">" +
            "<dimension ref=\"A1:${colName(last)}$lastRow\"/>" +
            "<sheetViews><sheetView workbookViewId=\"0\"/></sheetViews>" +
            "<sheetFormatPr defaultRowHeight=\"15\"/>" +
            cols + "<sheetData>" + sb + "</sheetData>" + mc +
            "<pageMargins left=\"0.3\" right=\"0.3\" top=\"0.5\" bottom=\"0.5\" header=\"0.3\" footer=\"0.3\"/>" +
            "</worksheet>"

        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { zip ->
            fun put(name: String, content: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            put("[Content_Types].xml", CONTENT_TYPES)
            put("_rels/.rels", RELS)
            put("xl/workbook.xml", WORKBOOK)
            put("xl/_rels/workbook.xml.rels", WB_RELS)
            put("xl/styles.xml", STYLES)
            put("xl/worksheets/sheet1.xml", sheet)
        }
        return bos.toByteArray()
    }

    /** 「無線テスト結果シート_日付_店舗名.xlsx」形式のファイル名を作る（ファイル名に使えない文字は除去）。 */
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
