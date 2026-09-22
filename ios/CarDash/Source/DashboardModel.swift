import Foundation
import Combine

/// 仪表盘数据中枢：合并「车机桥接推送」与「本机传感器」两路数据。
/// 车机数据优先，取不到时自动退回本机 GPS。
final class DashboardModel: ObservableObject {

    // MARK: - 车机数据
    @Published private(set) var car: CarSnapshot?
    @Published private(set) var link: LinkStatus = .idle

    // MARK: - 本机传感器
    @Published private(set) var localSpeed: Double?
    @Published private(set) var localAltitude: Double?
    @Published private(set) var localOdometer: Double = 0
    @Published private(set) var locationDenied = false

    // MARK: - 设置
    @Published var host: String {
        didSet { UserDefaults.standard.set(host, forKey: Self.hostKey) }
    }

    static let defaultPort = 8765
    private static let hostKey = "carHost"

    private let sensors = LocalSensors()
    private let session: URLSession
    private var timer: Timer?
    private var inFlight = false
    private var lastSuccess = Date.distantPast
    private var started = false

    init() {
        host = UserDefaults.standard.string(forKey: Self.hostKey) ?? ""
        let cfg = URLSessionConfiguration.ephemeral
        cfg.timeoutIntervalForRequest = 2
        cfg.timeoutIntervalForResource = 3
        cfg.requestCachePolicy = .reloadIgnoringLocalAndRemoteCacheData
        cfg.waitsForConnectivity = false
        session = URLSession(configuration: cfg)
    }

    // MARK: - 生命周期

    func start() {
        guard !started else { return }
        started = true

        sensors.onUpdate = { [weak self] speed, altitude, odo in
            guard let self else { return }
            self.localSpeed = speed
            self.localAltitude = altitude
            self.localOdometer = odo
        }
        sensors.onDenied = { [weak self] in
            self?.locationDenied = true
        }
        sensors.start()

        let t = Timer(timeInterval: 0.25, repeats: true) { [weak self] _ in
            self?.poll()
        }
        RunLoop.main.add(t, forMode: .common)
        timer = t
        poll()
    }

    func stop() {
        timer?.invalidate()
        timer = nil
    }

    /// 允许外部（设置页）立即重连
    func reconnectNow() {
        lastSuccess = .distantPast
        poll()
    }

    // MARK: - 网络

    private var normalizedURL: URL? {
        var h = host.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !h.isEmpty else { return nil }
        h = h.replacingOccurrences(of: "http://", with: "")
        h = h.replacingOccurrences(of: "https://", with: "")
        if !h.contains(":") {
            h += ":\(Self.defaultPort)"
        }
        return URL(string: "http://\(h)/state")
    }

    private func poll() {
        guard let url = normalizedURL else {
            link = .idle
            return
        }
        guard !inFlight else { return }
        inFlight = true

        session.dataTask(with: url) { [weak self] data, response, error in
            DispatchQueue.main.async {
                guard let self else { return }
                self.inFlight = false

                if let data,
                   let snap = try? JSONDecoder().decode(CarSnapshot.self, from: data) {
                    self.car = snap
                    self.lastSuccess = Date()
                    self.link = .online
                    return
                }

                if (response as? HTTPURLResponse) != nil {
                    self.link = .failed("车机返回异常")
                } else if error != nil {
                    self.link = .failed("连接车机失败")
                } else {
                    self.link = .failed("数据格式错误")
                }
            }
        }.resume()
    }

    // MARK: - 对外展示值

    private var carFresh: Bool {
        Date().timeIntervalSince(lastSuccess) < 3.0
    }

    var isCarOnline: Bool { carFresh }

    var displaySpeed: Double? {
        if let s = car?.speed, carFresh { return s }
        return localSpeed
    }

    var displayAltitude: Double? {
        if let a = car?.altitude, carFresh { return a }
        return localAltitude
    }

    var displayOdometer: Double? {
        if let o = car?.odometer, o > 0, carFresh { return o }
        return localOdometer > 0.3 ? localOdometer : (car?.odometer)
    }

    var displayGear: String? {
        carFresh ? car?.gear : nil
    }

    var displaySoc: Double? {
        carFresh ? car?.soc : nil
    }

    var displayRange: Double? {
        carFresh ? car?.range : nil
    }

    var displayMusic: MusicState? {
        guard carFresh, let m = car?.music, !m.isEmpty else { return nil }
        return m
    }

    var displayNav: NavState? {
        carFresh ? car?.nav : nil
    }
}
