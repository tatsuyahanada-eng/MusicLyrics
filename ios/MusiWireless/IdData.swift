import Foundation

/// 対象機器（ブランド）。規格a（F列）と、ブランドごとの ch リストを持つ。
/// Android版 IdData.kt と同じロジック。
enum Brand: String, CaseIterable, Identifiable {
    case monstera = "Monstera"
    case relier = "Relier"

    var id: String { rawValue }
    var label: String { rawValue }

    /// 規格a（F列）。ブランドで固定。
    var kikaku: Int {
        switch self {
        case .monstera: return 4
        case .relier: return 5
        }
    }

    /// (ch番号, Ch設定値3桁コード=G,H,I)。表示順もこの並び。
    var channels: [(ch: Int, code: String)] {
        switch self {
        case .monstera:
            return [(36, "001"), (40, "002"), (44, "003"), (48, "004"),
                    (1, "101"), (6, "106"), (13, "113")]
        case .relier:
            return [(1, "101"), (6, "106"), (13, "113"),
                    (36, "001"), (40, "002"), (44, "003"), (48, "004"),
                    (100, "009"), (120, "014"), (124, "015"),
                    (128, "016"), (132, "017"), (136, "018"), (140, "019")]
        }
    }
}

/// 1 ch 分の行。
struct IdRow: Identifiable {
    let ch: Int
    let kikaku: String   // F（1桁）
    let chCode: String   // G,H,I（3桁）… 先頭が規格b
    let store: String    // J〜N（5桁。未入力なら空）
    let cd: String       // CD（1桁。未入力なら空）

    var id: Int { ch }

    /// 規格b（G）。"0"→a側, "1"→b側。
    var kikakuB: String { String(chCode.prefix(1)) }
    var isASide: Bool { kikakuB == "0" }
    /// Ch設定値の下2桁（H,I）。
    var chSet: String { String(chCode.dropFirst()) }

    /// 完成した10桁ID（未入力なら空）。
    var fullId: String {
        store.isEmpty ? "" : kikaku + chCode + store + cd
    }
}

/// 共通番号が有効な5桁の数字か。
func isValidStoreNumber(_ s: String) -> Bool {
    s.count == 5 && s.allSatisfy { $0.isNumber }
}

/// ブランド＋共通番号から全 ch 行を生成する。
func buildRows(brand: Brand, storeNumber: String) -> [IdRow] {
    let valid = isValidStoreNumber(storeNumber)
    return brand.channels.map { c in
        if !valid {
            return IdRow(ch: c.ch, kikaku: String(brand.kikaku), chCode: c.code, store: "", cd: "")
        } else {
            let nine = "\(brand.kikaku)\(c.code)\(storeNumber)" // 1+3+5 = 9桁
            let sum = nine.compactMap { $0.wholeNumberValue }.reduce(0, +)
            let cd = String(sum % 10)
            return IdRow(ch: c.ch, kikaku: String(brand.kikaku), chCode: c.code, store: storeNumber, cd: cd)
        }
    }
}
