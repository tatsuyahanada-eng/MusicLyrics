import Foundation
import Combine

/// 距離の表示単位。
enum DistUnit: String, CaseIterable, Identifiable {
    case m, cm
    var id: String { rawValue }
    var label: String { rawValue }

    /// メートル値を表示文字列に整形。
    func format(_ meters: Double) -> String {
        switch self {
        case .m: return String(format: "%.2f m", meters)
        case .cm: return String(format: "%.0f cm", meters * 100)
        }
    }
}

/// 距離の計測記録（値は常にメートルで保持）。
struct DistanceRecord: Identifiable, Codable, Equatable {
    var id = UUID()
    var memo: String
    var meters: Double

    func display(_ unit: DistUnit) -> String { unit.format(meters) }
}

/// アプリ全体で共有する状態（UserDefaults に自動保存）。
/// Android版の SharedPreferences 相当。
final class AppStore: ObservableObject {
    private let d = UserDefaults.standard

    // ── ID計算タブ ──
    @Published var brand: Brand { didSet { d.set(brand.rawValue, forKey: "brand") } }
    @Published var storeNumber: String { didSet { d.set(storeNumber, forKey: "storeNumber") } }
    @Published var storeName: String { didSet { d.set(storeName, forKey: "storeName") } }
    /// ID計算でタップして選んだ「変更後システムID」。
    @Published var selectedSystemId: String { didSet { d.set(selectedSystemId, forKey: "selectedSystemId") } }

    // ── 距離タブ ──
    @Published var unit: DistUnit { didSet { d.set(unit.rawValue, forKey: "distUnit") } }
    @Published var records: [DistanceRecord] { didSet { saveRecords() } }

    // ── 結果タブ ──（キー→値のマップ）
    @Published var result: [String: String] { didSet { saveResult() } }

    static let defaultEmail = "jrss-03@alljrs.co.jp"

    init() {
        brand = Brand(rawValue: UserDefaults.standard.string(forKey: "brand") ?? "") ?? .relier
        storeNumber = UserDefaults.standard.string(forKey: "storeNumber") ?? ""
        storeName = UserDefaults.standard.string(forKey: "storeName") ?? ""
        selectedSystemId = UserDefaults.standard.string(forKey: "selectedSystemId") ?? ""
        unit = DistUnit(rawValue: UserDefaults.standard.string(forKey: "distUnit") ?? "") ?? .m

        if let data = UserDefaults.standard.data(forKey: "records"),
           let recs = try? JSONDecoder().decode([DistanceRecord].self, from: data) {
            records = recs
        } else {
            records = []
        }
        if let data = UserDefaults.standard.data(forKey: "result"),
           let map = try? JSONDecoder().decode([String: String].self, from: data) {
            result = map
        } else {
            result = [:]
        }
    }

    private func saveRecords() {
        if let data = try? JSONEncoder().encode(records) { d.set(data, forKey: "records") }
    }
    private func saveResult() {
        if let data = try? JSONEncoder().encode(result) { d.set(data, forKey: "result") }
    }

    /// 結果タブが開かれたときの初期反映（Android版 LaunchedEffect 相当）。
    func syncResultDefaults() {
        if !selectedSystemId.isEmpty { result["変更後システムID"] = selectedSystemId }
        if (result["店舗名"] ?? "").isEmpty && !storeName.isEmpty { result["店舗名"] = storeName }
        let email = result["送信先メール"] ?? ""
        if email.isEmpty || email == "tatsuya.hanada@gmail.com" { result["送信先メール"] = Self.defaultEmail }
    }

    /// すべての記録を消して初期状態へ（初期化ボタン）。
    func clearAll() {
        brand = .relier
        storeNumber = ""
        storeName = ""
        selectedSystemId = ""
        records = []
        result = [:]
        // 単位は保持（好みの設定のため）
    }
}
