import SwiftUI
import UIKit
import CoreLocation

#if canImport(AMapNaviKit)
import AMapNaviKit
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
/// 放在 #if canImport(AMapNaviKit) **外面** —— DashboardView 也要用它，
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

// MARK: - 日夜模式

/// 日夜模式判定。
///
/// 两套地图各走各的机制：
///   - **导航视图**（AMapNaviDriveView）：SDK 自己按**日出日落**切，
///     我们只要把 mapViewModeType 设成 2 = DayNightAuto 就行（见 NaviKitView）。
///   - **普通矢量地图**（MAMapView）：没有自动档，只有 mapType 两套样式
///     （.standard 白天 / .standardNight 夜间），所以这里用**本地时间窗**判，
///     作为统一兜底。
///
/// 时间窗取 6:30 ~ 19:00（成都 9 月约 6:50 日出 / 19:30 日落，够用）。
/// 想调就在下面两行改。
///
/// ⚠️ 2026-09-23 的背景：用户反馈「地图只有白色，晚上也刺眼，要自动切」。
/// 之前两处都是写死的白天样式（MAMapView 那处当时是特意改回白天的，
/// 现在自相矛盾了 —— 所以这次统一改成自动）。
enum DayNight {
    static let dayStartHour = 6.5
    static let dayEndHour = 19.0

    /// 现在算不算「晚上」
    static func isNight(_ date: Date = Date(), calendar: Calendar = .current) -> Bool {
        let c = calendar.dateComponents([.hour, .minute], from: date)
        let h = Double(c.hour ?? 12) + Double(c.minute ?? 0) / 60.0
        return h < dayStartHour || h >= dayEndHour
    }
}

// MARK: - 车头在屏幕上的位置（导航态 / 非导航态共用）

/// 地图里「车头 / 当前位置」该落在屏幕的哪儿（0~1，(0,0) 左上、(1,1) 右下）。
///
///   - **横屏**：往右挪 —— 左边是大号车速数字（参考图也是车顶偏右，左半屏留给 HUD）
///   - **竖屏**：往下挪 —— 车速数字就在车头正上方，居中的话会压住车头前的路线
///
/// ⚠️ **导航态（AMapNaviDriveView.screenAnchor）和非导航态（AMapNavView 自己反算偏移）
/// 必须用这一组值** —— 用户明确要求两种状态的位置同步，别再各写一份。
enum MapAnchor {
    static let landscape = CGPoint(x: 0.64, y: 0.50)
    static let portrait  = CGPoint(x: 0.50, y: 0.66)

    static func of(size: CGSize) -> CGPoint {
        size.height > size.width ? portrait : landscape
    }
}

// MARK: - 高德 SDK 自带图标

/// 从高德 SDK 的资源包里取官方图标。
///
/// 为什么要偷它的图：导航态那个自车图标（蓝圈 + 白箭头）是导航引擎内部渲染的，
/// 非导航态用的是 MAMapView + 自己维护的 Annotation（这是官方文档给巡航 UI 的方案），
/// 想让两边的车标长得一样，只能用 SDK 自带的同一张图 —— 它就在 AMapNavi.bundle 里。
///
/// 取不到就返回 nil，调用方退回自绘箭头，**不会崩**。
enum AmapBundle {

    /// 官方自车图标（导航态用的那张 256px 图，缩到 42pt）
    static let carIcon: UIImage? = scaled("engine/eyrieImage/compass_car_256@3x.webp", to: 42)

    private static func scaled(_ rel: String, to side: CGFloat) -> UIImage? {
        for base in basePaths() {
            let p = base + "/AMapNavi.bundle/" + rel
            if let img = UIImage(contentsOfFile: p) {
                let t = CGSize(width: side, height: side)
                let fmt = UIGraphicsImageRendererFormat.default()
                fmt.opaque = false
                return UIGraphicsImageRenderer(size: t, format: fmt).image { _ in
                    img.draw(in: CGRect(origin: .zero, size: t))
                }
            }
        }
        return nil
    }

