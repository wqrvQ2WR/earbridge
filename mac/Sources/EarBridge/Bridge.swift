import Foundation
import Network

struct Phone: Identifiable, Hashable {
    let id: String
    let name: String
    let endpoint: NWEndpoint
}

let relayURL = "wss://pi.taild4dc8e.ts.net/earbridge"

/// 폰 찾기(Bonjour) + 접속(같은 와이파이는 TCP 직결, 다른 네트워크는 파이 중계 WebSocket) + 소리 받기.
@MainActor
final class Bridge: ObservableObject {
    enum Status: Equatable {
        case idle
        case connecting(String)
        case listening(String)
        case failed(String)
    }

    private enum Target {
        case lan(name: String, endpoint: NWEndpoint)
        case remote(code: String)

        var label: String {
            switch self {
            case .lan(let n, _): return n
            case .remote(let c): return "원격 \(c)"
            }
        }
    }

    @Published var phones: [Phone] = []
    @Published var status: Status = .idle
    @Published var level: Float = 0
    @Published var bufferMs: Int = 0
    @Published var volume: Double = UserDefaults.standard.object(forKey: "volume") as? Double ?? 1 {
        didSet {
            player.buffer.gain = Float(volume)
            UserDefaults.standard.set(volume, forKey: "volume")
        }
    }
    @Published var autoLevel: Bool = UserDefaults.standard.object(forKey: "autoLevel") as? Bool ?? true {
        didSet {
            player.buffer.autoLevel = autoLevel
            UserDefaults.standard.set(autoLevel, forKey: "autoLevel")
        }
    }
    @Published var code: String = UserDefaults.standard.string(forKey: "code") ?? ""
    @Published private(set) var isRemote = false

    private nonisolated(unsafe) let player = Player()
    private var browser: NWBrowser?
    private var conn: NWConnection?
    private var ws: URLSessionWebSocketTask?
    private var target: Target?
    private var userStopped = false
    private nonisolated let queue = DispatchQueue(label: "earbridge.net")

    // 연결이 바뀔 때마다 올라가는 번호. 예전 연결에서 늦게 온 데이터는 버린다.
    private nonisolated(unsafe) var gen = 0
    // 네트워크 큐에서만 쓰는 값
    private nonisolated(unsafe) var headerDone = false
    private nonisolated(unsafe) var pending = Data()
    private nonisolated(unsafe) var peak: Float = 0
    private nonisolated(unsafe) var lastMeter = Date()

    func start() {
        // 테스트용: --remote-only 로 켜면 같은 와이파이 찾기를 끄고 중계로만 듣는다
        let remoteOnly = CommandLine.arguments.contains("--remote-only")
        if !remoteOnly { startBrowser() }
        if !code.isEmpty { open(.remote(code: code)) }
    }

    private func startBrowser() {
        let b = NWBrowser(for: .bonjour(type: "_earbridge._tcp", domain: nil), using: .tcp)
        b.browseResultsChangedHandler = { [weak self] results, _ in
            Task { @MainActor in self?.updatePhones(results) }
        }
        b.start(queue: queue)
        browser = b
    }

    private func updatePhones(_ results: Set<NWBrowser.Result>) {
        phones = results.compactMap { r in
            guard case let .service(name, _, _, _) = r.endpoint else { return nil }
            return Phone(id: name, name: name, endpoint: r.endpoint)
        }.sorted { $0.name < $1.name }
        // 같은 와이파이에 폰이 있으면 직결이 더 빠르니까 그쪽으로 붙는다
        guard !userStopped, let p = phones.first else { return }
        switch status {
        case .idle, .failed: connect(p)
        case .connecting where isRemote: connect(p) // 원격에서 폰을 기다리는 중이면 직결로
        default: break
        }
    }

    func connect(_ p: Phone) {
        UserDefaults.standard.set(false, forKey: "lastRemote")
        open(.lan(name: p.name, endpoint: p.endpoint))
    }

    func connect(address: String) {
        let a = address.trimmingCharacters(in: .whitespaces)
        guard !a.isEmpty else { return }
        let parts = a.split(separator: ":")
        let host = String(parts[0])
        let port = parts.count > 1 ? UInt16(parts[1]) ?? 7700 : 7700
        UserDefaults.standard.set(false, forKey: "lastRemote")
        open(.lan(name: host, endpoint: .hostPort(host: .init(host), port: .init(rawValue: port)!)))
    }

