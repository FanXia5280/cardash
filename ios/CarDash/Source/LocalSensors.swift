import Foundation
import CoreLocation

/// 本机 GPS：车速、累计里程。作为车机数据不可用时的兜底来源。
///
/// ⚠️ 海拔 2026-09-26 已按用户要求**整个移除**（原来经 `onUpdate` 传给仪表，
/// 后来那个"冻结 + 精度闸"的写法没有 bootstrap 路径，导致海拔永久显示 `--`，
/// 见交接文档 §6 第 84 条）。要加回来，记得**先给初值再收严**。
final class LocalSensors: NSObject, CLLocationManagerDelegate {

    var onUpdate: ((Double?, Double) -> Void)?
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

        // 先判断"车到底动没动" —— 下面的里程累加和车速都用这同一份结论
        let still = looksStationary(loc)

        if loc.horizontalAccuracy >= 0, loc.horizontalAccuracy < 60 {
            if let prev = last {
                let d = loc.distance(from: prev)
                // ⚠️ 判定静止时**不累加**：GPS 抖动会让停着的车"越跑越远"
                //    （和车速是同一类噪声，见 §6 第 84 条）。
                if !still, d > 0.5, d < 500 {
                    meters += d
                }
            }
            last = loc
        }

        // ⚠️ 2026-09-26 用户实测：**车停着、也没在导航，时速飙到 30 多 km/h**。
        //    根因是原来那行 `loc.speed >= 0 ? loc.speed * 3.6 : 0` ——
        //    `loc.speed` 是 GPS 芯片的**瞬时**值，静止时多路径（地库口、高楼之间、
        //    车顶金属反射）产生的**正的大值**照样被发出去了。
        //
        //    修法：用**最近几秒的净位移**判断"车到底动没动"（见 looksStationary）——
        //      · 没动 ⇒ 速度直接 0；
        //      · 在动 ⇒ 再用"位移推算值"核对芯片值。
        let speedKmhValue = speedKmh(loc, stationary: still)
        onUpdate?(speedKmhValue, meters / 1000.0)
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

    /// 从这一帧定位里算出**可信**的车速（km/h）。
    ///
    /// ⚠️ 2026-09-26 用户实测：**车停着、也没在导航，时速会飙到 30 多 km/h**。
    /// 根因是原来那行 `loc.speed >= 0 ? loc.speed * 3.6 : 0` —— `loc.speed` 是
    /// GPS 芯片的**瞬时**值，静止 / 多路径（地库口、高楼之间、车顶金属反射）时会
    /// 给出**正的**大值，而原来只挡了"负值 = 无效"，噪声就被当成真车速显示了出去。
    ///
    /// 1. **判定没动 ⇒ 直接 0**，不再看芯片值（这就是那个 bug 的正解）；
    /// 2. 这一帧**可信度不够**（`speed` 负、`speedAccuracy` > 3 m/s、`horizontalAccuracy` > 50m）
    ///    ⇒ 退到"**位移推算值**"，⚠️ **不是**沿用上一帧的值 ——
    ///    否则市区/隧道里精度长期偏差时，正在跑的车速会被**永久冻住**（初值 0 ⇒ 一直显示 0）；
    /// 3. 芯片值比"相邻两帧位移 ÷ 时间"大出 18km/h 以上 ⇒ 采信位移推算值
    ///    （车真在动，位移必然对得上）。
    ///    （`speedAccuracy` 是 iOS 13.4+，工程 deploymentTarget 16.0，能直接用。）
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

        // 位移推算值：只依赖**位置差 ÷ 时间**，不受多普勒（loc.speed）噪声影响。
        // 相邻两帧时间差要在合理区间，否则不推算。
        func derivedKmh() -> Double? {
            guard let prev else { return nil }
            let dt = loc.timestamp.timeIntervalSince(prev.timestamp)
            guard dt > 0, dt < 5 else { return nil }
            return max(0, loc.distance(from: prev) / dt * 3.6)
        }

        // ⚠️ 这一帧"可信度不够"时**不能死守上一帧的值**：
        //    上市区/隧道/高架下，`speedAccuracy` 可能长期偏差，
        //    那就等于把正在跑的车速**永久冻住**（上一版就是这么写的，
        //    初值是 0 ⇒ 会一直显示 0 km/h，比噪声更糟）。
        //    正确做法：退到"位移推算值"，它照样能反映真实速度。
        if loc.speed < 0
            || loc.speedAccuracy < 0 || loc.speedAccuracy > 3.0
            || loc.horizontalAccuracy < 0 || loc.horizontalAccuracy > 50 {
            let fallback = derivedKmh() ?? lastGoodSpeedKmh
            lastGoodSpeedKmh = fallback
            return fallback
        }

        var kmh = loc.speed * 3.6
        // 芯片值比位移推算值大出 18km/h（5 m/s）以上 ⇒ 采信位移推算值
        //（"没动"那种情况已经在上面 stationary 分支里处理掉了）
        if let d = derivedKmh(), kmh > d + 18 {
            kmh = d
        }
        if kmh < 0 { kmh = 0 }
        lastGoodSpeedKmh = kmh
        return kmh
    }

    func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        // 定位失败时保持上一次数据，不影响仪表显示
    }
}
