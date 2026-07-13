import SwiftUI

struct IdView: View {
    @EnvironmentObject var store: AppStore

    private let teal = Color(red: 0/255, green: 137/255, blue: 123/255)

    var rows: [IdRow] { buildRows(brand: store.brand, storeNumber: store.storeNumber) }
    var valid: Bool { isValidStoreNumber(store.storeNumber) }

    var body: some View {
        ScrollView {
            VStack(spacing: 10) {
                // 対象機器
                Picker("対象機器", selection: $store.brand) {
                    ForEach(Brand.allCases) { Text($0.label).tag($0) }
                }
                .pickerStyle(.segmented)

                TextField("店名（空欄で可）", text: $store.storeName)
                    .textFieldStyle(.roundedBorder)

                TextField("共通番号（5桁）", text: Binding(
                    get: { store.storeNumber },
                    set: { store.storeNumber = String($0.filter { $0.isNumber }.prefix(5)) }
                ))
                .keyboardType(.numberPad)
                .textFieldStyle(.roundedBorder)

                if !store.storeNumber.isEmpty && !valid {
                    Text("5桁の数字を入力してください（残り \(5 - store.storeNumber.count) 桁）")
                        .font(.caption).foregroundColor(.red)
                        .frame(maxWidth: .infinity, alignment: .leading)
                }

                // 表
                VStack(spacing: 0) {
                    header
                    Divider()
                    ForEach(Array(rows.enumerated()), id: \.element.id) { idx, row in
                        rowView(row)
                        if idx < rows.count - 1 { Divider().opacity(0.4) }
                    }
                }
                .overlay(RoundedRectangle(cornerRadius: 4).stroke(Color.green.opacity(0.6)))

                Text("行をタップで ch番号＋10桁ID をコピー＆結果へ反映")
                    .font(.caption2).foregroundColor(.secondary)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
            .padding()
        }
    }

    private var header: some View {
        HStack(spacing: 2) {
            Text("規格").frame(width: 58).font(.system(size: 10, weight: .bold)).foregroundColor(.secondary)
            HStack(spacing: 0) {
                headerCell("規格", 2)
                headerCell("Ch設定値", 2)
                headerCell("番号", 5)
                headerCell("CD", 1)
            }
        }
        .padding(.vertical, 3).padding(.horizontal, 4)
    }

    private func headerCell(_ t: String, _ w: CGFloat) -> some View {
        Text(t).font(.system(size: 9, weight: .bold)).foregroundColor(.secondary)
            .frame(maxWidth: .infinity).layoutPriority(Double(w))
    }

    private func rowView(_ row: IdRow) -> some View {
        let hasStore = !row.store.isEmpty
        return HStack(spacing: 2) {
            VStack(spacing: 2) {
                HStack(spacing: 3) {
                    abCell("a", row.isASide)
                    abCell("b", !row.isASide)
                }
                Text("ch:\(row.ch)").font(.system(size: 11, weight: .bold)).foregroundColor(teal)
            }
            .frame(width: 58)

            HStack(spacing: 0) {
                digit(row.kikaku, Color(white: 0.80))
                digit(row.kikakuB, Color(red: 0.84, green: 0.80, blue: 0.78))
                digit(charAt(row.chSet, 0), Color(white: 0.85))
                digit(charAt(row.chSet, 1), Color(white: 0.85))
                ForEach(0..<5, id: \.self) { i in
                    digit(hasStore ? charAt(row.store, i) : "", hasStore ? Color(white: 0.90) : Color(white: 0.95))
                }
                digit(row.cd, row.cd.isEmpty ? Color(white: 0.95) : Color(red: 0.5, green: 0.8, blue: 0.77), cd: true)
            }
        }
        .padding(.vertical, 2).padding(.horizontal, 4)
        .contentShape(Rectangle())
        .onTapGesture {
            guard !row.fullId.isEmpty else { return }
            let value = "ch\(row.ch) \(row.fullId)"
            UIPasteboard.general.string = value
            store.selectedSystemId = value // 結果タブの変更後システムIDへ反映
        }
    }

    private func abCell(_ letter: String, _ active: Bool) -> some View {
        Text(letter)
            .font(.system(size: 15, weight: .bold))
            .foregroundColor(active ? .white : Color(white: 0.74))
            .frame(width: 24, height: 24)
            .background(active ? Color(white: 0.07) : Color(white: 0.94))
            .cornerRadius(3)
    }

    private func digit(_ s: String, _ tint: Color, cd: Bool = false) -> some View {
        Text(s)
            .font(.system(size: cd ? 15 : 13, weight: cd ? .bold : .medium, design: .monospaced))
            .frame(maxWidth: .infinity, minHeight: 30)
            .background(tint)
            .overlay(RoundedRectangle(cornerRadius: 3).stroke(Color(white: 0.7), lineWidth: 0.7))
            .cornerRadius(3)
            .padding(1)
    }

    private func charAt(_ s: String, _ i: Int) -> String {
        guard i < s.count else { return "" }
        return String(Array(s)[i])
    }
}