    func connect(code raw: String) {
        let c = raw.uppercased().filter { $0.isLetter || $0.isNumber }
        guard c.count >= 6 else { return }
        code = c
        UserDefaults.standard.set(c, forKey: "code")
        UserDefaults.standard.set(true, forKey: "lastRemote")
        open(.remote(code: c))
    }

    func disconnect() {
        dlog("disconnect")
        userStopped = true
        UserDefaults.standard.set(false, forKey: "lastRemote")
        closeConnection()
        status = .idle
    }

    private func open(_ t: Target) {
        dlog("open \(t.label)")
        closeConnection()
        userStopped = false
        target = t
        let g = queue.sync { () -> Int in
            gen += 1
            headerDone = false
            pending = Data()
            return gen
        }

        switch t {
        case .lan(let name, let endpoint):
            isRemote = false
            status = .connecting("\(name) 연결 중...")
            let tcp = NWProtocolTCP.Options()
            tcp.noDelay = true
            tcp.connectionTimeout = 5
            let c = NWConnection(to: endpoint, using: NWParameters(tls: nil, tcp: tcp))
            conn = c
            c.stateUpdateHandler = { [weak self] st in
                switch st {
                case .failed(let e): Task { @MainActor in self?.lost("연결 실패: \(e.localizedDescription)", g) }
                case .waiting(let e): Task { @MainActor in self?.lost("연결 안 됨: \(e.localizedDescription)", g) }
                default: break
                }
            }
            c.start(queue: queue)
            receiveTCP(c, g)
            // Bonjour 주소 풀이에서 멈추면 상태가 안 바뀌어서, 5초 안에 소리가 안 오면 실패로 본다
            DispatchQueue.main.asyncAfter(deadline: .now() + 5) { [weak self] in
                guard let self, case .connecting = self.status, !self.isRemote else { return }
                self.lost("와이파이로 직접 연결 안 됨", g)
            }

        case .remote(let code):
            isRemote = true
            status = .connecting("폰 기다리는 중 (코드 \(code))")
            let task = URLSession.shared.webSocketTask(with: URL(string: "\(relayURL)/listen/\(code)")!)
            task.maximumMessageSize = 1 << 20
            ws = task
            task.resume()
            receiveWS(task, g)
        }
    }

    private func lost(_ msg: String, _ g: Int) {
        guard g == queue.sync(execute: { gen }) else { return }
        dlog("lost \(target?.label ?? "-"): \(msg)")
        closeConnection()
        status = .failed(msg)
        guard !userStopped else { return }
        // 와이파이 직결이 안 되면(폰이 다른 네트워크) 코드로 원격에 붙는다
        if case .lan = target, !code.isEmpty {
            open(.remote(code: code))
            return
        }
        // 잠깐 끊겨도 다시 붙도록
        if let t = target {
            DispatchQueue.main.asyncAfter(deadline: .now() + 2) { [weak self] in
                guard let self, !self.userStopped, case .failed = self.status else { return }
                self.open(t)
            }
        }
    }

    private func closeConnection() {
        queue.sync {
            gen += 1
            player.stop()
        }
        conn?.stateUpdateHandler = nil
        conn?.cancel()
        conn = nil
        ws?.cancel(with: .goingAway, reason: nil)
        ws = nil
        level = 0
        bufferMs = 0
    }

    private nonisolated func receiveTCP(_ c: NWConnection, _ g: Int) {
        c.receive(minimumIncompleteLength: 1, maximumLength: 16384) { [weak self] data, _, done, err in
            guard let self else { return }
            if let data, !data.isEmpty { self.consume(data, g) }
            if done || err != nil {
                Task { @MainActor in self.lost(err.map { "끊김: \($0.localizedDescription)" } ?? "폰에서 연결을 닫았어요", g) }
                return
            }
            self.receiveTCP(c, g)
        }
    }

