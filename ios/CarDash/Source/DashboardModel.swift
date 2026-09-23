import Foundation
import Darwin
import CoreLocation

/// 仪表盘数据中枢：合并「车机桥接推送」与「本机传感器」两路数据。
/// 车机数据优先，取不到时自动退回本机 GPS。
final class DashboardModel: ObservableObject {

    // MARK: - 车机数据
    @Published private(set) var car: CarSnapshot?
    @Published private(set) var link: LinkStatus = .idle
    @Published private(set) var carHost: String?

    // MARK: - 本机传感器
    @Published private(set) var localSpeed: Double?
    @Published private(set) var localAltitude: Double?
    @Published private(set) var localOdometer: Double = 0
    @Published private(set) var locationDenied = false

    // MARK: - 地图
    /// 车辆当前位置（本机 GPS）。地图还没定位到时为 nil。
    @Published private(set) var coord: CLLocationCoordinate2D?
    /// 车头方向（度）。地图「车头朝上」用。
    @Published private(set) var heading: Double = 0
    /// 已行驶轨迹。上限 900 个点，按 8 米一个点采样，
    /// 够画十几公里的轨迹，又不会让地图重绘变慢。
    @Published private(set) var track: [CLLocationCoordinate2D] = []

    // MARK: - 设置
    @Published var host: String {
        didSet {
            UserDefaults.standard.set(host, forKey: Self.hostKey)
            lastSuccess = .distantPast
        }
    }

    static let defaultPort = 8765
    private static let hostKey = "carHost"

    private let sensors = LocalSensors()
    private let session: URLSession
    private let scanSession: URLSession
    /// 诊断文本可能挺大（/log 有几十 KB），单独给个宽松点的超时
    private let diagSession: URLSession
    private var timer: Timer?
    private var watchdog: Timer?
    private var inFlight = false
    private var lastSuccess = Date.distantPast
    private var lastScan = Date.distantPast
    private var scanning = false
    private var started = false

    init() {
        host = UserDefaults.standard.string(forKey: Self.hostKey) ?? ""

        let cfg = URLSessionConfiguration.ephemeral
        cfg.timeoutIntervalForRequest = 2
        cfg.timeoutIntervalForResource = 3
        cfg.requestCachePolicy = .reloadIgnoringLocalAndRemoteCacheData
        cfg.waitsForConnectivity = false
        session = URLSession(configuration: cfg)

        let scanCfg = URLSessionConfiguration.ephemeral
        scanCfg.timeoutIntervalForRequest = 0.6
        scanCfg.timeoutIntervalForResource = 1.0
        scanCfg.requestCachePolicy = .reloadIgnoringLocalAndRemoteCacheData
        scanCfg.waitsForConnectivity = false
        scanSession = URLSession(configuration: scanCfg)

        let diagCfg = URLSessionConfiguration.ephemeral
        diagCfg.timeoutIntervalForRequest = 10
        diagCfg.timeoutIntervalForResource = 15
        diagCfg.requestCachePolicy = .reloadIgnoringLocalAndRemoteCacheData
        diagCfg.waitsForConnectivity = false
        diagSession = URLSession(configuration: diagCfg)
    }

