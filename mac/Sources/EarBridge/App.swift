import SwiftUI

let brand = LinearGradient(
    colors: [Color(red: 1, green: 0.30, blue: 0.62), Color(red: 0.61, green: 0.36, blue: 1), Color(red: 0.24, green: 0.66, blue: 1)],
    startPoint: .leading, endPoint: .trailing
)
let bg = Color(red: 0.06, green: 0.06, blue: 0.09)
let card = Color(red: 0.094, green: 0.094, blue: 0.14)
let dim = Color(red: 0.56, green: 0.56, blue: 0.63)

func pre(_ size: CGFloat, bold: Bool = false) -> Font {
    .custom(bold ? "Pretendard-Bold" : "Pretendard-Regular", size: size)
}

@main
struct EarBridgeApp: App {
    @StateObject private var bridge = Bridge()

    var body: some Scene {
        WindowGroup("EarBridge") {
            ContentView()
                .environmentObject(bridge)
                .onAppear { bridge.start() }
                .preferredColorScheme(.dark)
        }
        .windowResizability(.contentSize)
    }
}

struct ContentView: View {
    @EnvironmentObject var bridge: Bridge
    @State private var address = ""
    @State private var codeInput = ""

    var body: some View {
        VStack(alignment: .leading, spacing: 18) {
            Text("EarBridge")
                .font(pre(30, bold: true))
                .foregroundStyle(brand)
            Text("폰 마이크 소리를 이 맥에서 듣습니다. 같은 와이파이면 자동으로 붙어요.")
                .font(pre(14)).foregroundStyle(dim)

            statusCard

            VStack(alignment: .leading, spacing: 8) {
                HStack {
                    Image(systemName: "speaker.wave.2.fill").foregroundStyle(dim)
                    Text("볼륨").font(pre(13)).foregroundStyle(dim)
                    Spacer()
                    Text("\(Int(bridge.volume * 100))%").font(pre(13, bold: true)).monospacedDigit()
                }
                Slider(value: $bridge.volume, in: 0...4)
                    .tint(Color(red: 0.61, green: 0.36, blue: 1))
            }

            VStack(alignment: .leading, spacing: 8) {
                Text("찾은 폰").font(pre(13)).foregroundStyle(dim)
                if bridge.phones.isEmpty {
                    Text("폰에서 EarBridge를 열고 시작을 누르세요.")
                        .font(pre(14)).foregroundStyle(dim)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(12).background(card).clipShape(RoundedRectangle(cornerRadius: 12))
                }
                ForEach(bridge.phones) { p in
                    Button { bridge.connect(p) } label: {
                        HStack {
                            Image(systemName: "iphone.gen3")
                            Text(p.name).font(pre(14, bold: true))
                            Spacer()
                            if isCurrent(p) {
                                Text("연결됨").font(pre(12, bold: true)).foregroundStyle(brand)
                            }
                        }
                        .padding(12)
                        .background(card)
                        .clipShape(RoundedRectangle(cornerRadius: 12))
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                }
            }

            VStack(alignment: .leading, spacing: 8) {
                Text("다른 네트워크 (폰에 뜬 코드)").font(pre(13)).foregroundStyle(dim)
                HStack {
                    TextField("ABCD2345", text: $codeInput)
                        .textFieldStyle(.roundedBorder)
                        .font(pre(14))
                        .onSubmit { bridge.connect(code: codeInput) }
                    Button("듣기") { bridge.connect(code: codeInput) }
                }
            }
            .onAppear { codeInput = bridge.code }

            VStack(alignment: .leading, spacing: 8) {
                Text("직접 주소 입력").font(pre(13)).foregroundStyle(dim)
                HStack {
                    TextField("192.168.0.10", text: $address)
                        .textFieldStyle(.roundedBorder)
                        .font(pre(14))
                        .onSubmit { bridge.connect(address: address) }
                    Button("연결") { bridge.connect(address: address) }
                }
            }
        }
        .padding(24)
        .frame(width: 380)
        .background(bg)
    }

    private func isCurrent(_ p: Phone) -> Bool {
        if case .listening(let n) = bridge.status { return n == p.name }
        return false
    }

    private var statusCard: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(spacing: 10) {
                Circle().fill(dotColor).frame(width: 10, height: 10)
                if bridge.isRemote {
                    Image(systemName: "globe").foregroundStyle(dim)
                }
                Text(statusText).font(pre(15, bold: true)).lineLimit(2)
                Spacer()
                if isActive {
                    Button("끊기") { bridge.disconnect() }
                }
            }
            LevelBar(rms: bridge.level)
            HStack {
                Text("지연 버퍼").font(pre(12)).foregroundStyle(dim)
                Spacer()
                Text(isListening ? "\(bridge.bufferMs) ms" : "-").font(pre(12, bold: true)).monospacedDigit()
            }
        }
        .padding(16)
        .background(card)
        .clipShape(RoundedRectangle(cornerRadius: 16))
    }

    private var isListening: Bool {
        if case .listening = bridge.status { return true }
        return false
    }

    private var isActive: Bool {
        switch bridge.status {
        case .listening, .connecting: return true
        default: return false
        }
    }

    private var statusText: String {
        switch bridge.status {
        case .idle: return "대기 중"
        case .connecting(let n): return n
        case .listening(let n): return "\(n) 듣는 중"
        case .failed(let m): return m
        }
    }

    private var dotColor: Color {
        switch bridge.status {
        case .listening: return Color(red: 0.24, green: 0.9, blue: 0.6)
        case .connecting: return Color(red: 1, green: 0.75, blue: 0.3)
        case .failed: return Color(red: 1, green: 0.42, blue: 0.55)
        case .idle: return dim
        }
    }
}

struct LevelBar: View {
    let rms: Float

    var body: some View {
        let db = rms <= 0 ? -80 : max(-80, min(0, 20 * log10(rms)))
        let v = CGFloat((db + 80) / 80)
        GeometryReader { g in
            ZStack(alignment: .leading) {
                Capsule().fill(Color.white.opacity(0.06))
                Capsule().fill(brand).frame(width: g.size.width * v)
                    .animation(.linear(duration: 0.08), value: v)
            }
        }
        .frame(height: 10)
    }
}
