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
    private var lastSize: CGSize = .zero

    override func layoutSubviews() {
        super.layoutSubviews()
        // ⚠️ 只在**尺寸真的变了**才回调：layoutSubviews 可能被 SDK 内部频繁触发，
        // 每次重算相机（convert + centerCoordinate）会跟地图渲染抢时间 → 看着"卡卡"。
        if bounds.size != lastSize {
            lastSize = bounds.size
            onLayout?()
        }
    }
}

/// 车辆位置标记：**苹果「查找」那种会呼吸的实心圆**（用户 2026-09-24 明确要求）。
///
/// 为什么不用 `MAAnnotationView.image`（一张静态图）：静态图动不起来。
/// 这里用三个 `CAShapeLayer` —— 外层光晕（呼吸）、中层白圈、里层实心圆 ——
/// 呼吸动效交给 Core Animation（跑在渲染服务上，**不吃主线程**，也就不影响地图帧率）。
///
/// ⚠️ 不要在子类里重写 `init(annotation:reuseIdentifier:)`：那是 ObjC 的初始化器，
/// 空值标注一写错就编译不过（这类坑本项目踩过好几次）。所以这里**不写 init**，
/// 用继承来的那个，再调一次 `prepare(online:)` 把三层建起来。
final class CarDotView: MAAnnotationView {

    private let halo = CAShapeLayer()
    private let ring = CAShapeLayer()
    private let core = CAShapeLayer()
    private var ready = false

    private static let side: CGFloat = 34
    private static let coreRadius: CGFloat = 9
    private static let ringRadius: CGFloat = 11.5
    private static let haloRadius: CGFloat = 15

    /// 建好三层并启动呼吸（只在第一帧做一次），之后每次只更新颜色。
    func prepare(online: Bool) {
        if !ready {
            ready = true
            frame = CGRect(x: 0, y: 0, width: Self.side, height: Self.side)
            backgroundColor = .clear
            layer.addSublayer(halo)
            layer.addSublayer(ring)
            layer.addSublayer(core)

            // ⚠️ 每一层都必须把 **bounds / position** 设对，再按自己的 bounds 画 path。
            // CAShapeLayer 的 bounds 和 position 默认都是 0 —— 那样 `transform.scale`
            // 会绕着**父层左上角**缩放，肉眼看到的就是「光晕在旁边呼吸、没对准圆心」
            //（用户 2026-09-24 实测反馈的就是这个）。
            // 设好 bounds（圆的外接矩形）+ position（视图中心），缩放才绕自己的圆心。
            func setup(_ l: CAShapeLayer, radius: CGFloat, fill: UIColor) {
                l.bounds = CGRect(x: 0, y: 0, width: radius * 2, height: radius * 2)
                l.position = CGPoint(x: Self.side / 2, y: Self.side / 2)
                l.path = UIBezierPath(ovalIn: l.bounds).cgPath
                l.fillColor = fill.cgColor
            }
            setup(halo, radius: Self.haloRadius, fill: .clear)   // 颜色由 setOnline 刷
            setup(ring, radius: Self.ringRadius, fill: .white)
            setup(core, radius: Self.coreRadius, fill: .clear)

            // 呼吸：光晕一边放大一边淡出，无限循环
            let scale = CABasicAnimation(keyPath: "transform.scale")
            scale.fromValue = 0.55
            scale.toValue = 1.15
            let fade = CABasicAnimation(keyPath: "opacity")
            fade.fromValue = 0.75
            fade.toValue = 0.0
            let g = CAAnimationGroup()
            g.animations = [scale, fade]
            g.duration = 1.8
            g.repeatCount = .infinity
            g.timingFunction = CAMediaTimingFunction(name: .easeOut)
            halo.add(g, forKey: "breathe")
        }
        setOnline(online)
    }

    /// 绿 = 在线（能取到当前位置）、灰 = 不在线
    func setOnline(_ online: Bool) {
        let base: UIColor = online ? .systemGreen : .systemGray
        core.fillColor = base.cgColor
        halo.fillColor = base.withAlphaComponent(0.30).cgColor
    }
}

struct AMapNavView: UIViewRepresentable {

