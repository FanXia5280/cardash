import SwiftUI
import MapKit
import CoreLocation

/// 导航地图背景。
///
/// 用系统自带的 MapKit —— 不用第三方 SDK、不用申请 key、不用联网授权，
/// 而且 iOS 13+ 支持深色地图样式。
///
/// 视觉上对齐参考图：
///   - 深色路网（mutedStandard + 强制 dark），去掉所有 POI 图标
///     和指南针/比例尺，画面干净，HUD 压上去才看得清
///   - **车头朝上**（heading-up）跟随车辆，带一点俯角，像车机那样
///   - 画一条已行驶轨迹，作为位置参照
///
/// ⚠️ 关于「和车机同步的路线」——
/// 高德的引导广播（KEY_TYPE=10001）只给**距离 / 转向图标 / 路名**，
/// 不给路线几何（没有坐标点串），所以画不出车机那条蓝色规划路线。
/// 这里画的是**已经开过的轨迹**。
/// 等 /logcat 里那段 extras dump 出来，如果高德确实广播了经纬度，再切过去。
struct NavMapView: UIViewRepresentable {

    /// 车辆当前位置（本机 GPS）
    let coord: CLLocationCoordinate2D?
    /// 车头方向（度）
    let heading: Double
    /// 已行驶轨迹
    let track: [CLLocationCoordinate2D]

    func makeUIView(context: Context) -> MKMapView {
        let v = MKMapView()
        v.delegate = context.coordinator
        v.mapType = .mutedStandard
        v.pointOfInterestFilter = .excludingAll
        v.showsCompass = false
        v.showsScale = false
        v.showsUserLocation = false
        v.showsTraffic = false
        v.showsBuildings = true
        // 仪表盘背景，不接受任何手势，免得误拖
        v.isZoomEnabled = false
        v.isScrollEnabled = false
        v.isRotateEnabled = false
        v.isPitchEnabled = false
        v.isUserInteractionEnabled = false
        // 深色地图
        v.overrideUserInterfaceStyle = .dark
        return v
    }

    func updateUIView(_ v: MKMapView, context: Context) {
        context.coordinator.update(view: v, coord: coord, heading: heading, track: track)
    }

    func makeCoordinator() -> Coordinator { Coordinator() }

    final class Coordinator: NSObject, MKMapViewDelegate {

        private var polyline: MKPolyline?
        private var drawnCount = 0

        func update(view: MKMapView,
                    coord: CLLocationCoordinate2D?,
                    heading: Double,
                    track: [CLLocationCoordinate2D]) {

            guard let c = coord else { return }

            // 相机：车头朝上、带俯角、贴近街道的高度
            let cam = MKMapCamera(lookingAtCenter: c,
                                  fromDistance: 340,
                                  pitch: 52,
                                  heading: heading)
            view.setCamera(cam, animated: false)

            // 轨迹只在点数变化时重画（每帧重画会白烧 CPU）
            if track.count >= 2, track.count != drawnCount {
                drawnCount = track.count
                if let old = polyline { view.removeOverlay(old) }
                let p = MKPolyline(coordinates: track, count: track.count)
                polyline = p
                view.addOverlay(p, level: .aboveRoads)
            }
        }

        func mapView(_ mapView: MKMapView, rendererFor overlay: MKOverlay) -> MKOverlayRenderer {
            guard let p = overlay as? MKPolyline else {
                return MKOverlayRenderer(overlay: overlay)
            }
            let r = MKPolylineRenderer(polyline: p)
            r.strokeColor = UIColor(red: 0.36, green: 0.63, blue: 1.0, alpha: 0.92)
            r.lineWidth = 8
            r.lineCap = .round
            r.lineJoin = .round
            return r
        }
    }
}

/// 地图还没定位到时显示的样子（保持和原来一致的动态背景）。
struct MapPlaceholder: View {
    let reason: String

    var body: some View {
        ZStack {
            BackgroundScene()
            VStack(spacing: 8) {
                Image(systemName: "location.slash")
                    .font(.system(size: 22, weight: .semibold))
                Text(reason)
                    .font(.system(size: 12, weight: .medium))
            }
            .foregroundStyle(.white.opacity(0.45))
        }
    }
}
