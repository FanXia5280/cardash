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

        // loc.speed 为负表示这一帧速度无效（刚定位到、或静止时常见）。
        // 之前直接给 nil，仪表就显示 "--"；可只要定位有效，静止就是 0 km/h，
        // 显示 0 才对，显示 "--" 反而像坏了。
        let speedKmh = loc.speed >= 0 ? loc.speed * 3.6 : 0
        let altitude = loc.verticalAccuracy > 0 ? loc.altitude : nil
        onUpdate?(speedKmh, altitude, meters / 1000.0)
        onFix?(loc)
    }

    func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        // 定位失败时保持上一次数据，不影响仪表显示
    }
}