    let coord: CLLocationCoordinate2D?      // WGS-84
    let heading: Double
    let dest: CLLocationCoordinate2D?       // WGS-84
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
            co?.reapplyCamera(v)
        }
        return v
    }

    func updateUIView(_ v: MAMapView, context: Context) {
        context.coordinator.update(view: v,
                                   coord: coord,
                                   heading: heading,
                                   dest: dest,
                                   zoom: zoom,
                                   speed: speed)
    }

    func makeCoordinator() -> Coordinator { Coordinator() }

    final class Coordinator: NSObject, MAMapViewDelegate {

        private var pin: MAPointAnnotation?
        private var lastKey: String?
        private var lastDestKey: String?
        private var lastZoom: Int = -1
        /// 车标箭头（高德那种蓝色导航箭头），一直钉在车辆位置上
        private var carPin: MAPointAnnotation?
        /// 最近一次的位置（GCJ-02）和车头朝向 —— 屏幕旋转时要拿它重算相机
        private var lastCoord: CLLocationCoordinate2D?
        private var lastHeading: Double = 0
        /// 能不能取到当前位置（true = 在线）。车标颜色用它：绿=在线、灰=不在线
        private var lastLocated = true
        /// 相机偏移没生效时的重试计数（见 reapplyCamera）
        private var cameraRetry = 0
        /// 上一次画车标用的在线状态。变了才换图 ——
        /// `viewFor` 不会为已经存在的标注再调一次，所以状态变了要手动换。
        private var carOnline: Bool?



        /// 把相机摆成「车落在 `MapAnchor` 指定位置」的样子。
        ///
        /// 除了定位更新，**每次 layout 也会调**（横竖屏切换 / 尺寸变化），
        /// 所以它只能依赖 lastCoord / lastHeading，不能依赖"这次传进来的新位置"。
        ///
        /// 返回值是**有没有真的偏移成功** —— 失败时调用方要重试（见 `reapplyCamera`）。
        @discardableResult
        func applyCamera(_ view: MAMapView) -> Bool {
            guard let c = lastCoord else { return false }
            // MAMapView 没有"自车图标位置"这种能力（它的 screenAnchor 是**缩放**
            // 锚点，不是内容锚点），所以自己反算：先把中心放到车上，
            // 再看「想让车出现的那个屏幕点」现在压着哪个坐标，把中心平移过去。
            //
            // ⚠️ 千万别退回 1.6.10 那种「把地图视图画大再 offset」的 hack：
            // SwiftUI 会把超大的子视图摆在父视图**左上角**，于是左/上露出一条
            // 地图没盖住的黑边（用户截图里说的「黑的断层」），落点也是错的。
            view.centerCoordinate = c
            view.rotationDegree = CGFloat(lastHeading)   // 车头朝上（和导航态一致）

            let w = view.bounds.width
            let h = view.bounds.height
            guard w > 1, h > 1 else { return false }

            let a = MapAnchor.of(size: view.bounds.size)
            let at = view.convert(CGPoint(x: w * a.x, y: h * a.y),
                                  toCoordinateFrom: view)
            let dLat = c.latitude - at.latitude
            let dLon = c.longitude - at.longitude
            // ⚠️ 地图还没渲染好时 `convert` 会返回垃圾值，偏移就不成立。
            //    这时**绝不能**就这么算了 —— 那车会停在屏幕正中，几秒后才落回
            //    该在的位置（用户 2026-09-24 截图实测，横竖屏都有）。
            //    返回 false，交给 reapplyCamera 排队重试。
            guard abs(dLat) < 0.02, abs(dLon) < 0.02 else { return false }
            view.centerCoordinate = CLLocationCoordinate2D(
                latitude: c.latitude + dLat,
                longitude: c.longitude + dLon)
            return true
        }

        /// 重摆相机；**没成功就排队重试**（地图渲染好之后一次就成）。
        ///
        /// 只在"相机偏移还没生效"时重试，最多 8 次、退避到 0.8s ——
        /// 正常情况下第一帧就成功，重试根本不会发生。
        func reapplyCamera(_ view: MAMapView) {
            if applyCamera(view) {
                cameraRetry = 0
                return
            }
            guard cameraRetry < 8 else { return }
            cameraRetry += 1
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.1 * Double(cameraRetry)) {
                [weak self, weak view] in
                guard let self, let view else { return }
                self.reapplyCamera(view)
            }
        }

        func update(view: MAMapView,
                    coord: CLLocationCoordinate2D?,
                    heading: Double,
                    dest: CLLocationCoordinate2D?,
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

            // ── 车标在线/离线（绿 / 灰）──
            // 取不到当前位置就画灰点，一眼能看出"GPS 没数据"，别让人以为车在这儿。
            lastLocated = (coord != nil)
            if carOnline != lastLocated {
                carOnline = lastLocated
                if let a = carPin, let v = view.view(for: a) as? CarDotView {
                    v.setOnline(lastLocated)
                }
            }

            guard let raw = coord else { return }
            let c = ChinaCoord.toGcj(raw)
            lastCoord = c
            lastHeading = heading
            let key = "\(c.latitude),\(c.longitude),\(Int(heading))"
            if key != lastKey {
                lastKey = key
                reapplyCamera(view)
                // 俯角交给动画，免得每秒硬跳一次看着卡
                UIView.animate(withDuration: 0.9, delay: 0,
                               options: [.curveLinear, .beginFromCurrentState]) {
                    view.cameraDegree = 55
                }
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
        func mapView(_ mapView: MAMapView!, viewFor annotation: MAAnnotation!) -> MAAnnotationView! {
            guard !(annotation is MAUserLocation) else { return nil }

            // 车标：用户 2026-09-24 要求照苹果「查找」那种**实心圆** ——
            // 绿 = 在线（能取到当前位置）、灰 = 不在线（取不到）。
            // 之前那个高德三角箭头太小，导航态又已经有原版 3D 车标，非导航态就用圆点。
            if annotation === carPin {
                let id = "car"
                var av = mapView.dequeueReusableAnnotationView(withIdentifier: id)
                if !(av is CarDotView) {
                    av = CarDotView(annotation: annotation, reuseIdentifier: id)
                }
                av?.annotation = annotation
                av?.centerOffset = .zero
                (av as? CarDotView)?.prepare(online: carOnline ?? true)
                return av
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
    let dest: CLLocationCoordinate2D?
    let amapAgreed: Bool
    let useRasterFallback: Bool
    let zoom: Int
    let speed: Double?

    var body: some View {
        #if canImport(AMapNaviKit)
        if amapAgreed {
            AMapNavView(coord: coord, heading: heading, dest: dest,
                        zoom: zoom, speed: speed)
        } else {
            NavMapView(coord: coord, heading: heading,
                       dest: dest, useAmapTiles: useRasterFallback)
        }
        #else
        NavMapView(coord: coord, heading: heading,
                   dest: dest, useAmapTiles: useRasterFallback)
        #endif
    }
}
