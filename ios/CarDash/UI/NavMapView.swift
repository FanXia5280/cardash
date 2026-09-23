import SwiftUI
import MapKit
import CoreLocation

// MARK: - 火星坐标（GCJ-02）转换

/// 国内地图坐标转换。
///
/// 高德的瓦片是 **GCJ-02（火星坐标）**，而 iPhone 的 GPS 和 Apple 的路线数据是
/// **WGS-84**。两者在国内差 300~600 米 —— 直接把 GPS 坐标丢给高德瓦片，
/// 车标就会整体偏到隔壁街上去。
///
/// 所以：显示给地图的一律转成 GCJ-02，交给系统算路的一律转回 WGS-84。
enum ChinaCoord {

    private static let a = 6378245.0
    private static let ee = 0.00669342162296594323

    private static func outOfChina(_ lat: Double, _ lon: Double) -> Bool {
        !(lon > 73.66 && lon < 135.05 && lat > 3.86 && lat < 53.55)
    }

    private static func dLat(_ x: Double, _ y: Double) -> Double {
        var r = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * sqrt(abs(x))
        r += (20.0 * sin(6.0 * x * .pi) + 20.0 * sin(2.0 * x * .pi)) * 2.0 / 3.0
        r += (20.0 * sin(y * .pi) + 40.0 * sin(y / 3.0 * .pi)) * 2.0 / 3.0
        r += (160.0 * sin(y / 12.0 * .pi) + 320.0 * sin(y * .pi / 30.0)) * 2.0 / 3.0
        return r
    }

    private static func dLon(_ x: Double, _ y: Double) -> Double {
        var r = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * sqrt(abs(x))
        r += (20.0 * sin(6.0 * x * .pi) + 20.0 * sin(2.0 * x * .pi)) * 2.0 / 3.0
        r += (20.0 * sin(x * .pi) + 40.0 * sin(x / 3.0 * .pi)) * 2.0 / 3.0
        r += (150.0 * sin(x / 12.0 * .pi) + 300.0 * sin(x / 30.0 * .pi)) * 2.0 / 3.0
        return r
    }

    /// WGS-84 → GCJ-02
    static func toGcj(_ c: CLLocationCoordinate2D) -> CLLocationCoordinate2D {
        if outOfChina(c.latitude, c.longitude) { return c }
        let x = c.longitude - 105.0
        let y = c.latitude - 35.0
        let rLat = c.latitude / 180.0 * .pi
        var magic = sin(rLat)
        magic = 1 - ee * magic * magic
        let sq = sqrt(magic)
        let la = (dLat(x, y) * 180.0) / ((a * (1 - ee)) / (magic * sq) * .pi)
        let lo = (dLon(x, y) * 180.0) / (a / sq * cos(rLat) * .pi)
        return CLLocationCoordinate2D(latitude: c.latitude + la,
                                      longitude: c.longitude + lo)
    }

    /// GCJ-02 → WGS-84（正向变换迭代逼近，三次足够到厘米级）
    static func toWgs(_ c: CLLocationCoordinate2D) -> CLLocationCoordinate2D {
        if outOfChina(c.latitude, c.longitude) { return c }
        var lat = c.latitude
        var lon = c.longitude
        for _ in 0..<3 {
            let g = toGcj(CLLocationCoordinate2D(latitude: lat, longitude: lon))
            lat += c.latitude - g.latitude
            lon += c.longitude - g.longitude
        }
        return CLLocationCoordinate2D(latitude: lat, longitude: lon)
    }
}

// MARK: - 高德瓦片

/// 高德的路网瓦片。
///
/// 走它公开的瓦片服务（`wprd0N.is.autonavi.com`），**不需要 SDK、不需要 key**，
/// 所以能直接塞进 MapKit 的 `MKTileOverlay`，CI 上也不用装任何依赖。
/// 拿到的就是高德那套配色和路网，视觉上和车机一致。
final class AmapTileOverlay: MKTileOverlay {

    init() {
        super.init(urlTemplate: nil)
        // 坐标系是 GCJ-02，调用方负责把坐标转过去
        canReplaceMapContent = true
        tileSize = CGSize(width: 256, height: 256)
        minimumZ = 3
        maximumZ = 19
    }

    override func url(forTilePath path: MKTileOverlayPath) -> URL {
        // 轮换四个 CDN 节点，免得单节点限速
        let host = "wprd0\((abs(path.x + path.y + path.z) % 4) + 1).is.autonavi.com"
        let s = "https://\(host)/appmaptile?lang=zh_cn&size=1&scl=1&style=7"
            + "&x=\(path.x)&y=\(path.y)&z=\(path.z)"
        return URL(string: s) ?? URL(string: "https://wprd01.is.autonavi.com")!
    }
}

// MARK: - 地图视图

/// 导航地图。
///
/// 视觉对齐参考图：
///   - 高德底图（深色路网），车头朝上跟随
///   - 车机报目的地时自动算一条路线画上去（蓝色）
///   - 相机用短动画跟随，不是每秒硬跳一次 —— 那样看着就是「卡」
///
/// 坐标说明：喂进来的一律是 **WGS-84**（GPS 和 MKDirections 都是），
/// 内部转成 GCJ-02 再交给高德瓦片，否则整体偏移几百米。
struct NavMapView: UIViewRepresentable {

