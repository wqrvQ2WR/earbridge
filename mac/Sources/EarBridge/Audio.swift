import AVFoundation
import Foundation

/// 네트워크에서 들어온 샘플을 잠깐 모아 두는 버퍼.
/// 와이파이는 패킷이 몰려 오기 때문에 prime 만큼 쌓인 뒤에 재생을 시작하고,
/// 너무 많이 밀리면(maxLag) 오래된 걸 버려서 지연이 늘어나지 않게 한다.
final class JitterBuffer {
    private var buf: [Float]
    private var r = 0
    private var w = 0
    private(set) var count = 0
    private var playing = false
    private let lock = NSLock()

    var prime: Int
    var maxLag: Int
    var gain: Float = 1

    init(sampleRate: Int) {
        buf = [Float](repeating: 0, count: sampleRate * 2)
        prime = sampleRate * 60 / 1000
        maxLag = sampleRate * 250 / 1000
    }

    var bufferedSamples: Int {
        lock.lock(); defer { lock.unlock() }
        return count
    }

    func write(_ s: UnsafeBufferPointer<Int16>) {
        lock.lock(); defer { lock.unlock() }
        let cap = buf.count
        for v in s {
            buf[w] = Float(v) / 32768
            w = (w + 1) % cap
            if count == cap { r = (r + 1) % cap } else { count += 1 }
        }
        if count > maxLag {
            let drop = count - prime
            r = (r + drop) % cap
            count -= drop
        }
    }

    func read(into out: UnsafeMutablePointer<Float>, frames: Int) {
        lock.lock(); defer { lock.unlock() }
        if !playing && count >= prime { playing = true }
        let cap = buf.count
        var i = 0
        if playing {
            let g = gain
            while i < frames && count > 0 {
                out[i] = max(-1, min(1, buf[r] * g))
                r = (r + 1) % cap
                count -= 1
                i += 1
            }
            if count == 0 { playing = false } // 비면 다시 모을 때까지 기다림
        }
        while i < frames { out[i] = 0; i += 1 }
    }

    /// 인터넷 중계용: 더 많이 모았다가 재생해서 끊김을 줄인다.
    func loosen() {
        lock.lock(); defer { lock.unlock() }
        let rate = buf.count / 2
        prime = rate * 150 / 1000
        maxLag = rate * 500 / 1000
    }

    func reset() {
        lock.lock(); defer { lock.unlock() }
        r = 0; w = 0; count = 0; playing = false
    }
}

final class Player {
    private let engine = AVAudioEngine()
    private var node: AVAudioSourceNode?
    private(set) var buffer: JitterBuffer
    private(set) var sampleRate: Int

    init(sampleRate: Int = 48000) {
        self.sampleRate = sampleRate
        buffer = JitterBuffer(sampleRate: sampleRate)
    }

    func start(sampleRate: Int) throws {
        stop()
        let oldGain = buffer.gain
        self.sampleRate = sampleRate
        buffer = JitterBuffer(sampleRate: sampleRate)
        buffer.gain = oldGain
        let jb = buffer
        let fmt = AVAudioFormat(standardFormatWithSampleRate: Double(sampleRate), channels: 1)!
        let n = AVAudioSourceNode(format: fmt) { _, _, frameCount, abl -> OSStatus in
            let list = UnsafeMutableAudioBufferListPointer(abl)
            guard let p = list[0].mData?.assumingMemoryBound(to: Float.self) else { return noErr }
            jb.read(into: p, frames: Int(frameCount))
            return noErr
        }
        node = n
        engine.attach(n)
        engine.connect(n, to: engine.mainMixerNode, format: fmt)
        try engine.start()
    }

    func stop() {
        if engine.isRunning { engine.stop() }
        if let n = node { engine.detach(n) }
        node = nil
        buffer.reset()
    }
}
