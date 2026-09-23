#if canImport(AMapNaviKit)
import SwiftUI
import UIKit
import CoreLocation
import AMapNaviKit

/// 背景地图 = 高德**官方导航引擎**（AMapNaviDriveView + AMapNaviDriveManager）。
///
/// ⚠️ 2026-09-23 深夜重构：**非导航状态也用这一套**（用户明确要求「别用两套系统」）。
///
/// 以前是两套：没有目的地时用 MAMapView（自建矢量地图 + 自绘车标），一有目的地才换成
/// AMapNaviDriveView。两套的问题（用户实测截图）：
///   - 底图配色不一样（MAMapTypeStandardNight vs 导航夜间样式）
///   - 车标不一样（我们画的箭头 vs 高德 3D 车标）
///   - 遮罩接缝不一样：非导航那套因为外面临时加了「把视图画大再偏移」的 hack，
///     左/上会露出一条**没被地图盖住的黑边**（用户说的「黑的断层」）
///   - 开始导航时还要换 view → 卡一下、闪一下北京
///
/// 现在：**永远用这一个 view**。没有目的地就让引擎进**巡航模式**
/// （`AMapNaviDriveManager.detectedMode`，文档里叫巡航），有目的地就正常算路导航。
/// 地图、车标、日夜、遮罩接缝、锚点全部天然一致，切换时也不用重建视图。
struct NaviKitNavView: UIViewRepresentable {
    let from: CLLocationCoordinate2D?     // WGS-84 当前位置
    let to: CLLocationCoordinate2D?       // WGS-84 目的地；**nil = 巡航（不带路线）**

    func makeUIView(context: Context) -> AMapNaviDriveView {
        // Key 和隐私接口在 App 启动时已经设置过；这里再设一遍是幂等的
        AMapServices.shared().apiKey = AMapConfig.iOSKey
        AMapServices.shared().enableHTTPS = true

        // 不配置任何属性 —— AMapNaviDriveView 的默认样式就是官方导航
        // （原版路线纹理、红绿灯、电子眼、3D 车模、自动车头朝上）
        let v = AMapNaviDriveView(frame: CGRect.zero)
        v.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        v.delegate = context.coordinator

        // 只要地图本身。高德导航视图默认会把整套控件都画上
        // （转向面板、退出/设置按钮、右侧光柱条、全览按钮、比例尺），
        // 和我们的仪表盘叠在一起非常乱。showUIElements=false 关掉这些。
        // 路线、红绿灯、电子眼、车标是地图元素，不受影响。
        v.showUIElements = false
        v.showTrafficBar = false      // 右侧那条彩色光柱单独关掉
        // 比例尺（用户要求：导航自己会缩放，不需要尺子）。
        // 文档原话：showScale 只在 showUIElements = NO 时才可设 —— 正好符合。
        v.showScale = false

        // ── 日夜模式：按日出日落自动切换 ──
        // AMapNaviDriveView.mapViewModeType，枚举定义在 AMapNaviCommonObj.h：
        //   0 = Day   1 = Night   2 = DayNightAuto（按日出日落自动）   3 = Custom
        // ⚠️ 默认值是 **0 = Day** —— 这就是「晚上地图还是白的」的原因。
        // 这里故意用 rawValue 取 2，不写 .dayNightAuto 这类 case 名：
        // SDK 的枚举名字踩过坑，rawValue 是 ABI 级稳定的（头文件里写死的 2）。
        if let auto = AMapNaviViewMapModeType(rawValue: 2) {
            v.mapViewModeType = auto
        }

        context.coordinator.attach(view: v)
        context.coordinator.update(from: from, to: to)
        return v
    }

    func updateUIView(_ v: AMapNaviDriveView, context: Context) {
        context.coordinator.applyAnchor(view: v)
        context.coordinator.feed(from: from)      // 先喂位置，避免引擎自己去搜星
        context.coordinator.update(from: from, to: to)
    }

    func makeCoordinator() -> NaviCoordinator { NaviCoordinator() }

