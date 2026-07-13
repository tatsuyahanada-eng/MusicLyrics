import SwiftUI

@main
struct MusiWirelessApp: App {
    @StateObject private var store = AppStore()

    var body: some Scene {
        WindowGroup {
            RootView().environmentObject(store)
        }
    }
}

struct RootView: View {
    @EnvironmentObject var store: AppStore
    @State private var showResetAlert = false

    var body: some View {
        TabView {
            NavWrap(title: "無線チャンネル変更", showReset: $showResetAlert) { IdView() }
                .tabItem { Label("ID計算", systemImage: "number") }

            NavWrap(title: "距離測定", showReset: $showResetAlert) { DistanceView() }
                .tabItem { Label("距離", systemImage: "ruler") }

            NavWrap(title: "作図（見取り図）", showReset: $showResetAlert) { DrawView() }
                .tabItem { Label("作図", systemImage: "map") }

            NavWrap(title: "結果入力", showReset: $showResetAlert) { ResultView() }
                .tabItem { Label("結果", systemImage: "doc.text") }
        }
        .tint(Color(red: 0/255, green: 137/255, blue: 123/255)) // teal
        .alert("初期化の確認", isPresented: $showResetAlert) {
            Button("初期化する", role: .destructive) { store.clearAll() }
            Button("やめる", role: .cancel) {}
        } message: {
            Text("ID・距離・結果・作図のすべての記録を消去し、最初からやり直します。よろしいですか？")
        }
    }
}

/// 各タブ共通の NavigationStack ＋ 右上「初期化」ボタン。
struct NavWrap<Content: View>: View {
    let title: String
    @Binding var showReset: Bool
    @ViewBuilder var content: () -> Content

    var body: some View {
        NavigationStack {
            content()
                .navigationTitle(title)
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .navigationBarTrailing) {
                        Button("初期化") { showReset = true }
                    }
                }
        }
    }
}
