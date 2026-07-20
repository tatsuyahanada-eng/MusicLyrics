import SwiftUI
import MessageUI

private let resultOptions = ["", "最適", "良", "圏外", "送受信エラー"]
private let fieldDefs = ["電波強度送信", "電波強度受信", "ノイズ送信", "ノイズ受信", "送信ﾊﾟｹ", "受信ﾊﾟｹ"]
private let fieldLabels = ["電波送", "電波受", "ﾉｲｽﾞ送", "ﾉｲｽﾞ受", "ﾊﾟｹ送", "ﾊﾟｹ受"]

struct ResultView: View {
    @EnvironmentObject var store: AppStore
    @State private var locCount = 5
    @State private var mailData: MailPayload? = nil
    @State private var shareURL: URL? = nil

    private let teal = Color(red: 0/255, green: 137/255, blue: 123/255)
    private let headerBg = Color(red: 0.81, green: 0.85, blue: 0.86)
    private let cellW: CGFloat = 190

    var rows: [IdRow] { buildRows(brand: store.brand, storeNumber: store.storeNumber) }

    /// 未設定なら既定で48ch超を非表示。
    var hiddenChs: Set<Int> {
        if let raw = store.result["非表示Ch"] {
            return Set(raw.split(separator: ",").compactMap { Int($0) })
        }
        return Set(rows.filter { $0.ch > 48 }.map { $0.ch })
    }
    var visibleRows: [IdRow] { rows.filter { !hiddenChs.contains($0.ch) } }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 8) {
                header
                Text("対象機器: \(store.brand.label)").font(.subheadline).bold().foregroundColor(teal)

                Text("既定は48chまで表示。× で列を隠す／下の「戻す」で48ch超を追加")
                    .font(.caption2).foregroundColor(.secondary)
                restoreRow

                gridScroll

                HStack {
                    Button("CSVコピー") { UIPasteboard.general.string = buildCsv() }
                    Spacer()
                    Button("CSV共有") { shareCsv() }
                }
                Button(action: sendMail) {
                    Text("メールで送信（結果CSVを添付）").bold()
                        .frame(maxWidth: .infinity).padding(10)
                        .background(teal).foregroundColor(.white).cornerRadius(8)
                }
                .disabled(!MFMailComposeViewController.canSendMail())
                if !MFMailComposeViewController.canSendMail() {
                    Text("※ 端末の「メール」アプリの設定が必要です").font(.caption2).foregroundColor(.secondary)
                }
            }
            .padding()
        }
        .onAppear {
            store.syncResultDefaults()
            if let n = Int(store.result["場所数"] ?? "") { locCount = max(5, n) }
        }
        .sheet(item: $mailData) { m in
            MailView(payload: m) { mailData = nil }
        }
        .sheet(item: Binding(get: { shareURL.map { CsvItem(url: $0) } }, set: { shareURL = $0?.url })) { item in
            ActivityView(items: [item.url])
        }
    }

    // MARK: ヘッダー
    private var header: some View {
        VStack(spacing: 6) {
            field("変更後システムID")
            field("店舗名")
            field("日付")
            HStack { field("開始時間"); field("終了時間") }
            field("作業員")
            field("備考")
            field("送信先メール")
        }
    }

    private func field(_ key: String) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(key).font(.caption2).foregroundColor(.secondary)
            TextField(key, text: bind(key))
                .textFieldStyle(.roundedBorder)
                .keyboardType(key == "送信先メール" ? .emailAddress : .default)
                .autocorrectionDisabled()
        }
    }

    private func bind(_ key: String) -> Binding<String> {
        Binding(get: { store.result[key] ?? "" }, set: { store.result[key] = $0 })
    }

    // MARK: 非表示列の復元
    @ViewBuilder private var restoreRow: some View {
        let hid = hiddenChs.sorted()
        if !hid.isEmpty {
            ScrollView(.horizontal, showsIndicators: false) {
                HStack {
                    Text("非表示の列:").font(.caption)
                    ForEach(hid, id: \.self) { ch in
                        Button("ch:\(ch) を戻す") { setHidden(hiddenChs.subtracting([ch])) }
                            .font(.caption).buttonStyle(.bordered)
                    }
                    Button("すべて表示") { store.result["非表示Ch"] = "" }.font(.caption)
                }
            }
        }
    }

    private func setHidden(_ s: Set<Int>) {
        store.result["非表示Ch"] = s.sorted().map(String.init).joined(separator: ",")
    }

    // MARK: グリッド
    private var gridScroll: some View {
        ScrollView(.horizontal, showsIndicators: true) {
            VStack(spacing: 0) {
                HStack(spacing: 0) {
                    cornerCell
                    ForEach(visibleRows) { row in chHeader(row) }
                }
                ForEach(1...locCount, id: \.self) { l in
                    HStack(spacing: 0) {
                        locCell(l)
                        ForEach(visibleRows) { row in cell(l, row) }
                    }
                }
            }
        }
        .overlay(alignment: .topTrailing) {
            Button("＋場所追加") { locCount += 1; store.result["場所数"] = "\(locCount)" }
                .font(.caption).padding(4)
        }
    }

    private var cornerCell: some View {
        Text("場所＼Ch").font(.caption2).bold()
            .frame(width: 96, height: 54).background(Color(red: 0.69, green: 0.75, blue: 0.77))
            .border(Color(white: 0.7), width: 0.7)
    }

    private func chHeader(_ row: IdRow) -> some View {
        ZStack(alignment: .topTrailing) {
            VStack {
                Text("ch:\(row.ch)").font(.caption).bold().foregroundColor(teal)
                Text(row.fullId.isEmpty ? "（番号未入力）" : row.fullId)
                    .font(.system(size: 11, design: .monospaced)).lineLimit(1)
            }
            .frame(width: cellW, height: 54).background(headerBg)
            .border(Color(white: 0.7), width: 0.7)
            Button { setHidden(hiddenChs.union([row.ch])) } label: {
                Image(systemName: "xmark").font(.caption2).foregroundColor(.red).padding(4)
            }
        }
    }

    private func locCell(_ l: Int) -> some View {
        // 距離は測定順（古い順）＝場所順
        let ordered = store.records.reversed().map { $0 }
        let rec: DistanceRecord? = (l - 1) < ordered.count ? ordered[l - 1] : nil
        return VStack(alignment: .leading, spacing: 2) {
            Text("場所\(l)").font(.subheadline).bold()
            if let r = rec {
                Text(r.memo).font(.caption2).foregroundColor(.secondary)
                Text(r.display(store.unit)).font(.caption).bold().foregroundColor(teal)
            }
        }
        .frame(width: 96, height: 90, alignment: .topLeading).padding(4)
        .background(headerBg).border(Color(white: 0.7), width: 0.7)
    }

    private func cell(_ l: Int, _ row: IdRow) -> some View {
        func key(_ f: String) -> String { "loc\(l)_ch\(row.ch)_\(f)" }
        return VStack(spacing: 3) {
            Picker("", selection: bind(key("結果"))) {
                ForEach(resultOptions, id: \.self) { Text($0.isEmpty ? "—" : $0).tag($0) }
            }.pickerStyle(.menu).frame(maxWidth: .infinity)

            ForEach(0..<3, id: \.self) { r in
                HStack(spacing: 4) {
                    numField(fieldLabels[r*2], key(fieldDefs[r*2]))
                    numField(fieldLabels[r*2+1], key(fieldDefs[r*2+1]))
                }
            }
        }
        .frame(width: cellW, height: 90).padding(4)
        .border(Color(white: 0.7), width: 0.7)
    }

    private func numField(_ label: String, _ key: String) -> some View {
        TextField(label, text: Binding(
            get: { store.result[key] ?? "" },
            set: { store.result[key] = String($0.filter { $0.isNumber }.prefix(3)) }
        ))
        .keyboardType(.numberPad).font(.caption2).textFieldStyle(.roundedBorder)
    }

    // MARK: CSV / メール
    private func csvEscape(_ s: String) -> String {
        if s.contains(",") || s.contains("\"") || s.contains("\n") {
            return "\"" + s.replacingOccurrences(of: "\"", with: "\"\"") + "\""
        }
        return s
    }

    private func buildCsv() -> String {
        var sb = ""
        func r(_ k: String) -> String { store.result[k] ?? "" }
        sb += "変更後システムID,\(csvEscape(r("変更後システムID")))\n"
        sb += "店舗名,\(csvEscape(r("店舗名")))\n"
        sb += "日付,\(csvEscape(r("日付")))\n"
        sb += "開始時間,\(csvEscape(r("開始時間")))\n"
        sb += "終了時間,\(csvEscape(r("終了時間")))\n"
        sb += "作業員,\(csvEscape(r("作業員")))\n"
        sb += "備考,\(csvEscape(r("備考")))\n"
        sb += "対象機器,\(csvEscape(store.brand.label))\n\n"

        sb += "項目＼Ch"
        for row in visibleRows { sb += ",\(csvEscape("ch:\(row.ch) (\(row.fullId.isEmpty ? "-" : row.fullId))"))" }
        sb += "\n"

        let ordered = store.records.reversed().map { $0 }
        for loc in 1...locCount {
            let rec: DistanceRecord? = (loc - 1) < ordered.count ? ordered[loc - 1] : nil
            let distLabel = rec.map { "\($0.memo) = \($0.display(store.unit))" } ?? ""
            sb += "【場所\(loc)】,\(csvEscape(distLabel))\n"
            for (i, f) in (["結果"] + fieldDefs).enumerated() {
                _ = i
                sb += csvEscape(f)
                for row in visibleRows { sb += ",\(csvEscape(store.result["loc\(loc)_ch\(row.ch)_\(f)"] ?? ""))" }
                sb += "\n"
            }
            sb += "\n"
        }
        return sb.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private func stamp() -> String {
        let df = DateFormatter(); df.dateFormat = "yyyyMMdd_HHmm"; return df.string(from: Date())
    }

    private func csvURL() -> URL {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("無線テスト結果_\(stamp()).csv")
        let bom = "\u{FEFF}"
        try? (bom + buildCsv()).data(using: .utf8)?.write(to: url)
        return url
    }

    private func shareCsv() { shareURL = csvURL() }

    private func sendMail() {
        let store = self.store
        let subject = ["無線チャンネル変更", store.result["店舗名"] ?? "", store.result["日付"] ?? ""]
            .filter { !$0.isEmpty }.joined(separator: " ")
        var body = "お疲れ様です。\n無線チャンネル変更作業の結果を送付いたします。\n\n"
        if let v = store.result["店舗名"], !v.isEmpty { body += "店舗名：\(v)\n" }
        if let v = store.result["変更後システムID"], !v.isEmpty { body += "変更後システムID：\(v)\n" }
        if let v = store.result["日付"], !v.isEmpty { body += "日付：\(v)\n" }
        body += "対象機器：\(store.brand.label)\n\n添付：無線テスト結果表（CSV）\n\nご確認のほど、よろしくお願いいたします。"

        let email = store.result["送信先メール"] ?? AppStore.defaultEmail
        let data = ("\u{FEFF}" + buildCsv()).data(using: .utf8) ?? Data()
        mailData = MailPayload(to: email, subject: subject, body: body,
                               attachment: data, filename: "無線テスト結果_\(stamp()).csv")
    }
}