    /// 提前把导航引擎点着。
    ///
    /// 高德的单例（AMapNaviDriveManager）第一次创建要初始化整套引擎，
    /// 是**同步阻塞**的 —— 用户实测「点模拟导航会卡一下」就是这个。
    /// 放到 App 启动时空跑一次，之后就不卡了。
    static func prewarm() {
        DispatchQueue.main.async {
            AMapServices.shared().apiKey = AMapConfig.iOSKey
            AMapServices.shared().enableHTTPS = true
            let m = AMapNaviDriveManager.sharedInstance()
            m.isUseInternalTTS = false
            m.pauseNaviSpeech()
        }
    }
}

final class NaviCoordinator: NSObject, AMapNaviDriveManagerDelegate,
                            AMapNaviDriveViewDelegate {

    /// 自车图标在屏幕上的位置（0~1，(0,0) 左上、(1,1) 右下）。
    ///
    ///   - **横屏**：往右挪。左边是大号车速数字（参考图也是车顶偏右，左半屏留给 HUD）。
    ///   - **竖屏**：往下挪。竖屏时车速数字就在车头正上方，居中的话会压住车头前的路线。
    ///
    /// 用户实测反馈过两轮位置，当前值：横屏再右一点、竖屏再下一点。
    /// ⚠️ screenAnchor 只在 showUIElements = NO 时生效（头文件原话），我们正好是 NO。
    /// 要调位置只改这两个常量 —— **巡航和导航是同一个 view，所以两边一定同步**。
    static let anchorLandscape = CGPoint(x: 0.64, y: 0.50)
    static let anchorPortrait  = CGPoint(x: 0.50, y: 0.66)

    /// 引擎当前处在哪个阶段
    private enum Phase { case idle, cruise, navi }

    private weak var view: AMapNaviDriveView?
    private var manager: AMapNaviDriveManager?
    private var phase: Phase = .idle
    /// 上一次算路的 (起点纬,起经,终纬,终经)，目的地/位置没大变就不重算
    private var planned: (Double, Double, Double, Double)?
    /// 上一次喂给引擎的位置（没动就不重复喂）
    private var lastFed: CLLocationCoordinate2D?

    func attach(view v: AMapNaviDriveView) { view = v }

    /// 自车图标位置：按当前屏幕方向设置
    func applyAnchor(view v: AMapNaviDriveView) {
        guard v.bounds.width > 1, v.bounds.height > 1 else { return }
        let portrait = v.bounds.height > v.bounds.width
        let want = portrait ? Self.anchorPortrait : Self.anchorLandscape
        if abs(v.screenAnchor.x - want.x) > 0.001 || abs(v.screenAnchor.y - want.y) > 0.001 {
            v.screenAnchor = want
        }
    }

    /// 把本机的「当前位置」喂给导航引擎。
    ///
    /// 为什么要这个：导航引擎默认用**它自己的**定位，还在搜星的那段时间
    /// 地图停在默认位置 —— 而高德的默认位置是**北京**。
    /// 用户实测：「点模拟导航会卡一下，然后地图先显示一下北京才跳到我的位置」。
    /// `setExternalLocation` 直接告诉引擎「你在这儿」，一刻都不用等。
    ///
    /// ⚠️ isAMapCoordinate 传 NO = 我们喂的是 WGS-84（内部坐标统一 WGS-84）。
    func feed(from: CLLocationCoordinate2D?) {
        guard let m = manager, let f = from else { return }
        if let l = lastFed,
           abs(l.latitude - f.latitude) < 1e-6, abs(l.longitude - f.longitude) < 1e-6 {
            return                      // 没动过就别打扰引擎
        }
        lastFed = f
        m.setExternalLocation(CLLocation(latitude: f.latitude, longitude: f.longitude),
                              isAMapCoordinate: false)
    }

    /// 统一入口：给了目的地就导航，没给就巡航。**两种状态用同一个 view。**
    func update(from: CLLocationCoordinate2D?, to: CLLocationCoordinate2D?) {
        guard let f = from else { return }
        if let t = to {
            enterNavigation(from: f, to: t)
        } else {
            enterCruise()
        }
    }

    private func ensureManager() -> AMapNaviDriveManager {
        if let m = manager { return m }
        let m = AMapNaviDriveManager.sharedInstance()
        m.delegate = self
        if let v = view { m.addDataRepresentative(v) }
        // ── 仪表盘自己不出声 ──
        // 用户明确要求：不要导航语音播报（车机自己会报）。
        // isUseInternalTTS 头文件默认就是 NO，这里显式声明一次防止版本差异；
        // pauseNaviSpeech 把内置播报暂停（文档：不影响导航状态）。
        m.isUseInternalTTS = false
        m.pauseNaviSpeech()
        manager = m
        return m
    }

    /// 巡航：**没有目的地时的背景地图**。同一个引擎，只是不带路线。
    ///
    /// `AMapNaviDriveManager.detectedMode`（巡航模式）枚举：
    ///   0 = None   1 = Camera（仅电子眼）   2 = SpecialRoad（仅特殊道路设施）
    ///   3 = CameraAndSpecialRoad
    /// ⚠️ 文档原话：从导航切回巡航要**先 stopNavi 再设 detectedMode**；
    /// 从巡航切到导航要**先把 detectedMode 设回 None 再开始导航**。
    private func enterCruise() {
        guard phase != .cruise else { return }
        let m = ensureManager()
        // ⚠️ 巡航**绝对不要**调 startGPSNavi：
        //   头文件原话「必须在路径规划成功的情况下，才能够开始实时导航」——
        //   没算路时它会直接失败；而且调了就等于进导航模式，不是巡航。
        //   巡航靠的是 detectedMode + 引擎自己的定位（我们再用
        //   setExternalLocation 把当前位置喂进去）。
        if phase == .navi {
            m.stopNavi()                        // 文档：要先 stopNavi，detectedMode 才生效
        }
        if let mode = AMapNaviDetectedMode(rawValue: 3) { m.detectedMode = mode }
        phase = .cruise
        planned = nil
    }

    /// 导航：算路 → 成功后由 delegate 回调里 startGPSNavi
    private func enterNavigation(from f: CLLocationCoordinate2D,
                                 to t: CLLocationCoordinate2D) {
        let key = (f.latitude, f.longitude, t.latitude, t.longitude)
        if let p = planned,
           abs(p.0 - key.0) < 5e-5, abs(p.1 - key.1) < 5e-5,
           abs(p.2 - key.2) < 5e-5, abs(p.3 - key.3) < 5e-5 {
            return
        }
        let m = ensureManager()
        if phase == .cruise {
            // 文档：先关巡航，再开导航
            if let off = AMapNaviDetectedMode(rawValue: 0) { m.detectedMode = off }
        }
        planned = key

        // 导航 SDK 的坐标是 GCJ-02，我们内部统一存 WGS-84，转一道
        let g1 = ChinaCoord.toGcj(f)
        let g2 = ChinaCoord.toGcj(t)
        let s = AMapNaviPoint.location(withLatitude: CGFloat(g1.latitude),
                                       longitude: CGFloat(g1.longitude))!
        let e = AMapNaviPoint.location(withLatitude: CGFloat(g2.latitude),
                                       longitude: CGFloat(g2.longitude))!
        m.calculateDriveRoute(withStart: [s], end: [e], wayPoints: nil,
                              drivingStrategy: .drivingStrategySingleDefault)
    }

    /// 算路成功 → 进入 GPS 导航
    func driveManager(_ driveManager: AMapNaviDriveManager,
                      onCalculateRouteSuccessWith type: AMapNaviRoutePlanType) {
        phase = .navi
        // 巡航期间 GPS 已经启动了，这里再调一次是幂等的
        //（SDK 的常规流程就是「算路成功 → startGPSNavi」）。
        driveManager.startGPSNavi()
    }
}
#endif
