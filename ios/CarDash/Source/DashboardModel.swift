import Foundation
import Darwin
import CoreLocation
import MapKit

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
    /// 去目的地的路线（WGS-84）。车机报上来目的地后自动算。
    @Published private(set) var route: [CLLocationCoordinate2D] = []
    /// 按路况分段的路线（和高德一路，用来画绿/黄/红）
    @Published private(set) var routeSegments: [RouteSegment] = []
    /// 目的地坐标（WGS-84）
    @Published private(set) var routeDest: CLLocationCoordinate2D?
    /// 已经算过路线的那份目的地，用来去重
    private var routedKey: String?
    /// 测试用：手动塞的假目的地。正式版可以连这块一起删掉。
    @Published private(set) var mockDest: Dest?

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
            if loc.course >= 0, loc.speed > 1.0 {
                // 移动中：GPS 航向最可靠（罗盘在车里会被车身磁场带偏）
                self.heading = loc.course
            } else if let b = routeBearing(at: loc.coordinate) {
                // 停着的时候 course 无效 —— 高德的做法是把车头对齐
                // 路线的前进方向，这样车标和路线永远一致，
                // 「车头朝上 / 朝前」在停车时也成立
                self.heading = b
            }

            // 目的地来自车机快照，快照更新时才算路（见 poll），
            // 这里只更新车辆位置
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
                    // 车机一报新目的地就重算路线（内部按目的地去重）
                    self.replanRoute()
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

    // MARK: - 自动同步路线

    /// 车机一报目的地就在本机算一条路线出来。
    ///
    /// 目的地是 Android 侧从车机语音助手的 NLU 日志里解析出来的
    /// （D.apk 的 AssistantUtil 就是这么干的，我们套了同一套正则）。
    ///
    /// 算法说明：
    ///   - 有坐标就直接用；只有名字就先地理编码。车机给的是 GCJ-02，
    ///     而系统算路要 WGS-84，所以先转一道。
    ///   - 路线用系统的 MKDirections：不需要 key、不依赖第三方服务。
    ///     画到地图上是高德那种蓝色路线，视觉和车机一致。
    private func replanRoute() {
        // 测试按钮塞的假目的地优先；清掉就回落到车机真实数据
        guard let dest = mockDest ?? car?.dest else {
            if !route.isEmpty || routeDest != nil || !routeSegments.isEmpty {
                route = []
                routeSegments = []
                routeDest = nil
                routedKey = nil
            }
            return
        }
        guard let from = coord else { return }

        let key = dest.routeKey
        guard key != routedKey else { return }
        routedKey = key

        resolveDestination(dest) { [weak self] to in
            guard let self, let to else { return }

            // 先走高德算路：和车机同源，路线能对得上
            self.amapRoute(from: from, to: to) { pts, segs in
                DispatchQueue.main.async {
                    if let pts {
                        self.route = pts
                        self.routeSegments = segs ?? [RouteSegment(points: pts, status: 0)]
                        self.routeDest = to
                        return
                    }
                    // 高德不通（没网 / 配额用尽 / Key 不对）再退回系统算路
                    self.appleRoute(from: from, to: to) { p2 in
                        self.route = p2 ?? []
                        self.routeSegments = (p2?.count ?? 0) >= 2
                            ? [RouteSegment(points: p2!, status: 0)] : []
                        self.routeDest = to
                    }
                }
            }
        }
    }

    /// 高德驾车路径规划（Web 服务）。
    ///
    /// 用 **Web 平台**那个 Key —— 高德把「地图渲染」和「数据服务」拆成
    /// 不同平台的 Key，iOS 平台那个拿去调算路接口是过不了校验的。
    /// 返回的 polyline 是 GCJ-02，我们内部统一存 WGS-84，所以转一道。
    private func amapRoute(from: CLLocationCoordinate2D,
                           to: CLLocationCoordinate2D,
                           done: @escaping ([CLLocationCoordinate2D]?, [RouteSegment]?) -> Void) {
        let o = ChinaCoord.toGcj(from)
        let d = ChinaCoord.toGcj(to)
        var comp = URLComponents(string: "https://restapi.amap.com/v3/direction/driving")!
        comp.queryItems = [
            URLQueryItem(name: "origin", value: "\(o.longitude),\(o.latitude)"),
            URLQueryItem(name: "destination", value: "\(d.longitude),\(d.latitude)"),
            URLQueryItem(name: "extensions", value: "all"),
            URLQueryItem(name: "strategy", value: "32"),   // 高德推荐（躲避拥堵+不走高速）
            URLQueryItem(name: "key", value: AMapConfig.webKey),
        ]
        guard let url = comp.url else { done(nil, nil); return }

        URLSession.shared.dataTask(with: url) { data, _, _ in
            guard let data,
                  let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
                  (obj["status"] as? String) == "1",
                  let route = obj["route"] as? [String: Any],
                  let paths = route["paths"] as? [[String: Any]],
                  let steps = paths.first?["steps"] as? [[String: Any]]
            else {
                done(nil, nil)
                return
            }
            // 高德在 extensions=all 时会给 steps[].tmcs[]，
            // 每一小段都有自己的 polyline 和 status（畅通/缓行/拥堵/严重拥堵）。
            // 有就用它分段，没有（老接口/被限流）就整段当畅通。
            var segs: [RouteSegment] = []
            for s in steps {
                let tmcs = s["tmcs"] as? [[String: Any]]
                if let tmcs, !tmcs.isEmpty {
                    for t in tmcs {
                        guard let pl = t["polyline"] as? String else { continue }
                        let pts = Self.parsePolyline(pl)
                        guard !pts.isEmpty else { continue }
                        segs.append(RouteSegment(points: pts,
                                                 status: Self.statusIndex(t["status"] as? String)))
                    }
                } else if let pl = s["polyline"] as? String {
                    let pts = Self.parsePolyline(pl)
                    if !pts.isEmpty { segs.append(RouteSegment(points: pts, status: 0)) }
                }
            }
            let wgs = segs.flatMap { $0.points }
            done(wgs.isEmpty ? nil : wgs, segs.isEmpty ? nil : segs)
        }.resume()
    }

    private static func parsePolyline(_ s: String) -> [CLLocationCoordinate2D] {
        var out: [CLLocationCoordinate2D] = []
        for pair in s.split(separator: ";") {
            let xy = pair.split(separator: ",")
            guard xy.count == 2,
                  let lon = Double(xy[0]), let lat = Double(xy[1]) else { continue }
            out.append(ChinaCoord.toWgs(CLLocationCoordinate2D(latitude: lat, longitude: lon)))
        }
        return out
    }

    private static func statusIndex(_ s: String?) -> Int {
        switch s {
        case "缓行": return 1
        case "拥堵": return 2
        case "严重拥堵": return 3
        default: return 0      // 畅通 / 高德没给
        }
    }

    /// 系统算路（兜底）
    private func appleRoute(from: CLLocationCoordinate2D,
                            to: CLLocationCoordinate2D,
                            done: @escaping ([CLLocationCoordinate2D]?) -> Void) {
        DispatchQueue.main.async {
            let req = MKDirections.Request()
            req.source = MKMapItem(placemark: MKPlacemark(coordinate: from))
            req.destination = MKMapItem(placemark: MKPlacemark(coordinate: to))
            req.transportType = .automobile
            MKDirections(request: req).calculate { resp, _ in
                guard let r = resp?.routes.first else { done(nil); return }
                let n = r.polyline.pointCount
                var pts: [CLLocationCoordinate2D] = []
                pts.reserveCapacity(n)
                let raw = r.polyline.points()
                for i in 0..<n { pts.append(raw[i].coordinate) }
                done(pts)
            }
        }
    }

    /// 车辆位置附近那段路线的前进方向（度，顺时针从北起）。
    /// 找离车最近的路线点，取它到下一点的方向；在末尾就取前一点到它的方向。
    private func routeBearing(at c: CLLocationCoordinate2D) -> Double? {
        guard route.count >= 2 else { return nil }
        let here = CLLocation(latitude: c.latitude, longitude: c.longitude)
        var best = 0
        var bestD = Double.greatestFiniteMagnitude
        for (i, p) in route.enumerated() {
            let d = here.distance(from: CLLocation(latitude: p.latitude,
                                                   longitude: p.longitude))
            if d < bestD { bestD = d; best = i }
        }
        if best + 1 < route.count {
            return Self.bearing(route[best], route[best + 1])
        }
        guard best >= 1 else { return nil }
        return Self.bearing(route[best - 1], route[best])
    }

    /// 两点间的方位角（度，顺时针从北起，和高德/GPS course 同一约定）
    private static func bearing(_ a: CLLocationCoordinate2D,
                                _ b: CLLocationCoordinate2D) -> Double {
        let f1 = a.latitude * .pi / 180, f2 = b.latitude * .pi / 180
        let dl = (b.longitude - a.longitude) * .pi / 180
        let y = sin(dl) * cos(f2)
        let x = cos(f1) * sin(f2) - sin(f1) * cos(f2) * cos(dl)
        return ((atan2(y, x) * 180 / .pi) + 360).truncatingRemainder(dividingBy: 360)
    }

    // MARK: - 测试：模拟车机报目的地

    /// 在当前位置东北方向约 3 公里放一个假目的地。
    /// 坐标要生成成 **GCJ-02** —— 车机报上来的就是火星坐标，
    /// resolveDestination 会按 GCJ-02 转回 WGS-84，得保持一致。
    func sendMockDestination() {
        guard let c = coord else { return }
        let g = ChinaCoord.toGcj(c)
        let dLat = 3.0 / 111.0
        let dLon = 3.0 / (111.0 * cos(c.latitude * .pi / 180))
        mockDest = Dest(name: "测试目的地",
                        lat: g.latitude + dLat,
                        lon: g.longitude + dLon)
        routedKey = nil
        replanRoute()
    }

    func clearMockDestination() {
        mockDest = nil
        routedKey = nil
        replanRoute()
    }

    /// 拿到目的地的 WGS-84 坐标：有坐标先转坐标系，只有名字就地理编码。
    private func resolveDestination(_ dest: Dest,
                                    done: @escaping (CLLocationCoordinate2D?) -> Void) {
        if let la = dest.lat, let lo = dest.lon, la != 0, lo != 0 {
            // 车机给的是火星坐标，转回 WGS-84 再交给系统算路
            let gcj = CLLocationCoordinate2D(latitude: la, longitude: lo)
            done(ChinaCoord.toWgs(gcj))
            return
        }
        guard let name = dest.name, !name.isEmpty else { done(nil); return }
        CLGeocoder().geocodeAddressString(name) { marks, _ in
            done(marks?.first?.location?.coordinate)
        }
    }

    /// 地图能不能显示。定位被拒或还没定位到就显示占位背景。
    var mapUnavailableReason: String? {
        if locationDenied { return "定位权限被拒绝，地图不可用" }
        if coord == nil { return "正在定位…" }
        return nil
    }
}
