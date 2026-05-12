import AppKit

final class NuvioPlayerPrewarmer {
    static let shared = NuvioPlayerPrewarmer()

    private var started = false

    private init() {
    }

    func prewarm() {
        DispatchQueue.main.async {
            guard !self.started else { return }
            self.started = true
            let view = NuvioMPVView(frame: NSRect(x: 0, y: 0, width: 16, height: 16))
            view.setup()
            view.destroyPlayer()
        }
    }
}
