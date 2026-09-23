import SwiftUI
import CoreLocation

#if canImport(MAMapKit)
import MAMapKit
import AMapFoundationKit
#endif

// MARK: - 高德配置

/// 高德开放平台的配置。
///
/// ⚠️ 这两个 Key 绑定的是**这个 App**：
///     Bundle ID = `com.cardash.dashboard`
/// 重新签名打包时 Bundle ID 必须保持一致，否则高德会在运行时拒绝。
/// （建议顺手去控制台给 iOS Key 绑定上 Bundle ID，防止被别人刷配额。）
enum AMapConfig {

    /// iOS 平台 Key —— 地图渲染用
    static let iOSKey = "ae986c05ca592da903ea68d0778c54b9"

    /// Web 服务 Key —— 路径规划用。
    /// 高德把「地图渲染」和「数据服务」拆成不同平台的 Key，
    /// iOS 平台的 Key 拿去调算路接口是过不了的，必须分开用。
    static let webKey = "14f2efc030e5992fe6c030b6277d6912"

    /// 用户有没有看过隐私说明。首次启动弹一次。
    static var privacyAsked: Bool {
        get { UserDefaults.standard.bool(forKey: "amapPrivacyAsked") }
        set { UserDefaults.standard.set(newValue, forKey: "amapPrivacyAsked") }
    }

    /// 用户有没有同意高德 SDK 的隐私协议。
    ///
    /// ⚠️ 这是**高德强制要求**的：没同意就去 new MAMapView，
    /// 会直接拿到 nil（地图一片空白）。所以没同意时我们退回栅格地图。
    static var privacyAgreed: Bool {
        get { UserDefaults.standard.bool(forKey: "amapPrivacyAgreed") }
        set { UserDefaults.standard.set(newValue, forKey: "amapPrivacyAgreed") }
    }
}

/// 首次启动的隐私说明。同意了才启用高德矢量地图。
struct PrivacyConsentView: View {
    let onDecide: (Bool) -> Void

    var body: some View {
        NavigationView {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    Text("关于地图服务")
                        .font(.headline)

                    Text("本应用使用**高德地图开放平台**提供地图显示与路线规划服务。\n\n"
                         + "启用后会向高德服务器请求地图瓦片、路径规划等数据，"
                         + "其中包含你当前的定位信息（用于把地图居中到你所在的位置）。")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)

                    Text("你可以随时在设置里改这个选择。不同意也能用，"
                         + "地图会自动退回备用样式（路网信息会少一些）。")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
                .padding()
            }
            .navigationTitle("启用高德地图？")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("暂不启用") { onDecide(false) }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("同意并启用") { onDecide(true) }
                }
            }
        }
        .navigationViewStyle(.stack)
        .interactiveDismissDisabled()
    }
}

/// 视距相关的小工具。
/// 放在 #if canImport(MAMapKit) **外面** —— DashboardView 也要用它，
/// 不能和高德 SDK 绑在一起（没装 SDK 时栅格兜底也要能编译）。
enum MapZoom {
    static let minLevel = 12
    static let maxLevel = 19

    static func clamp(_ z: Int) -> Int { min(maxLevel, max(minLevel, z)) }

    /// 自动比例的加减档（高德导航那种「快了拉远、慢了拉近」）。
    /// ⚠️ 差值由 DashboardView 直接写回 zoom，zoom 本身就是最终档位 ——
    /// 之前「基准+偏移」两套数打架，顶到 19 之后按 − 看不出变化，
    /// 用户就以为按钮没反应（实测确实如此：25/50/100 都顶在 19）。
    static func autoOffset(_ speedKmh: Double?) -> Int {
        guard let s = speedKmh, s.isFinite else { return 0 }
        switch s {
        case ..<5:   return 2      // 停着/刚起步：拉近看清楚
        case 5..<20: return 1
        case 20..<45: return 0
        case 45..<75: return -1
        default:      return -2    // 上了快速路：拉远看远一点
        }
    }
}