    /// 拉取车机上的诊断文本（/diag、/logcat、/log）。
    /// 车机上没有浏览器，所以直接在 App 里读，省得在 Safari 里手打地址。
    func fetchText(path: String, completion: @escaping (String) -> Void) {
        var h = host.trimmingCharacters(in: .whitespacesAndNewlines)
        h = h.replacingOccurrences(of: "http://", with: "")
        h = h.replacingOccurrences(of: "https://", with: "")
        while h.hasSuffix("/") { h.removeLast() }
        guard !h.isEmpty else {
            completion("还没有车机地址。\n\n先在上面填地址，或点「自动搜索车机」。")
            return
        }
        if !h.contains(":") { h += ":\(Self.defaultPort)" }
        guard let url = URL(string: "http://\(h)\(path)") else {
            completion("地址格式不对: \(h)")
            return
        }
        diagSession.dataTask(with: url) { data, _, err in
            let text: String
            if let data, let s = String(data: data, encoding: .utf8) {
                text = s
            } else {
                text = "读取失败：\(err?.localizedDescription ?? "车机没有响应")\n\n"
                     + "地址: http://\(h)\(path)\n"
                     + "确认 iPhone 和车机在同一个 WiFi/热点下。"
            }
            DispatchQueue.main.async { completion(text) }
        }.resume()
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
        sensors.onFix = { [weak self] loc in
            guard let self else { return }
            self.coord = loc.coordinate
            // course 只有在移动时才有效（静止时是 -1），
            // 无效就保持上一次的车头方向，免得地图乱转
            if loc.course >= 0 { self.heading = loc.course }

            if let last = self.track.last {
                let d = CLLocation(latitude: last.latitude, longitude: last.longitude)
                    .distance(from: loc)
                if d < 8 { return }          // 太近的点不要，否则轨迹会糊成一团
            }
            self.track.append(loc.coordinate)
            if self.track.count > 900 {
                self.track.removeFirst(self.track.count - 900)
            }
        }
        sensors.start()

        let t = Timer(timeInterval: 0.25, repeats: true) { [weak self] _ in
            self?.poll()
        }
        RunLoop.main.add(t, forMode: .common)
        timer = t

        // 连不上就自动扫网段找车机
        let w = Timer(timeInterval: 5, repeats: true) { [weak self] _ in
            self?.watchdogTick()
        }
        RunLoop.main.add(w, forMode: .common)
        watchdog = w

        poll()
    }

    func stop() {
        timer?.invalidate(); timer = nil
        watchdog?.invalidate(); watchdog = nil
    }

    /// 设置页里的「立即重连」
    func reconnectNow() {
        lastSuccess = .distantPast
        lastScan = .distantPast
        poll()
    }

    /// 设置页里的「搜索车机」
    func searchNow() {
        scanSubnet()
    }

    private func watchdogTick() {
        guard Date().timeIntervalSince(lastSuccess) > 8 else { return }
        guard Date().timeIntervalSince(lastScan) > 45 else { return }
        scanSubnet()
    }

