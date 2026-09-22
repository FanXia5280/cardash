import Foundation
import CoreLocation

/// 本机 GPS：车速、海拔、累计里程。作为车机数据不可用时的兜底来源。
final class LocalSensors: NSObject, CLLocationManagerDelegate {

    var onUpdate: ((Double?, Double?, Double) -> Void)?
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
        manager.allowsBackgroundLocationUpdates = false
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

        let speedKmh = loc.speed >= 0 ? loc.speed * 3.6 : nil
        let altitude = loc.verticalAccuracy > 0 ? loc.altitude : nil
        onUpdate?(speedKmh, altitude, meters / 1000.0)
    }

    func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        // 定位失败时保持上一次数据，不影响仪表显示
    }
}