// MARK: - 高德矢量地图

#if canImport(MAMapKit)

/// 高德矢量地图（首选）。
///
/// 和参考图同源：矢量渲染、**3D 建筑**、**实时路况**、官方的**夜景样式**。
/// 之前那套栅格瓦片做不到 3D 建筑，只能靠反相硬凑深色 —— 这个是正解。
///
/// 坐标说明：MAMapView **原生就是 GCJ-02**，所以喂进来的 WGS-84
/// 一定要先转（用 ChinaCoord），否则整体偏几百米。
struct AMapNavView: UIViewRepresentable {

    let coord: CLLocationCoordinate2D?      // WGS-84
    let heading: Double
    let route: [CLLocationCoordinate2D]     // WGS-84
    let dest: CLLocationCoordinate2D?       // WGS-84
    /// 按路况分段的路线（高德 tmcs 的 status），用来画绿/黄/红
    let segments: [RouteSegment]
    /// 视距（缩放级别）。高德官方 API：zoomLevel，范围 3~20。
    /// 越大越近。16≈200米、17≈100米、18≈50米、19≈25米。
    let zoom: Int
    /// 本机 GPS 车速，用来自动调视距（高德导航那种「快了拉远、慢了拉近」）
    let speed: Double?

    func makeUIView(context: Context) -> MAMapView {
        // ⚠️ 顺序不能变：apiKey → 隐私合规 → 才能 new MAMapView
        AMapServices.shared().apiKey = AMapConfig.iOSKey
        AMapServices.shared().enableHTTPS = true
        MAMapView.updatePrivacyShow(AMapPrivacyShowStatus.didShow,
                                    privacyInfo: AMapPrivacyInfoStatus.didContain)
        MAMapView.updatePrivacyAgree(AMapPrivacyAgreeStatus.didAgree)

        let v = MAMapView(frame: .zero)
        v.delegate = context.coordinator
        // 官方夜景样式 —— 不用再自己反相了
        v.mapType = .standardNight
        // 实时路况（参考图里那些红黄绿的路段）
        v.isShowTraffic = true
        v.showsUserLocation = false
        v.showsCompass = false
        v.showsScale = false
        // 仪表盘背景，不接受手势
        v.isRotateEnabled = false
        v.isScrollEnabled = false
        v.isZoomEnabled = false
        v.isUserInteractionEnabled = false
        v.zoomLevel = Double(zoom)
        return v
    }

    func updateUIView(_ v: MAMapView, context: Context) {
        context.coordinator.update(view: v,
                                   coord: coord,
                                   heading: heading,
                                   route: route,
                                   dest: dest,
                                   segments: segments,
                                   zoom: zoom,
                                   speed: speed)
    }

    func makeCoordinator() -> Coordinator { Coordinator() }

    final class Coordinator: NSObject, MAMapViewDelegate {

        private var line: MAPolyline?
        private var lineBorder: MAPolyline?
        private var segLines: [MAPolyline] = []
        private var segStatus: [Int] = []
        private var routeCount = -1
        private var segKey = ""
        private var pin: MAPointAnnotation?
        private var lastKey: String?
        private var lastDestKey: String?
        private var lastZoom: Int = -1
        /// 车标箭头（高德那种蓝色导航箭头），一直钉在车辆位置上
        private var carPin: MAPointAnnotation?