    /// CocoaPods 用 use_frameworks! 时资源在 App 里的 AMapNaviKit.framework 内；
    /// 有的集成方式会把它拷到 App 根目录 —— 两种都试一遍。
    private static func basePaths() -> [String] {
        var out: [String] = []
        if let fw = Bundle.main.privateFrameworksPath {
            out.append(fw + "/AMapNaviKit.framework")
        }
        out.append(Bundle.main.bundlePath)
        return out
    }
}

// MARK: - 高德矢量地图

#if canImport(AMapNaviKit)

/// 高德矢量地图（首选）。
///
/// 和参考图同源：矢量渲染、**3D 建筑**、**实时路况**、官方的**夜景样式**。
/// 之前那套栅格瓦片做不到 3D 建筑，只能靠反相硬凑深色 —— 这个是正解。
///
/// 坐标说明：MAMapView **原生就是 GCJ-02**，所以喂进来的 WGS-84
/// 一定要先转（用 ChinaCoord），否则整体偏几百米。
/// 带布局回调的 MAMapView：屏幕旋转 / 尺寸变化时**立刻**重算车头位置。
///
/// 和导航态那个 `AnchoredDriveView` 同一个原因：SwiftUI 在横竖屏切换时
/// **不一定**会调 `updateUIView`（输入没变），但视图 bounds 已经变了 ⇒
/// 车头会停在旧位置，用户看到的就是「切过来先错位、要等一会才归位」。
final class AnchoredMapView: MAMapView {
    var onLayout: (() -> Void)?
    override func layoutSubviews() {
        super.layoutSubviews()
        onLayout?()
    }
}

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

        let v = AnchoredMapView(frame: .zero)
        v.delegate = context.coordinator
        // ⚠️ 用**导航样式**（MAMapTypeNavi / MAMapTypeNaviNight），不是普通样式：
        // 非导航态要和导航态（AMapNaviDriveView）看起来一样，底图配色得同一套
        //（普通夜间样式是蓝紫底 + 艳丽 POI 图标，和导航态那种灰黑底差很远）。
        // 白天/夜间仍然跟着时间自动切（见 DayNight）。
        v.mapType = DayNight.isNight() ? .naviNight : .navi
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
        // 旋转时立刻重摆车头位置（见 AnchoredMapView 的说明）
        v.onLayout = { [weak v, weak co = context.coordinator] in
            guard let v else { return }
            co?.applyCamera(v)
        }
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
        /// 最近一次的位置（GCJ-02）和车头朝向 —— 屏幕旋转时要拿它重算相机
        private var lastCoord: CLLocationCoordinate2D?
        private var lastHeading: Double = 0

        /// 把相机摆成「车落在 `MapAnchor` 指定位置」的样子。
        ///
        /// 除了定位更新，**每次 layout 也会调**（横竖屏切换 / 尺寸变化），
        /// 所以它只能依赖 lastCoord / lastHeading，不能依赖"这次传进来的新位置"。
        func applyCamera(_ view: MAMapView) {
            guard let c = lastCoord else { return }
            // MAMapView 没有"自车图标位置"这种能力（它的 screenAnchor 是**缩放**
            // 锚点，不是内容锚点），所以自己反算：先把中心放到车上，
            // 再看「想让车出现的那个屏幕点」现在压着哪个坐标，把中心平移过去。
            //
            // ⚠️ 千万别退回 1.6.10 那种「把地图视图画大再 offset」的 hack：
            // SwiftUI 会把超大的子视图摆在父视图**左上角**，于是左/上露出一条
            // 地图没盖住的黑边（用户截图里说的「黑的断层」），落点也是错的。
            view.centerCoordinate = c
            let w = view.bounds.width
            let h = view.bounds.height
            if w > 1, h > 1 {
                let a = MapAnchor.of(size: view.bounds.size)
                let at = view.convert(CGPoint(x: w * a.x, y: h * a.y),
                                      toCoordinateFrom: view)
                let dLat = c.latitude - at.latitude
                let dLon = c.longitude - at.longitude
                // 偏移量必须小得合理 —— 地图还没渲染好时 convert 可能返回垃圾
                if abs(dLat) < 0.02, abs(dLon) < 0.02 {
                    view.centerCoordinate = CLLocationCoordinate2D(
                        latitude: c.latitude + dLat,
                        longitude: c.longitude + dLon)
                }
            }
            view.rotationDegree = CGFloat(lastHeading)   // 车头朝上（和导航态一致）
        }

        func update(view: MAMapView,
                    coord: CLLocationCoordinate2D?,
                    heading: Double,
                    route: [CLLocationCoordinate2D],
                    dest: CLLocationCoordinate2D?,
                    segments: [RouteSegment],
                    zoom: Int,
                    speed: Double?) {

            // 日夜模式：过点（傍晚天黑了 / 早上天亮了）就换样式，不用重启 App。
            // 用**导航样式**，和导航态底图一致（见 makeUIView 里的说明）
            let wantType: MAMapType = DayNight.isNight() ? .naviNight : .navi
            if view.mapType != wantType {
                view.mapType = wantType
            }

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
            lastCoord = c
            lastHeading = heading
            let key = "\(c.latitude),\(c.longitude),\(Int(heading))"
            if key != lastKey {
                lastKey = key
                applyCamera(view)
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
                var olds: [MAPolyline] = [line].compactMap { $0 }
                olds.append(contentsOf: segLines)
                for o in olds { view.remove(o) }
                line = nil
                segLines = []
                segStatus = []

                if route.count >= 2 {
                    // 高德算路返回的就是 GCJ-02，但我们内部统一存 WGS-84，
                    // 所以这里还是要转一道
                    let pts = route.map { p -> CLLocationCoordinate2D in ChinaCoord.toGcj(p) }

                    // 按路况分段着色（绿/黄/红），高德 tmcs 的 status。
                    // ⚠️ 不画白底！之前白底线和彩线是同层 overlay，
                    // 高德的渲染顺序不保证，白线可能盖在彩线上面 ——
                    // 整条路就成了白色、只剩两条绿边（上一版正是这样）。
                    // 高德原版就是单层 is3DArrowLine：彩色主体 + 白色路缘。
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

            // 高德原版路线 = 彩色粗主体 + 白色路缘 + 路面箭头。
            // is3DArrowLine 的含义：strokeColor 是主体色，sideColor 是两侧路缘，
            // 箭头纹理是 SDK 自动印上去的（浅色，和高德一致）。
            // 全部单层，绝不叠第二根线。
            let main: UIColor
            if let idx = segLines.firstIndex(where: { $0 === l }) {
                // 高德路况配色：绿=畅通 黄=缓行 红=拥堵 暗红=严重拥堵
                switch segStatus[idx] {
                case 1:  main = UIColor(red: 1.00, green: 0.70, blue: 0.00, alpha: 1.0)
                case 2:  main = UIColor(red: 1.00, green: 0.32, blue: 0.10, alpha: 1.0)
                case 3:  main = UIColor(red: 0.76, green: 0.05, blue: 0.05, alpha: 1.0)
                default: main = UIColor(red: 0.00, green: 0.76, blue: 0.35, alpha: 1.0)
                }
            } else {
                main = UIColor(red: 0.16, green: 0.56, blue: 1.0, alpha: 1.0)   // 兜底蓝
            }
            r?.strokeColor = main
            r?.lineWidth = 20
            r?.is3DArrowLine = true
            r?.sideColor = UIColor(red: 0.97, green: 1.0, blue: 1.0, alpha: 1.0)
            return r
        }

        func mapView(_ mapView: MAMapView!, viewFor annotation: MAAnnotation!) -> MAAnnotationView! {
            guard !(annotation is MAUserLocation) else { return nil }

            // 车标：优先用**高德官方那张**（和导航态同一个图标），取不到才自绘
            if annotation === carPin {
                let id = "car"
                var v = mapView.dequeueReusableAnnotationView(withIdentifier: id)
                if v == nil {
                    v = MAAnnotationView(annotation: annotation, reuseIdentifier: id)
                }
                v?.annotation = annotation
                if let official = AmapBundle.carIcon {
                    v?.image = official
                } else {
                    let cfg = UIImage.SymbolConfiguration(pointSize: 30, weight: .bold)
                    v?.image = UIImage(systemName: "location.north.fill",
                                       withConfiguration: cfg)?
                        .withTintColor(UIColor(red: 0.10, green: 0.52, blue: 1.0, alpha: 1.0),
                                      renderingMode: .alwaysOriginal)
                }
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
        #if canImport(AMapNaviKit)
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
