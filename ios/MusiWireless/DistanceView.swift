import SwiftUI
import ARKit
import simd

/// ARKit のセッションを持ち、カメラ位置をリアルタイムに公開するモデル。
/// Android版（ARCore のカメラ姿勢の移動で距離を測る）と同じ考え方。
final class ARDistanceModel: NSObject, ObservableObject, ARSessionDelegate {
    let session = ARSession()
    @Published var tracking = false
    @Published var currentPos: SIMD3<Float>? = nil

    override init() {
        super.init()
        session.delegate = self
    }

    func start() {
        guard ARWorldTrackingConfiguration.isSupported else { return }
        let config = ARWorldTrackingConfiguration()
        session.run(config, options: [.resetTracking, .removeExistingAnchors])
    }
    func pause() { session.pause() }

    func session(_ session: ARSession, didUpdate frame: ARFrame) {
        let t = frame.camera.transform.columns.3
        currentPos = SIMD3<Float>(t.x, t.y, t.z)
        if case .normal = frame.camera.trackingState { tracking = true } else { tracking = false }
    }
}

struct ARContainer: UIViewRepresentable {
    let model: ARDistanceModel
    func makeUIView(context: Context) -> ARSCNView {
        let v = ARSCNView()
        v.session = model.session
        model.start()
        return v
    }
    func updateUIView(_ uiView: ARSCNView, context: Context) {}
}

struct DistanceView: View {
    @EnvironmentObject var store: AppStore
    @StateObject private var ar = ARDistanceModel()

    @State private var memo = ""
    @State private var measuring = false
    @State private var startPos: SIMD3<Float>? = nil
    @State private var showList = false
    @State private var editIndex: Int? = nil

    private let teal = Color(red: 0/255, green: 137/255, blue: 123/255)

    private var liveMeters: Double {
        guard let s = startPos, let c = ar.currentPos else { return 0 }
        return Double(simd_distance(s, c))
    }

    var body: some View {
        Group {
            if ARWorldTrackingConfiguration.isSupported {
                content
            } else {
                Text("この端末はARに対応していません。").padding()
            }
        }
        .onDisappear { ar.pause() }
    }

    private var content: some View {
        ZStack(alignment: .bottom) {
            ARContainer(model: ar).ignoresSafeArea(edges: .bottom)

            // 中央レチクル
            Circle().stroke(Color.white, lineWidth: 3).frame(width: 22, height: 22)
                .frame(maxWidth: .infinity, maxHeight: .infinity)

            // 計測中の距離
            if measuring {
                VStack {
                    Text(store.unit.format(liveMeters))
                        .font(.system(size: 30, weight: .bold, design: .monospaced))
                        .foregroundColor(Color(red: 0, green: 0.41, blue: 0.36))
                        .padding(.horizontal, 16).padding(.vertical, 6)
                        .background(.white.opacity(0.92)).cornerRadius(10)
                        .padding(.top, 8)
                    Spacer()
                }
            }

            controls
        }
    }

    private var controls: some View {
        VStack(spacing: 8) {
            Text(ar.tracking ? "メモ→計測→移動→終点確定" : "周囲を映してゆっくり動かしてください…")
                .font(.caption).bold()
                .foregroundColor(ar.tracking ? Color(red: 0, green: 0.41, blue: 0.36) : .orange)
                .padding(6).frame(maxWidth: .infinity)
                .background(.white.opacity(0.92)).cornerRadius(8)

            HStack {
                Text("単位").font(.caption).bold()
                Picker("", selection: $store.unit) {
                    ForEach(DistUnit.allCases) { Text($0.label).tag($0) }
                }.pickerStyle(.segmented).frame(width: 120)
                Spacer()
            }
            .padding(6).background(.white.opacity(0.92)).cornerRadius(8)

            TextField("メモ（例：入口→レジ）", text: $memo)
                .textFieldStyle(.roundedBorder).disabled(measuring)

            Button(action: measureTapped) {
                Text(measuring ? "終点を確定して記録" : "計測（始点をセット）")
                    .bold().frame(maxWidth: .infinity).padding(10)
                    .background(measuring ? Color.red : teal).foregroundColor(.white).cornerRadius(8)
            }

            if measuring {
                Button("やり直し") { reset() }.foregroundColor(.orange)
            }

            if !store.records.isEmpty {
                Button(action: { showList.toggle() }) {
                    Text("記録一覧 (\(store.records.count)) \(showList ? "▲" : "▼")").bold()
                }
                if showList { recordList }
            }
        }
        .padding(12)
    }