        func update(view: MAMapView,
                    coord: CLLocationCoordinate2D?,
                    heading: Double,
                    route: [CLLocationCoordinate2D],
                    dest: CLLocationCoordinate2D?,
                    segments: [RouteSegment],
                    zoom: Int,
                    speed: Double?) {

            // 视距 = 用户基准 + 按车速自动加减档。
            // 只有目标变了才动，别每帧重置，否则 +/− 没效果。
            // zoom 就是最终档位（自动档的差值由 DashboardView 写回），
            // 这里只负责夹在范围内。只有目标变了才动，别每帧重置。
            let target = MapZoom.clamp(zoom)
            if target != lastZoom {
                lastZoom = target
                // ⚠️ 高德这个 setZoomLevel(_:animated:) 参数是 CGFloat，
                // 但 zoomLevel 属性又是 double，两个类型不一致，得显式转
                view.setZoomLevel(CGFloat(target), animated: true)
            }

            guard let raw = coord else { return }
            let c = ChinaCoord.toGcj(raw)
            let key = "\(c.latitude),\(c.longitude),\(Int(heading))"
            if key != lastKey {
                lastKey = key
                view.centerCoordinate = c
                view.rotationDegree = CGFloat(heading)   // 车头朝上
                // 俯角交给动画，免得每秒硬跳一次看着卡
                UIView.animate(withDuration: 0.9, delay: 0,
                               options: [.curveLinear, .beginFromCurrentState]) {
                    view.cameraDegree = 55
                }
            }

            let segHash = "\(route.count)|\(segments.map { String($0.status) }.joined())"
            if route.count != routeCount || segHash != segKey {
                routeCount = route.count
                segKey = segHash
                var olds: [MAPolyline] = [line, lineBorder].compactMap { $0 }
                olds.append(contentsOf: segLines)
                for o in olds { view.remove(o) }
                line = nil
                lineBorder = nil
                segLines = []
                segStatus = []

                if route.count >= 2 {
                    // 高德算路返回的就是 GCJ-02，但我们内部统一存 WGS-84，
                    // 所以这里还是要转一道
                    let pts = route.map { p -> CLLocationCoordinate2D in ChinaCoord.toGcj(p) }

                    // 1) 白底（最下层）—— 高德导航那条路的白边
                    var b = pts
                    let border = MAPolyline(coordinates: &b, count: UInt(pts.count))
                    lineBorder = border
                    view.add(border)

                    // 2) 按路况分段着色（绿/黄/红），高德 tmcs 的 status
                    for seg in segments {
                        let g = seg.points.map { p -> CLLocationCoordinate2D in ChinaCoord.toGcj(p) }
                        guard g.count >= 2 else { continue }
                        var c = g
                        let l = MAPolyline(coordinates: &c, count: UInt(g.count))
                        segLines.append(l!)
                        segStatus.append(seg.status)
                        view.add(l)
                    }

                    // 3) 万一分段是空的（接口没给 tmcs），至少画一根主路线
                    if segLines.isEmpty {
                        var coords = pts
                        let l = MAPolyline(coordinates: &coords, count: UInt(pts.count))
                        line = l
                        view.add(l)
                    }
                }
            }

            // ── 车标箭头：一直钉在车辆位置上 ──
            // 地图本身已经车头朝上（rotationDegree = heading），
            // 所以箭头固定朝屏幕上方就是「始终朝前」，不用自己转。
            if carPin == nil {
                let a = MAPointAnnotation()
                a.coordinate = c
                carPin = a
                view.addAnnotation(a)
            } else {
                carPin?.coordinate = c
            }

            let dkey = dest.map { "\($0.latitude),\($0.longitude)" } ?? ""
            if dkey != lastDestKey {
                lastDestKey = dkey
                if let old = pin { view.removeAnnotation(old); pin = nil }
                if let d = dest {
                    let a = MAPointAnnotation()
                    a.coordinate = ChinaCoord.toGcj(d)
                    a.title = "目的地"
                    pin = a
                    view.addAnnotation(a)
                }
            }
        }