    // MARK: - 轮询

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
            link = scanning ? .scanning : .idle
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
                    self.carHost = self.host
                    return
                }

                if self.scanning { return }
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

    // MARK: - 自动搜索车机

    /// 本机所在的 /24 网段。只认 WiFi / 热点网桥，**不碰蜂窝网**，避免误扫运营商网段。
    private func localPrefixes() -> [String] {
        var result: [String] = []
        var addrs: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&addrs) == 0, let first = addrs else { return result }
        defer { freeifaddrs(addrs) }

        var ptr: UnsafeMutablePointer<ifaddrs>? = first
        while let p = ptr {
            let ifa = p.pointee
            defer { ptr = ifa.ifa_next }
            guard let sa = ifa.ifa_addr, sa.pointee.sa_family == UInt8(AF_INET) else { continue }

            let name = String(cString: ifa.ifa_name)
            // en0 = WiFi，bridge100 = 个人热点网桥；pdp_ip0 是蜂窝，必须排除
            guard name.hasPrefix("en") || name.hasPrefix("bridge") else { continue }

            var buf = [CChar](repeating: 0, count: Int(NI_MAXHOST))
            guard getnameinfo(sa, socklen_t(sa.pointee.sa_len), &buf,
                              socklen_t(buf.count), nil, 0, NI_NUMERICHOST) == 0 else { continue }
            let ip = String(cString: buf)
            guard !ip.hasPrefix("127."), !ip.hasPrefix("169.254.") else { continue }

            let parts = ip.split(separator: ".")
            guard parts.count == 4 else { continue }
            let prefix = "\(parts[0]).\(parts[1]).\(parts[2])"
            guard prefix.hasPrefix("192.168.") || prefix.hasPrefix("10.")
                || prefix.hasPrefix("172.") else { continue }
            if !result.contains(prefix) {
                result.append(prefix)
            }
        }
        return result
    }

    private func scanSubnet() {
        guard !scanning else { return }
        let prefixes = localPrefixes()
        guard let prefix = prefixes.first else {
            link = .failed("没有可用局域网，请先连车机热点")
            return
        }

        scanning = true
        lastScan = Date()
        link = .scanning

        let hosts = (2...254).map { "\(prefix).\($0)" }
        let lock = NSLock()
        var found: String?
        let group = DispatchGroup()
        let sem = DispatchSemaphore(value: 32)
        let session = scanSession

        for h in hosts {
            group.enter()
            DispatchQueue.global(qos: .userInitiated).async { [weak self] in
                sem.wait()
                defer { sem.signal(); group.leave() }
                lock.lock(); let done = found != nil; lock.unlock()
                if done || self == nil { return }
                if DashboardModel.probe(host: h, session: session) {
                    lock.lock()
                    if found == nil { found = h }
                    lock.unlock()
                }
            }
        }

        group.notify(queue: .main) { [weak self] in
            guard let self else { return }
            self.scanning = false
            if let found {
                self.host = "\(found):\(Self.defaultPort)"
                self.lastSuccess = .distantPast
                self.poll()
            } else if Date().timeIntervalSince(self.lastSuccess) > 8 {
                self.link = .failed("网段内没找到车机")
            }
        }
    }

    private static func probe(host: String, session: URLSession) -> Bool {
        guard let url = URL(string: "http://\(host):\(defaultPort)/health") else { return false }
        let sem = DispatchSemaphore(value: 0)
        var ok = false
        let task = session.dataTask(with: url) { data, _, _ in
            if let d = data, let s = String(data: d, encoding: .utf8), s.contains("ok") {
                ok = true
            }
            sem.signal()
        }
        task.resume()
        _ = sem.wait(timeout: .now() + 0.55)
        task.cancel()
        return ok
    }

    // MARK: - 对外展示值

    /// 车机数据的新鲜度门槛。轮询是 0.2s，1 秒内没成功就认为断了，
    /// 这样仪表上的数字不会停留在几秒前的旧值上。
    private var carFresh: Bool {
        Date().timeIntervalSince(lastSuccess) < 1.2
    }

    var isCarOnline: Bool { carFresh }

    /// 车速 —— **只用本机 GPS**。
    ///
    /// 用户要求把车机那套车速来源（实时别名推送 / 缓存快照 / 高德广播）
    /// 全部不用，只认 iPhone 自己的 GPS。车机端的车速仍然会采集，
    /// 但只写进诊断信息，不再参与显示。
    var displaySpeed: Double? {
        guard let s = localSpeed, s.isFinite, s >= 0 else { return nil }
        return s
    }

    var displayAltitude: Double? {
        if let a = car?.altitude, carFresh { return a }
        return localAltitude
    }

    /// 总里程——**优先用车机**。
    ///
    /// 车机那边现在走实时推送（不会再卡住不涨了）。
    /// 本机 GPS 里程只在车机完全没数据时兜底：它是从 App 启动开始算的，
    /// 和车机那个累计总里程根本不是一回事，混着用数字会跳。
    var displayOdometer: Double? {
        if carFresh, let o = car?.odometer, o > 0 { return o }
        if let o = car?.odometer, o > 0 { return o }
        return localOdometer > 0.3 ? localOdometer : nil
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

    /// 地图能不能显示。定位被拒或还没定位到就显示占位背景。
    var mapUnavailableReason: String? {
        if locationDenied { return "定位权限被拒绝，地图不可用" }
        if coord == nil { return "正在定位…" }
        return nil
    }
}
