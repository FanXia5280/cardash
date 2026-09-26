import Foundation
import CoreLocation

/// 本机 GPS：车速、海拔、累计里程。作为车机数据不可用时的兜底来源。
final class LocalSensors: NSObject, CLLocationManagerDelegate {

    var onUpdate: ((Double?, Double?, Double) -> Void)?
    /// 原始定位（坐标 + 车头方向），给地图用。
    /// 和 onUpdate 分开一路：地图需要的是原始 CLLocation，
    /// 而仪表那条链路已经在做「累计里程」之类的加工，混在一起会互相牵制。
    var onFix: ((CLLocation) -> Void)?
    var onDenied: (() -> Void)?

    private let manager = CLLocationManager()
    private var last: CLLocation?
    private var meters: Double = 0
    private var started = false

    /// 上一帧用于"位移核对"的定位点（比 {@link #last} 宽松：精度过得去就更新，
    /// 这样相邻两帧的时间差才够小，推算出来的速度才有意义）
    private var lastForSpeed: CLLocation?
    /// 最近一次可信的车速（km/h）。这一帧不可信时沿用它，而不是把噪声当车速发出去。
    private var lastGoodSpeedKmh: Double = 0
    /// 最近一次可信的海拔（米）。GPS 海拔静止时也会乱跳，同样要过闸。
    private var lastGoodAltitude: Double?
    private var lastAltitudeAt: Date?
    /// 最近几帧定位，用来判断"车到底动没动"（见 {@link #looksStationary}）。
    private var recentFixes: [(t: Date, loc: CLLocation)] = []
    /// 静止判定窗口：这段时间内的位移都算进来
    private static let stillWindowSec: TimeInterval = 4.0
    /// 窗口内**净位移**小于这个值 ⇒ 判定为静止（米）
    private static let stillNetMeters: Double = 6.0

    override init() {
        super.init()
        manager.delegate = self
        manager.desiredAccuracy = kCLLocationAccuracyBestForNavigation
        manager.activityType = .automotiveNavigation
        manager.distanceFilter = kCLDistanceFilterNone
        manager.pausesLocationUpdatesAutomatically = false

        // ⚠️ 2026-09-24 自查发现的**自相矛盾**：Info.plist 里声明了
        //    `UIBackgroundModes: location`（要后台跑），这里却把后台定位**关着** ——
        //    那样声明等于白写，iOS 会在 App 切后台/锁屏后把它挂起，
        //    轮询和导航引擎一起停（仪表冻在最后那一帧）。
        //    用户习惯是这块手机在车里一直当仪表用，中途切出去看别的 App、
        //    或者手动锁屏都很常见，所以这里必须打开。
        //    （打开的前提是 plist 里真有 location 后台模式，我们确实有，
        //      否则 iOS 会直接抛异常。）
        manager.allowsBackgroundLocationUpdates = true
        manager.showsBackgroundLocationIndicator = false
    }

    func start() {
        guard !started else { return }
        started = true
        manager.requestWhenInUseAuthorization()
        manager.startUpdatingLocation()
    }

    // MARK: - CLLocationManagerDelegate