private struct CsvItem: Identifiable { let id = UUID(); let url: URL }

// MARK: メール送信
struct MailPayload: Identifiable {
    let id = UUID()
    let to: String
    let subject: String
    let body: String
    let attachment: Data
    let filename: String
}

struct MailView: UIViewControllerRepresentable {
    let payload: MailPayload
    let onFinish: () -> Void

    func makeCoordinator() -> Coordinator { Coordinator(onFinish: onFinish) }

    func makeUIViewController(context: Context) -> MFMailComposeViewController {
        let vc = MFMailComposeViewController()
        vc.mailComposeDelegate = context.coordinator
        if !payload.to.isEmpty { vc.setToRecipients([payload.to]) }
        vc.setSubject(payload.subject)
        vc.setMessageBody(payload.body, isHTML: false)
        vc.addAttachmentData(payload.attachment, mimeType: "text/csv", fileName: payload.filename)
        return vc
    }
    func updateUIViewController(_ vc: MFMailComposeViewController, context: Context) {}

    final class Coordinator: NSObject, MFMailComposeViewControllerDelegate {
        let onFinish: () -> Void
        init(onFinish: @escaping () -> Void) { self.onFinish = onFinish }
        func mailComposeController(_ controller: MFMailComposeViewController,
                                   didFinishWith result: MFMailComposeResult, error: Error?) {
            onFinish()
        }
    }
}
