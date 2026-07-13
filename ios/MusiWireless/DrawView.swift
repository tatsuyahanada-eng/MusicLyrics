import SwiftUI

private enum DTool: String, CaseIterable { case pen = "ペン", item = "アイテム", delete = "削除" }

private struct Stroke: Identifiable { let id = UUID(); var pts: [CGPoint]; var color: Color }
private struct Mark: Identifiable { let id = UUID(); var pos: CGPoint; var type: String; var num: Int; var color: Color }

private let deviceTypes = ["RST", "EST", "RPR", "EPR", "RMD", "EMD", "PC", "Router", "HUB"]
private let palette: [Color] = [.black, .red, .blue, .green, .orange, .purple]

struct DrawView: View {
    @State private var strokes: [Stroke] = []
    @State private var marks: [Mark] = []
    @State private var live: [CGPoint] = []
    @State private var ops: [String] = []          // "S" or "M" 履歴（取消用）

    @State private var tool: DTool = .item
    @State private var color: Color = .black
    @State private var selType = "RST"
    @State private var selNum = 1
    @State private var shareURL: URL? = nil

    var body: some View {
        VStack(spacing: 6) {
            Picker("", selection: $tool) {
                ForEach(DTool.allCases, id: \.self) { Text($0.rawValue).tag($0) }
            }.pickerStyle(.segmented)

            subSettings

            GeometryReader { geo in
                ZStack {
                    Color.white
                    canvas(size: geo.size)
                }
                .clipShape(RoundedRectangle(cornerRadius: 4))
                .overlay(RoundedRectangle(cornerRadius: 4).stroke(Color(white: 0.74)))
                .gesture(drawGesture(size: geo.size))
            }

            HStack {
                Button("取消") { undo() }.disabled(ops.isEmpty)
                Spacer()
                Button("全消去", role: .destructive) { strokes = []; marks = []; ops = [] }
                Spacer()
                Button("画像を共有") { exportImage() }
            }
        }
        .padding(8)
        .sheet(item: Binding(get: { shareURL.map { ShareItem(url: $0) } }, set: { shareURL = $0?.url })) { item in
            ActivityView(items: [item.url])
        }
    }

    @ViewBuilder private var subSettings: some View {
        switch tool {
        case .pen:
            colorRow
        case .item:
            ScrollView(.horizontal, showsIndicators: false) {
                HStack {
                    ForEach(deviceTypes, id: \.self) { t in
                        chip(t, selected: selType == t) { selType = t }
                    }
                }
            }
            ScrollView(.horizontal, showsIndicators: false) {
                HStack {
                    ForEach(1...10, id: \.self) { n in
                        chip("\(n)", selected: selNum == n) { selNum = n }
                    }
                }
            }
            colorRow
        case .delete:
            Text("消したいアイテム/線をタップ").font(.caption).foregroundColor(.secondary)
        }
    }

    private var colorRow: some View {
        HStack {
            ForEach(palette, id: \.self) { c in
                Circle().fill(c).frame(width: 26, height: 26)
                    .overlay(Circle().stroke(color == c ? Color.black : Color.gray, lineWidth: color == c ? 3 : 1))
                    .onTapGesture { color = c }
            }
            Spacer()
        }
    }

    private func chip(_ t: String, selected: Bool, _ action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(t).font(.footnote)
                .padding(.horizontal, 10).padding(.vertical, 4)
                .background(selected ? Color(red: 0.8, green: 0.94, blue: 0.91) : Color(white: 0.95))
                .foregroundColor(selected ? Color(red: 0, green: 0.41, blue: 0.36) : .primary)
                .clipShape(Capsule())
        }
    }

    private func canvas(size: CGSize) -> some View {
        Canvas { ctx, _ in
            for s in strokes { draw(ctx: ctx, stroke: s) }
            if live.count >= 2 { draw(ctx: ctx, stroke: Stroke(pts: live, color: color)) }
            for m in marks {
                let rect = CGRect(x: m.pos.x - 12, y: m.pos.y - 12, width: 24, height: 24)
                ctx.fill(Path(rect), with: .color(m.color))
                ctx.draw(Text("\(m.num)").font(.system(size: 12, weight: .bold)).foregroundColor(.white),
                         at: m.pos)
                ctx.draw(Text(m.type).font(.system(size: 10, weight: .bold)).foregroundColor(.black),
                         at: CGPoint(x: m.pos.x, y: m.pos.y + 20))
            }
        }
    }

    private func draw(ctx: GraphicsContext, stroke: Stroke) {
        var p = Path()
        p.addLines(stroke.pts)
        ctx.stroke(p, with: .color(stroke.color), lineWidth: 3)
    }

    private func drawGesture(size: CGSize) -> some Gesture {
        DragGesture(minimumDistance: 0)
            .onChanged { v in
                if tool == .pen { live.append(v.location) }
            }
            .onEnded { v in
                switch tool {
                case .pen:
                    if live.count >= 2 { strokes.append(Stroke(pts: live, color: color)); ops.append("S") }
                    live = []
                case .item:
                    marks.append(Mark(pos: v.location, type: selType, num: selNum, color: color))
                    ops.append("M")
                case .delete:
                    deleteNearest(v.location)
                }
            }
    }

    private func deleteNearest(_ p: CGPoint) {
        if let i = marks.indices.min(by: { dist(marks[$0].pos, p) < dist(marks[$1].pos, p) }),
           dist(marks[i].pos, p) < 40 {
            marks.remove(at: i); return
        }
        if let i = strokes.indices.min(by: { dist(center($0), p) < dist(center($1), p) }),
           dist(center(i), p) < 60 {
            strokes.remove(at: i)
        }
    }
    private func center(_ i: Int) -> CGPoint {
        let pts = strokes[i].pts
        let x = pts.map { $0.x }.reduce(0, +) / CGFloat(pts.count)
        let y = pts.map { $0.y }.reduce(0, +) / CGFloat(pts.count)
        return CGPoint(x: x, y: y)
    }
    private func dist(_ a: CGPoint, _ b: CGPoint) -> CGFloat { hypot(a.x - b.x, a.y - b.y) }

    private func undo() {
        guard let last = ops.popLast() else { return }
        if last == "S", !strokes.isEmpty { strokes.removeLast() }
        if last == "M", !marks.isEmpty { marks.removeLast() }
    }

    @MainActor private func exportImage() {
        let size = CGSize(width: 1080, height: 1440)
        let renderer = ImageRenderer(content:
            ZStack { Color.white; canvas(size: size) }.frame(width: size.width, height: size.height)
        )
        renderer.scale = 1
        guard let ui = renderer.uiImage, let data = ui.pngData() else { return }
        let df = DateFormatter(); df.dateFormat = "yyyyMMdd_HHmm"
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("見取り図_\(df.string(from: Date())).png")
        try? data.write(to: url)
        shareURL = url
    }
}

private struct ShareItem: Identifiable { let id = UUID(); let url: URL }

/// UIActivityViewController のラッパー（共有シート）。
struct ActivityView: UIViewControllerRepresentable {
    let items: [Any]
    func makeUIViewController(context: Context) -> UIActivityViewController {
        UIActivityViewController(activityItems: items, applicationActivities: nil)
    }
    func updateUIViewController(_ vc: UIActivityViewController, context: Context) {}
}