        func mapView(_ mapView: MAMapView!, rendererFor overlay: MAOverlay!) -> MAOverlayRenderer! {
            guard let l = overlay as? MAPolyline else { return nil }
            let r = MAPolylineRenderer(polyline: l)

            if l === lineBorder {
                // 白边
                r?.strokeColor = UIColor(red: 0.93, green: 0.97, blue: 1.0, alpha: 0.95)
                r?.lineWidth = 15
                return r
            }

            if let idx = segLines.firstIndex(where: { $0 === l }) {
                // 高德路况配色（绿=畅通 黄=缓行 红=拥堵 暗红=严重拥堵）
                let c: UIColor
                switch segStatus[idx] {
                case 1:  c = UIColor(red: 1.00, green: 0.72, blue: 0.00, alpha: 1.0)
                case 2:  c = UIColor(red: 1.00, green: 0.38, blue: 0.16, alpha: 1.0)
                case 3:  c = UIColor(red: 0.75, green: 0.05, blue: 0.05, alpha: 1.0)
                default: c = UIColor(red: 0.10, green: 0.75, blue: 0.42, alpha: 1.0)
                }
                r?.strokeColor = c
                r?.lineWidth = 11
                r?.is3DArrowLine = true
                r?.sideColor = UIColor(red: 0.93, green: 0.97, blue: 1.0, alpha: 0.95)
                return r
            }

            // 兜底主路线
            r?.strokeColor = UIColor(red: 0.18, green: 0.58, blue: 1.0, alpha: 0.98)
            r?.lineWidth = 11
            r?.is3DArrowLine = true
            r?.sideColor = UIColor(red: 0.93, green: 0.97, blue: 1.0, alpha: 0.95)
            return r
        }

        func mapView(_ mapView: MAMapView!, viewFor annotation: MAAnnotation!) -> MAAnnotationView! {
            guard !(annotation is MAUserLocation) else { return nil }

            // 车标：高德那种蓝色导航箭头
            if annotation === carPin {
                let id = "car"
                var v = mapView.dequeueReusableAnnotationView(withIdentifier: id)
                if v == nil {
                    v = MAAnnotationView(annotation: annotation, reuseIdentifier: id)
                }
                v?.annotation = annotation
                let cfg = UIImage.SymbolConfiguration(pointSize: 30, weight: .bold)
                v?.image = UIImage(systemName: "location.north.fill",
                                   withConfiguration: cfg)?
                    .withTintColor(UIColor(red: 0.10, green: 0.52, blue: 1.0, alpha: 1.0),
                                  renderingMode: .alwaysOriginal)
                v?.centerOffset = .zero
                return v
            }

            let id = "dest"
            var v = mapView.dequeueReusableAnnotationView(withIdentifier: id)
            if v == nil {
                v = MAAnnotationView(annotation: annotation, reuseIdentifier: id)
            }
            v?.annotation = annotation
            v?.image = UIImage(systemName: "mappin.circle.fill")?
                .withTintColor(.systemRed, renderingMode: .alwaysOriginal)
            v?.centerOffset = CGPoint(x: 0, y: -10)
            return v
        }
    }
}

#endif

// MARK: - 地图选择器

/// 地图选择器。
///
/// 优先用高德矢量地图（和车机参考图同源：3D 建筑、实时路况、官方夜景样式）。
/// 两种情况退回栅格瓦片版：
///   1. 用户没同意高德 SDK 的隐私协议 —— 高德强制要求同意后才能 new MAMapView
///   2. 编译时压根没装高德 SDK（pod install 没跑）—— 用 canImport 兜住，
///      保证工程在任何情况下都编得过，不会因为一个依赖把整个 App 卡死
struct DashboardMapView: View {
    let coord: CLLocationCoordinate2D?
    let heading: Double
    let route: [CLLocationCoordinate2D]
    let dest: CLLocationCoordinate2D?
    let amapAgreed: Bool
    let useRasterFallback: Bool
    let zoom: Int
    let speed: Double?
    let segments: [RouteSegment]

    var body: some View {
        #if canImport(MAMapKit)
        if amapAgreed {
            AMapNavView(coord: coord, heading: heading, route: route, dest: dest,
                        segments: segments, zoom: zoom, speed: speed)
        } else {
            NavMapView(coord: coord, heading: heading, route: route,
                       dest: dest, useAmapTiles: useRasterFallback)
        }
        #else
        NavMapView(coord: coord, heading: heading, route: route,
                   dest: dest, useAmapTiles: useRasterFallback)
        #endif
    }
}