    private var recordList: some View {
        VStack(spacing: 4) {
            ForEach(Array(store.records.enumerated()), id: \.element.id) { i, rec in
                HStack {
                    Text("\(i + 1). \(rec.memo)").font(.footnote)
                    Spacer()
                    Text(rec.display(store.unit)).font(.footnote).bold().foregroundColor(teal)
                    Button("修正") { editIndex = i }.font(.caption)
                    Button {
                        store.records.remove(at: i)
                    } label: { Image(systemName: "xmark").foregroundColor(.red) }
                }
            }
            HStack {
                Button("コピー") { UIPasteboard.general.string = report() }
                Spacer()
                Button("全消去", role: .destructive) { store.records = [] }
            }.padding(.top, 4)
        }
        .padding(8).background(.white.opacity(0.92)).cornerRadius(8)
        .sheet(item: Binding(get: { editIndex.map { IdxWrap(id: $0) } }, set: { editIndex = $0?.id })) { w in
            EditRecordSheet(record: store.records[w.id], unit: store.unit) { name, meters in
                store.records[w.id].memo = name
                store.records[w.id].meters = meters
                editIndex = nil
            } onCancel: { editIndex = nil }
        }
    }

    private func measureTapped() {
        if !measuring {
            guard ar.tracking, let c = ar.currentPos else { return }
            startPos = c
            measuring = true
        } else {
            let name = memo.isEmpty ? "計測\(store.records.count + 1)" : memo
            store.records.insert(DistanceRecord(memo: name, meters: liveMeters), at: 0)
            memo = ""
            reset()
        }
    }

    private func reset() { measuring = false; startPos = nil }

    private func report() -> String {
        var s = "測定結果\n"
        for (i, r) in store.records.reversed().enumerated() {
            s += "\(i + 1). \(r.memo): \(r.display(store.unit))\n"
        }
        return s
    }
}

private struct IdxWrap: Identifiable { let id: Int }

/// 記録の修正（名称＋距離の数値）。
struct EditRecordSheet: View {
    let record: DistanceRecord
    let unit: DistUnit
    var onSave: (String, Double) -> Void
    var onCancel: () -> Void

    @State private var name: String
    @State private var value: String

    init(record: DistanceRecord, unit: DistUnit,
         onSave: @escaping (String, Double) -> Void, onCancel: @escaping () -> Void) {
        self.record = record; self.unit = unit; self.onSave = onSave; self.onCancel = onCancel
        _name = State(initialValue: record.memo)
        _value = State(initialValue: unit == .cm ? String(format: "%.0f", record.meters * 100)
                                                  : String(format: "%.2f", record.meters))
    }

    var body: some View {
        NavigationStack {
            Form {
                Section("名称") { TextField("名称（例：入口→レジ）", text: $name) }
                Section("距離（\(unit.label)）") {
                    TextField(unit == .cm ? "例：235" : "例：2.35", text: $value)
                        .keyboardType(.decimalPad)
                }
            }
            .navigationTitle("記録の修正")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("取消") { onCancel() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("保存") {
                        guard !name.isEmpty, let v = Double(value), v >= 0 else { return }
                        onSave(name, unit == .cm ? v / 100 : v)
                    }
                }
            }
        }
    }
}