    private nonisolated func receiveWS(_ t: URLSessionWebSocketTask, _ g: Int) {
        t.receive { [weak self] result in
            guard let self else { return }
            switch result {
            case .success(.data(let d)):
                self.queue.async { self.consume(d, g) }
                self.receiveWS(t, g)
            case .success(.string(let text)):
                self.queue.async { self.control(text, g) }
                self.receiveWS(t, g)
            case .success:
                self.receiveWS(t, g)
            case .failure(let e):
                Task { @MainActor in self.lost("중계 서버 끊김: \(e.localizedDescription)", g) }
            }
        }
    }

    /// 중계 서버 알림 (네트워크 큐). start: 새 헤더가 곧 옴, gone: 폰이 끊김
    private nonisolated func control(_ text: String, _ g: Int) {
        guard g == gen else { return }
        headerDone = false
        pending = Data()
        player.stop()
        let code = text == "gone" ? "폰 기다리는 중" : nil
        Task { @MainActor in
            guard g == self.queue.sync(execute: { self.gen }), let code else { return }
            dlog("phone gone")
            if case .remote(let c) = self.target { self.status = .connecting("\(code) (코드 \(c))") }
            self.level = 0
            self.bufferMs = 0
        }
    }

    /// 네트워크 큐에서만 불린다.
    private nonisolated func consume(_ data: Data, _ g: Int) {
        guard g == gen else { return }
        pending.append(data)
        if !headerDone {
            guard pending.count >= 9 else { return }
            let h = [UInt8](pending.prefix(9))
            guard h[0] == 0x45, h[1] == 0x42, h[2] == 0x52, h[3] == 0x31 else {
                Task { @MainActor in self.lost("EarBridge 폰이 아니에요", g) }
                return
            }
            let rate = Int(h[4]) | Int(h[5]) << 8 | Int(h[6]) << 16 | Int(h[7]) << 24
            pending.removeFirst(9)
            headerDone = true
            // 플레이어는 이 큐에서만 켜고 끈다 (메인 스레드를 기다리지 않게)
            do {
                try player.start(sampleRate: rate)
            } catch {
                Task { @MainActor in self.status = .failed("소리 장치를 열 수 없음: \(error.localizedDescription)") }
                return
            }
            let jb = player.buffer
            Task { @MainActor in
                guard g == self.queue.sync(execute: { self.gen }) else { return }
                jb.gain = Float(self.volume)
                jb.autoLevel = self.autoLevel
                if self.isRemote { jb.loosen() } // 인터넷은 흔들림이 커서 버퍼를 넉넉히
                self.status = .listening(self.target?.label ?? "폰")
                dlog("listening \(self.target?.label ?? "-") \(rate)Hz")
            }
        }

        let usable = pending.count & ~1
        guard usable > 0 else { return }
        let jb = player.buffer
        var sum: Float = 0
        var samples = [Int16](repeating: 0, count: usable / 2) // 맥도 리틀엔디언이라 그대로 복사
        _ = samples.withUnsafeMutableBytes { pending.copyBytes(to: $0, from: pending.startIndex..<pending.startIndex + usable) }
        samples.withUnsafeBufferPointer { s in
            jb.write(s)
            for v in s { let f = Float(v) / 32768; sum += f * f }
        }
        peak = max(peak, (sum / Float(max(samples.count, 1))).squareRoot())
        pending.removeFirst(usable)

        let now = Date()
        if now.timeIntervalSince(lastMeter) > 0.05 {
            let p = peak
            let ms = jb.bufferedSamples * 1000 / max(player.sampleRate, 1)
            peak = 0
            lastMeter = now
            Task { @MainActor in
                guard g == self.queue.sync(execute: { self.gen }) else { return }
                self.level = p
                self.bufferMs = ms
            }
        }
    }
}

/// 연결 흐름 기록: ~/Library/Logs/EarBridge.log (최근 것만 보면 됨)
func dlog(_ s: String) {
    let url = FileManager.default.homeDirectoryForCurrentUser.appendingPathComponent("Library/Logs/EarBridge.log")
    let line = "\(Date()) \(s)\n"
    if let h = try? FileHandle(forWritingTo: url) {
        h.seekToEndOfFile()
        h.write(line.data(using: .utf8)!)
        try? h.close()
    } else {
        try? line.write(to: url, atomically: true, encoding: .utf8)
    }
}