    let coord: CLLocationCoordinate2D?
    let heading: Double
    /// 路线（WGS-84）
    let route: [CLLocationCoordinate2D]
    /// 目的地（WGS-84）
    let dest: CLLocationCoordinate2D?
    let useAmapTiles: Bool

    func makeUIView(context: Context) -> MKMapView {
        let v = MKMapView()
        v.delegate = context.coordinator
        v.mapType = .standard
        v.pointOfInterestFilter = .excludingAll
        v.showsCompass = false
        v.showsScale = false
        v.showsUserLocation = false
        v.showsTraffic = false
        // 仪表盘背景，不接受任何手势
        v.isZoomEnabled = false
        v.isScrollEnabled = false
        v.isRotateEnabled = false
        v.isPitchEnabled = false
        v.isUserInteractionEnabled = false
        v.overrideUserInterfaceStyle = .dark
        return v
    }

    func updateUIView(_ v: MKMapView, context: Context) {
        context.coordinator.update(view: v,
                                   coord: coord,
                                   heading: heading,
                                   route: route,
                                   dest: dest,
                                   useAmapTiles: useAmapTiles)
    }

    func makeCoordinator() -> Coordinator { Coordinator() }

    final class Coordinator: NSObject, MKMapViewDelegate {

        private var drewAmap = false
        private var routeLine: MKPolyline?
        private var routeCount = -1
        private var destPin: MKPointAnnotation?
        private var lastCamera: (CLLocationCoordinate2D, Double)?
        private var lastDestKey: String?

        func update(view: MKMapView,
                    coord: CLLocationCoordinate2D?,
                    heading: Double,
                    route: [CLLocationCoordinate2D],
                    dest: CLLocationCoordinate2D?,
                    useAmapTiles: Bool) {

            // 底图切换
            if useAmapTiles != drewAmap {
                drewAmap = useAmapTiles
                view.removeOverlays(view.overlays.filter { $0 is AmapTileOverlay })
                if useAmapTiles {
                    view.addOverlay(AmapTileOverlay(), level: .aboveRoads)
                }
            }

            guard let raw = coord else { return }
            let c = useAmapTiles ? ChinaCoord.toGcj(raw) : raw

            // ── 相机：只有真的动了才更新，而且用短动画贴着走 ──
            // 原来每帧 setCamera(animated: false)，GPS 一秒一跳，看着就是卡。
            let moved = lastCamera == nil
                || abs(lastCamera!.0.latitude - c.latitude) > 1e-7
                || abs(lastCamera!.0.longitude - c.longitude) > 1e-7
                || abs(lastCamera!.1 - heading) > 2.0
            if moved {
                lastCamera = (c, heading)
                let cam = MKMapCamera(lookingAtCenter: c,
                                      fromDistance: 330,
                                      pitch: 50,
                                      heading: heading)
                UIView.animate(withDuration: 0.9,
                               delay: 0,
                               options: [.curveLinear, .beginFromCurrentState, .allowUserInteraction]) {
                    view.setCamera(cam, animated: false)
                }
            }

            // ── 路线 ──
            if route.count != routeCount {
                routeCount = route.count
                if let old = routeLine { view.removeOverlay(old); routeLine = nil }
                if route.count >= 2 {
                    let pts = useAmapTiles ? route.map(ChinaCoord.toGcj) : route
                    let p = MKPolyline(coordinates: pts, count: pts.count)
                    routeLine = p
                    // aboveRoads 会压在瓦片上面；瓦片本身也是 aboveRoads，
                    // 靠加入顺序决定层级，所以路线在瓦片之后添加就没问题
                    view.addOverlay(p, level: .aboveRoads)
                }
            }

            // ── 目的地标记 ──
            let dkey = dest.map { "\($0.latitude),\($0.longitude)" } ?? ""
            if dkey != lastDestKey {
                lastDestKey = dkey
                if let old = destPin { view.removeAnnotation(old); destPin = nil }
                if let d = dest {
                    let g = useAmapTiles ? ChinaCoord.toGcj(d) : d
                    let a = MKPointAnnotation()
                    a.coordinate = g
                    a.title = "目的地"
                    destPin = a
                    view.addAnnotation(a)
                }
            }
        }

        func mapView(_ mapView: MKMapView, rendererFor overlay: MKOverlay) -> MKOverlayRenderer {
            if let t = overlay as? AmapTileOverlay {
                return MKTileOverlayRenderer(tileOverlay: t)
            }
            if let p = overlay as? MKPolyline {
                let r = MKPolylineRenderer(polyline: p)
                // 高德路线那种蓝
                r.strokeColor = UIColor(red: 0.20, green: 0.55, blue: 1.0, alpha: 0.95)
                r.lineWidth = 9
                r.lineCap = .round
                r.lineJoin = .round
                return r
            }
            return MKOverlayRenderer(overlay: overlay)
        }

        func mapView(_ mapView: MKMapView, viewFor annotation: MKAnnotation) -> MKAnnotationView? {
            guard !(annotation is MKUserLocation) else { return nil }
            let id = "dest"
            let v = mapView.dequeueReusableAnnotationView(withIdentifier: id)
                ?? MKAnnotationView(annotation: annotation, reuseIdentifier: id)
            v.annotation = annotation
            v.image = UIImage(systemName: "mappin.circle.fill")?
                .withTintColor(.systemRed, renderingMode: .alwaysOriginal)
            v.centerOffset = CGPoint(x: 0, y: -10)
            return v
        }
    }
}

/// 地图还没定位到时显示的样子。
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
