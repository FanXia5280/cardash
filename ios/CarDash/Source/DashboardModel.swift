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
    /// 目的地坐标（WGS-84）
    @Published private(set) var routeDest: CLLocationCoordinate2D?
    /// 已经算过路线的那份目的地，用来去重
    private var routedKey: String?
    /// 「导航看起来结束了」是从什么时候开始的（nil = 现在还在导航）。
    /// ⚠️ 单帧不算数 —— 见 `beginRouteStopIfNeeded()` 的说明：
    /// 用户要求中间不中断导航，而车机那边报 active:false 可能只是引导广播断了几秒。
    private var navOffSince: Date?
    /// 这一轮"收路线"已经执行过了，别每 250ms 重复触发
    private var stopPending = false
    /// 用户按「退出导航」时按掉的那条目的地 key。
    /// 车机还在导航、还在广播同一条目的地 —— 不记这个的话下一次 poll
    /// 立刻把同一条路线算回来，按钮等于没用。
    /// 换了目的地 / 车机导航结束 / 车机失联，都自动作废。
    private var suppressedDestKey: String?
    /// 测试用：手动塞的假目的地。正式版可以连这块一起删掉。
    @Published private(set) var mockDest: Dest?
    /// 模拟路线时顺带塞的假导航信息。
    /// 连车机时「还有多久 / 多远 / 几点到」由车机的高德广播给；
    /// 没连车机时这三格是空的，用户就看不出来排版对不对 —— 所以模拟时自己填一份。
    @Published private(set) var mockNav: NavState?

    /// 「模拟转向灯」：0 = 关（用真实值）、1 = 左转、2 = 右转、3 = 双闪。
    /// 用户 2026-09-24 要求：没车时先预览两边绿光效果（左/右/双闪都要能试）。看 `displayTurn`。
    @Published var mockTurn = 0

    /// 「模拟超速」开关：没车/没电子眼数据时也能预览两边红光。
    /// 看 `isOverspeed` —— 打开就直接当作超速。
    @Published var mockOverspeed = false

    /// 现在是「模拟导航」测试模式吗（点了设置里那条「模拟一条导航路线」）。
    /// 真导航用 SDK 的实时导航（startGPSNavi），模拟模式用官方**模拟导航**
    /// （startEmulatorNavi，沿路线自动跑一遍，能看路况和电子眼）—— 见 NaviKitNavView。
    var isSimulating: Bool { mockDest != nil }

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
    /// 上一次请求是什么时候发出去的。见 poll()：完成回调万一没回来，
    /// inFlight 会永远卡在 true，整个仪表就再也不刷新了。
    private var inFlightSince = Date.distantPast
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

    /// 一键抓取**全部**诊断，拼成一份 —— 排查时用户点一次、复制一次就能整段发过来。
    ///
    /// 依次取 /state → /logcat → /diag → /log → /scan，每段带分隔标题；
    /// 开头附上 App 版本 / 桥接版本 / 链路 / 时间 —— 拿到就知道是哪一版、连没连上车机。
    ///
    /// ⚠️ 必须**串行**：车机那个 HTTP 服务是单线程的，并发抓会互相等超时。
    func fetchAllDiagnostics(completion: @escaping (String) -> Void) {
        let items: [(String, String)] = [
            ("/state", "状态快照（JSON）—— 目的地 / 导航来源"),
            ("/logcat", "logcat 取数诊断 —— 高德广播的全部 key 在这里"),
            ("/diag", "车机运行状态"),
            ("/log", "桥接运行日志"),
            ("/scan", "厂商属性扫描"),
        ]
        var parts: [String] = [diagnosticHeader()]

        func step(_ i: Int) {
            if i >= items.count {
                let bar = String(repeating: "=", count: 60)
                parts.append("\n" + bar + "\n【诊断结束】共 \(items.count) 段\n" + bar)
                completion(parts.joined(separator: "\n"))
                return
            }
            let (path, title) = items[i]
            fetchText(path: path) { text in
                let bar = String(repeating: "=", count: 60)
                parts.append("\n" + bar + "\n【" + title + "】" + path + "\n" + bar + "\n" + text)
                step(i + 1)
            }
        }
        step(0)
    }

    /// 诊断开头的元信息。拿到日志先能判断是哪一版、连没连上车机 ——
    /// 之前每次都要追问「你装的哪个版本」，就是缺这一段。
    private func diagnosticHeader() -> String {
        let info = Bundle.main.infoDictionary ?? [:]
        let app = info["CFBundleShortVersionString"] as? String ?? "?"
        let build = info["CFBundleVersion"] as? String ?? "?"
        let bridge: String
        if let v = car?.src?["apkVer"], !v.isEmpty {
            bridge = v
        } else if car == nil {
            bridge = "未连接（拿不到桥接版本）"
        } else {
            bridge = "旧版 APK（无版本标识）"
        }
        let f = DateFormatter()
        f.dateFormat = "yyyy-MM-dd HH:mm:ss"
        return """
        CarDash 全部诊断（一键复制）
          抓取时间 : \(f.string(from: Date()))
          App 版本 : \(app) (\(build))
          桥接版本 : \(bridge)
          链路状态 : \(link.label)
          车机地址 : \(carHost ?? "-")
          当前目的地: \(car?.dest?.name ?? "（车机没在导航）")
        """
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
            }
            // （以前这里还有一支「停着时拿自绘路线的前进方向当车头」的兜底 ——
            //   2026-09-24 自绘路线整块删掉后就没了。停车时车头靠罗盘/上一次 GPS 航向，
            //   导航态更是高德 3D 车标自己处理朝向。）

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
        // 车机失联太久 ⇒ 收掉导航（理由见 dropRouteBecauseCarGone）
        if mockDest == nil, routeDest != nil,
           Date().timeIntervalSince(lastSuccess) > 150 {
            dropRouteBecauseCarGone()
        }
        guard Date().timeIntervalSince(lastSuccess) > 8 else { return }
        guard Date().timeIntervalSince(lastScan) > 45 else { return }
        scanSubnet()
    }

    /// 车机失联太久（>150 秒）⇒ 认为车已经熄火/关机，把导航收掉。
    ///
    /// ⚠️ 2026-09-24 自查：`syncDestination()` 只在**拉取成功**时才跑，
    /// 所以车机一断电，手机端再也收不到任何消息 ⇒ `routeDest` 会一直留着，
    /// 导航引擎继续空转（用户习惯：熄火下车、走远就离开车机热点了）。
    /// 表现是"人都进楼了，手机上还在导航"。
    ///
    /// 150 秒是故意留宽的：行车途中热点打个嗝（几秒、几十秒）绝不能当成结束，
    /// 真断了这么久说明车那边已经不在了。重启车机后自然重来一遍
    /// （车机自己的状态是内存态，重启就没有目的地了）。
    private func dropRouteBecauseCarGone() {
        navOffSince = nil
        stopPending = false
        routeDest = nil
        routedKey = nil
        suppressedDestKey = nil
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
        // ⚠️ 不能只看 `inFlight` 这个布尔（2026-09-24 自查）：它只在完成回调里
        //    才会被清掉，万一那个回调没执行（App 被挂起、网络栈异常），
        //    它就永远卡在 true ⇒ `poll()` 永远直接 return ⇒ **整个仪表再也不刷新**，
        //    而且不报错、界面看着还"活着"。所以配一个时间闸门：
        //    请求超时是 2 秒，超过 3 秒还没回来就认为它废了，放新的出去。
        if inFlight, Date().timeIntervalSince(inFlightSince) < 3 { return }
        inFlight = true
        inFlightSince = Date()

        session.dataTask(with: url) { [weak self] data, response, error in
            DispatchQueue.main.async {
                guard let self else { return }
                self.inFlight = false

                if let data,
                   let snap = try? JSONDecoder().decode(CarSnapshot.self, from: data) {
                    // ⚠️ 只在**真的变了**才赋值。
                    // `@Published` 是"只要赋值就发通知"，而这个 poll 每 250ms 跑一次 ——
                    // 无条件赋值等于让整个 HUD（连同地图那个 UIViewRepresentable）
                    // 每秒重绘 4 次，地图会被一起拖累（用户反馈"地图卡卡的、没有 60 帧"）。
                    // CarSnapshot 是 Equatable，比一下几乎不花时间。
                    if snap != self.car { self.car = snap }
                    self.lastSuccess = Date()
                    if self.link != .online { self.link = .online }
                    if self.carHost != self.host { self.carHost = self.host }
                    // 车机一报新目的地就重算路线（内部按目的地去重）
                    self.syncDestination()
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

    /// 转向灯：0=灭 1=左 2=右 3=双闪。nil = 车机没这个数据（旧版车机 APK）。
    /// 模拟转向灯选了方向时优先返回它（预览效果用）。
    var displayTurn: Int? {
        if mockTurn > 0 { return mockTurn }
        return carFresh ? car?.turn : nil
    }

    /// 当前路段限速（车机高德广播给的）。0/缺省都当"不知道"。
    var displayLimit: Int? {
        if carFresh, let l = car?.limit, l > 0 { return l }
        return nil
    }

    /// 前方电子眼（车机高德引导广播）
    var displayCamera: CameraInfo? {
        carFresh ? car?.camera : nil
    }

    /// 要不要"两边冒红"？
    ///
    /// ⚠️ 用户 2026-09-24 提的关键点：**高德的红色脉冲是"会被拍限速的电子眼"才冒**，
    /// 不是"超过当前道路限速就冒" —— 路上很多电子眼根本不测速（闯红灯、监控、
    /// 公交道、应急车道…），按路段限速冒红会一路误报。
    ///
    /// 所以规则是：前方 300 米内**有电子眼** →
    ///   ① 只看它是不是**测速类**（高德 `AMapNaviCameraType`：0 测速 / 8 区间测速起始 /
    ///      9 区间测速终止；另外只要它自带限速值，也认定是测速类）；
    ///   ② 再看车速有没有超过**该电子眼自己的限速**（它没给就用路段限速）+ 2km/h 容差。
    /// 数据不全时一律 false —— 宁可不报，绝不误报。
    ///
    /// 这个判断**自己算**，不用高德 SDK 的 `showOverSpeedPulse`（那是收费接口，
    /// 用户已明确只用官方免费功能）。
    var isOverspeed: Bool {
        // 模拟超速：没车也能看红光效果（见设置里的开关）
        if mockOverspeed { return true }
        guard let s = displaySpeed, s.isFinite else { return false }
        guard let cam = displayCamera, let d = cam.dist, d >= 0, d <= 300 else { return false }

        let speedCamTypes = [0, 8, 9]
        let isSpeedCamera = (cam.type.map { speedCamTypes.contains($0) } ?? false)
            || (cam.speed ?? 0) > 0
        guard isSpeedCamera else { return false }

        let limit = (cam.speed ?? 0) > 0 ? cam.speed : displayLimit
        guard let l = limit, l > 0 else { return false }
        return s > Double(l) + 2
    }

    var displaySoc: Double? {
        carFresh ? car?.soc : nil
    }

    var displayRange: Double? {
        carFresh ? car?.range : nil
    }

    var displayNav: NavState? {
        if let m = mockNav { return m }        // 模拟路线优先（给用户试排版用）
        // 用户按过「退出导航」、而车机还在推同一条目的地 ⇒ **顶栏摘要也一起收掉**。
        //
        // ⚠️ 2026-09-25 修（用户实测截图：地图已经回普通地图了，顶栏还挂着
        //    「15 分钟 6.7 公里 预计 10:45 到达」）：以前只收导航视图，
        //    车机那边照旧报 nav.active=true，摘要就一直显示。
        // 车机换目的地 / 导航结束 / 失联时 suppressedDestKey 会自动作废
        //（见 syncDestination），所以新导航照常显示。
        if let key = suppressedDestKey, key == car?.dest?.routeKey { return nil }
        return carFresh ? car?.nav : nil
    }

    // MARK: - 自动同步目的地

    /// 车机一报目的地，就把**目的地坐标**解出来交给导航视图。
    ///
    /// 目的地有两条来源（Android 侧见 `DestSignals`）：
    /// **高德车机版广播**（权威 —— 车机上手动点导航也算，用户 2026-09-24 的要求）
    /// 和**语音助手的 NLU 日志**（兜底）。这里不区分，都按同一套规则处理。
    ///
    /// ⚠️ 2026-09-24 用户要求：**自绘路线整块删掉**。导航已经是高德官方视图
    /// （`AMapNaviDriveView`，路线由 SDK 自己画，更准也更省电），我们以前那套
    /// 「Web API 算路 + MAMapView 画 polyline」纯属多余 —— 而且启动导航那一瞬间
    /// 还会露出一下（用户实测看到了）。
    ///
    /// 现在这里只做三件事：挑目的地 → 解出 WGS-84 坐标 → 写进 `routeDest`。
    /// 画路线、算剩余时间距离，全是 SDK 的事。
    private func syncDestination() {
        // 只有**车机导航开着**的时候，才认车机报的目的地。
        //
        // ⚠️ 2026-09-23：以前不看这个，只要 /state 里有 dest 就一直算下去。
        // 车机那边的 dest 有新鲜度窗口，导航早就结束了它还在，
        // 结果仪表上长期挂着一条不存在的路线（用户说「IPA 会自己改目的地」）。
        let navOn = car?.nav?.isActive == true
        // 测试按钮塞的假目的地优先；没有就用车机的（前提是车机在导航）
        let candidate = mockDest ?? (navOn ? car?.dest : nil)
        guard let dest = candidate, dest.isUsable else {
            // 车机导航结束了（或目的地没了）⇒ 「退出导航」的记号作废，
            // 不然下一次导航到同一个地方会被它拦住
            suppressedDestKey = nil
            // ⚠️ **不要一帧就放手**（2026-09-24 用户要求：中间不中断导航）。
            //
            // 车机侧判断"导航还在不在"靠的是引导广播的新鲜度（60 秒窗口），
            // 高德重算路线 / 切前后台 / 进隧道时会短暂断流 —— 这时 /state 会报
            // `nav.active:false`，而这里一旦把 routeDest 清掉，
            // `NaviCoordinator` 立刻 `stopNavi()`，用户看到的就是「导航自己退出了」。
            //
            // 所以加一道去抖：连续 20 秒都拿不到可用目的地，才真的放手。
            // 真结束了也不会拖太久（车机那边还有 90 秒的会话看门狗）。
            beginRouteStopIfNeeded()
            return
        }
        navOffSince = nil
        stopPending = false
        guard coord != nil else { return }

        let key = dest.routeKey
        guard key != routedKey else { return }
        // 用户刚按过「退出导航」、车机还在推同一条目的地 ⇒ 不理，别算回来。
        // 换了目的地（key 不同）自然走到下面，记号随之作废。
        guard key != suppressedDestKey else { return }
        routedKey = key
        suppressedDestKey = nil

        resolveDestination(dest) { [weak self] to in
            guard let self, let to else { return }
            // 最后一道防线：目的地离当前位置太近（<150 米）基本可以断定是
            // 「把车自己的位置当成了终点」—— 那正是 2026-09-23 那次乱跳的症状。
            // 宁可这一趟不导航，也不带着一个和车机完全不同的目的地去导航。
            if let here = self.coord {
                let a = CLLocation(latitude: here.latitude, longitude: here.longitude)
                let b = CLLocation(latitude: to.latitude, longitude: to.longitude)
                if a.distance(from: b) < 150 {
                    DispatchQueue.main.async {
                        self.routeDest = nil
                        // ⚠️ 故意**不清 routedKey**：清了的话每次刷新都会重新
                        // 地理编码，白刷接口。key 记着「这条已处理过」。
                    }
                    return
                }
            }
            DispatchQueue.main.async { self.routeDest = to }
        }
    }

    /// 目的地这一帧拿不到了 —— 但不马上收路线，等 20 秒确认。
    ///
    /// 为什么要去抖（2026-09-24 用户要求「中间不中断导航」）：
    /// `routeDest` 一变 nil，`NaviCoordinator.update(to: nil)` 就会 `stopNavi()`，
    /// 画面掉回普通地图、顶栏的导航摘要也没了。而车机侧报 `nav.active:false`
    /// 可能只是引导广播断了一小会儿（高德重算路线、切前后台、进隧道）。
    /// 车机那边已经有一个 90 秒的会话看门狗兜底，这里 20 秒足够安全。
    private func beginRouteStopIfNeeded() {
        guard routeDest != nil, !stopPending else { return }
        let now = Date()
        guard let since = navOffSince else {
            navOffSince = now          // 第一帧：先记下时间，给一次机会
            return
        }
        // ⚠️ 2026-09-25 从 20 秒缩到 10 秒：车机侧现在只在**真的判到导航结束**
        //（引导广播断 60 秒、或巡航）时才会报 active:false，一次 250ms 的抖动
        // 已经不可能再造成误判了 ⇒ 手机这边不用再等那么久。
        // 用户要求「车机退出导航，IPA 也自动退出」，10 秒是留给"单次丢包"的余量。
        guard now.timeIntervalSince(since) >= 10 else { return }
        stopPending = true
        routeDest = nil
        routedKey = nil
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
        // 连车机时这些字段由车机的高德广播给；测试排版时得自己填，
        // 否则顶栏中间那块（还有多久 / 多远 / 几点到）是空的，没法看位置对不对。
        mockNav = NavState(active: true,
                           title: nil,
                           subtitle: nil,
                           distance: nil,
                           after: nil,
                           eta: "42 分钟",
                           remain: "21.9 公里",
                           turn: "straight",
                           arrive: "预计 19:30 到达")
        routedKey = nil
        syncDestination()
    }

    func clearMockDestination() {
        mockDest = nil
        mockNav = nil
        routedKey = nil
        syncDestination()
    }

    /// 「退出导航」按钮（用户 2026-09-24 要求：竖屏放总里程上面、横屏放海拔的位置）。
    ///
    /// 只结束 **iPhone 这边**的导航：停引擎、收起导航视图。
    /// 车机那边还在导航、还在广播同一个目的地 —— 所以记住这条目的地 key，
    /// 车机继续推它就不重算（否则下一次 poll 立刻把路线算回来，按钮白按）；
    /// 一旦换了目的地、车机导航结束或车机失联，记号自动作废，
    /// 下一次导航照常跟上。
    func endNavigation() {
        // ⚠️ 模拟模式下**不能**只走 clearMockDestination —— 它只清 mock，
        // routeDest 要等 beginRouteStopIfNeeded 的 20 秒去抖才被清掉，
        // 用户点「退出导航」后导航视图会赖在那儿二十秒才跳回绿点
        //（用户实测「还是会卡一下」，就是这个）。
        // 所以两种模式都**立刻**收路线。
        navOffSince = nil
        stopPending = false
        routeDest = nil
        routedKey = nil
        if isSimulating {
            mockDest = nil
            mockNav = nil
        } else {
            // 真导航：记住这条目的地，车机继续推它时不再算回来
            suppressedDestKey = car?.dest?.routeKey
        }
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