    func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        switch manager.authorizationStatus {
        case .authorizedWhenInUse, .authorizedAlways:
            manager.startUpdatingLocation()
        case .denied, .restricted:
            onDenied?()
        default:
            break
        }
    }

    func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        guard let loc = locations.last else { return }

        if loc.horizontalAccuracy >= 0, loc.horizontalAccuracy < 60 {
            if let prev = last {
                let d = loc.distance(from: prev)
                if d > 0.5, d < 500 {
                    meters += d
                }
            }
            last = loc
        }

        // ⚠️ 2026-09-26 用户实测：**车停着、也没在导航，时速飙到 30 多 km/h，海拔也在飙**。
        //    根因是同一个：`loc.speed` 和 `loc.altitude` 都是 GPS 芯片的**瞬时**值 ——
        //    原来那两行（`loc.speed >= 0 ? loc.speed * 3.6 : 0`、
        //    `loc.verticalAccuracy > 0 ? loc.altitude : nil`）只挡了"负值 = 无效"，
        //    静止时多路径（地库口、高楼之间、车顶金属反射）产生的**正的大值**照样被发出去了。
        //
        //    修法：先用**最近几秒的净位移**判断"车到底动没动"（见 looksStationary）——
        //      · 没动 ⇒ 速度直接 0、海拔**冻结**（车停着海拔本来就不会变）；
        //      · 在动 ⇒ 速度再用"位移推算值"核对芯片值、海拔做跳变核对。
        let still = looksStationary(loc)
        let speedKmhValue = speedKmh(loc, stationary: still)
        let altitude = altitudeMeters(loc, stationary: still)
        onUpdate?(speedKmhValue, altitude, meters / 1000.0)
        onFix?(loc)
    }

    /// 车"基本没动"吗？—— 看**最近几秒的净位移**，而不是单帧位移。
    ///
    /// 单帧位移会把多路径噪声误判成"在动"（静止时 GPS 位置会跳出去十几米再跳回来）；
    /// 净位移是"窗口里最新点 与 最旧点 的距离"，噪声来回跳时它接近 0。
    /// 窗口不够长（不足 2.5 秒）时不下结论，避免刚启动就把真移动误判成静止。
    private func looksStationary(_ loc: CLLocation) -> Bool {
        recentFixes.append((loc.timestamp, loc))
        while let f = recentFixes.first,
              loc.timestamp.timeIntervalSince(f.t) > Self.stillWindowSec {
            recentFixes.removeFirst()
        }
        guard let first = recentFixes.first else { return false }
        let span = loc.timestamp.timeIntervalSince(first.t)
        guard span >= 2.5 else { return false }
        return loc.distance(from: first.loc) < Self.stillNetMeters
    }

    /// 从这一帧定位里算出**可信**的海拔（米）。不可信就沿用上一帧的可信值。
    ///
    /// ⚠️ 2026-09-26 用户实测：**时速乱飙的同时海拔也在飙** —— 同一个根因
    /// （`loc.altitude` 也是芯片瞬时值，而垂直精度天生比水平差得多）。
    ///
    /// 1. **判定没动 ⇒ 直接沿用旧值（冻结）** —— 车停着海拔不会变，
    ///    而 GPS 海拔在静止时正是最爱乱跳的（±10~20 米很常见）；
    /// 2. `verticalAccuracy` 太差（> 20m）⇒ 这一帧海拔不可信，沿用好值；
    /// 3. 跳变核对：10 秒内跳超过 25 米 ⇒ 当噪声丢掉
    ///    （车在地面上跑，10 秒内海拔真变化 25 米只可能是隧道口/大立交，
    ///      那种情况会连着好几帧一起变，不会只跳一帧又跳回来）。
    private func altitudeMeters(_ loc: CLLocation, stationary: Bool) -> Double? {
        if stationary { return lastGoodAltitude }
        if loc.verticalAccuracy < 0 || loc.verticalAccuracy > 20 {
            return lastGoodAltitude
        }
        guard loc.altitude.isFinite else { return lastGoodAltitude }

        if let prev = lastGoodAltitude, let at = lastAltitudeAt {
            let dt = loc.timestamp.timeIntervalSince(at)
            if dt >= 0, dt < 10, abs(loc.altitude - prev) > 25 {
                return prev                      // 单帧跳变 ⇒ 噪声
            }
        }
        lastGoodAltitude = loc.altitude
        lastAltitudeAt = loc.timestamp
        return loc.altitude
    }

    /// 从这一帧定位里算出**可信**的车速（km/h）。
    ///
    /// ⚠️ 2026-09-26 用户实测：**车停着、也没在导航，时速会飙到 30 多 km/h**。
    /// 根因是原来那行 `loc.speed >= 0 ? loc.speed * 3.6 : 0` —— `loc.speed` 是
    /// GPS 芯片的**瞬时**值，静止 / 多路径（地库口、高楼之间、车顶金属反射）时会
    /// 给出**正的**大值，而原来只挡了"负值 = 无效"，噪声就被当成真车速显示了出去。
    ///
    /// 1. **判定没动 ⇒ 直接 0**，不再看芯片值（这就是那个 bug 的正解）；
    /// 2. `speedAccuracy` / `horizontalAccuracy` 太差 ⇒ 这一帧整体不可信，沿用上帧可信值
    ///    （`speedAccuracy` 是 iOS 13.4+，工程 deploymentTarget 16.0，能直接用）；
    /// 3. 位移核对：芯片值比"相邻两帧位移 ÷ 时间"大出 5 m/s 以上 ⇒ 采信位移推算值
    ///    （车真在动，位移必然对得上）。
    private func speedKmh(_ loc: CLLocation, stationary: Bool) -> Double {
        // 位移核对要用**上一帧**，所以要在这里更新 lastForSpeed
        let prev = lastForSpeed
        defer {
            if loc.horizontalAccuracy >= 0, loc.horizontalAccuracy < 100 {
                lastForSpeed = loc
            }
        }

        if stationary {
            lastGoodSpeedKmh = 0
            return 0
        }

        guard loc.speed >= 0 else { return lastGoodSpeedKmh }
        guard loc.speedAccuracy >= 0, loc.speedAccuracy <= 3.0 else { return lastGoodSpeedKmh }
        guard loc.horizontalAccuracy >= 0, loc.horizontalAccuracy <= 50 else { return lastGoodSpeedKmh }

        var kmh = loc.speed * 3.6
        if let prev {
            let dt = loc.timestamp.timeIntervalSince(prev.timestamp)
            if dt > 0, dt < 5 {
                // 芯片值明显偏大 ⇒ 用位移推算值（它才是"真的走了多远"）。
                // "没动"那种情况已经在上面的 stationary 分支里处理掉了。
                let derived = loc.distance(from: prev) / dt     // m/s
                if loc.speed > derived + 5.0 {
                    kmh = derived * 3.6
                }
            }
        }
        if kmh < 0 { kmh = 0 }
        lastGoodSpeedKmh = kmh
        return kmh
    }

    func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        // 定位失败时保持上一次数据，不影响仪表显示
    }
}
