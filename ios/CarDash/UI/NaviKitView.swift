#if canImport(AMapNaviKit)
import SwiftUI
import UIKit
import CoreLocation
import AMapNaviKit

/// 高德**官方导航视图**（AMapNaviDriveView）—— 有目的地时整屏接管仪表背景。
///
/// 原版路线纹理、3D 车标、红绿灯倒计时、电子眼，全是 SDK 自带，不自己画。
///
/// ⚠️ 2026-09-23 深夜：试过「没目的地时也用这个 view，靠巡航模式跟车」——
/// **不行**。官方文档《开发指南 → 专业导航 → 智能巡航》明确写了：
/// 巡航只提供电子眼/道路设施**数据**，地图视图**不会**自己跟车，
/// 官方给的巡航 UI 方案是「自己建 MAMapView + Annotation + 用 updateNaviLocation 更新坐标」。
/// 实测也是那样：巡航态下这个 view 停在默认位置（北京）、连车标都没有。
/// 所以没目的地时走 `AMapNavView`（MAMapView），那边已经调成和这里同一套观感
/// （`.naviNight` 底图 + 高德官方车标 + 同一组锚点）。
struct NaviKitNavView: UIViewRepresentable {
    let from: CLLocationCoordinate2D?     // WGS-84 当前位置
    let to: CLLocationCoordinate2D?       // WGS-84 目的地

    func makeUIView(context: Context) -> AMapNaviDriveView {
        // Key 和隐私接口在 App 启动时已经设置过；这里再设一遍是幂等的，
        // 防止「一打开就有目的地、普通地图还没初始化过」的时序
        AMapServices.shared().apiKey = AMapConfig.iOSKey
        AMapServices.shared().enableHTTPS = true

        // 不配置任何属性 —— AMapNaviDriveView 的默认样式就是官方导航
        // （原版路线纹理、红绿灯、电子眼、3D 车模、自动车头朝上）
        let v = AMapNaviDriveView(frame: CGRect.zero)
        v.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        v.delegate = context.coordinator

        // 只要路线本身。高德导航视图默认会把整套控件都画上
        // （转向面板、退出/设置按钮、右侧光柱条、全览按钮、比例尺），
        // 和我们的仪表盘叠在一起非常乱。showUIElements=false 关掉这些
        // 控件；路线、红绿灯、电子眼、车标是地图元素，不受影响，仍然显示。
        v.showUIElements = false
        v.showTrafficBar = false      // 右侧那条彩色光柱单独关掉
        // 比例尺也关掉（用户要求：导航自己会缩放，不需要尺子）。
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
        context.coordinator.plan(from: from, to: to)
        return v
    }

    func updateUIView(_ v: AMapNaviDriveView, context: Context) {
        // 自车图标位置随屏幕方向走（横屏靠右 / 竖屏靠下）
        context.coordinator.applyAnchor(view: v)
        // 把当前位置持续喂给引擎，别让它自己慢慢搜星
        context.coordinator.feed(from: from)
        context.coordinator.plan(from: from, to: to)
    }

    func makeCoordinator() -> NaviCoordinator { NaviCoordinator() }

    /// 提前把导航引擎点着。
    ///
    /// 高德的单例（AMapNaviDriveManager）第一次创建要初始化整套引擎，
    /// 是**同步阻塞**的 —— 用户实测「点模拟导航会卡一下」就是这个。
    /// 放到 App 启动时空跑一次，点导航时就不卡了。
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

    private weak var view: AMapNaviDriveView?
    private var manager: AMapNaviDriveManager?
    /// 上一次算路的 (起点纬,起经,终纬,终经)，目的地/位置没大变就不重算
    private var planned: (Double, Double, Double, Double)?
    private var naviStarted = false
    /// 上一次喂给引擎的位置（没动就不重复喂）
    private var lastFed: CLLocationCoordinate2D?

    func attach(view v: AMapNaviDriveView) { view = v }

    /// 自车图标位置：按当前屏幕方向设置。
    /// 值取自 `MapAnchor`（**和非导航态共用同一组**，用户要求两边同步）。
    /// screenAnchor 只在 showUIElements = NO 时生效（头文件原话）。
    func applyAnchor(view v: AMapNaviDriveView) {
        guard v.bounds.width > 1, v.bounds.height > 1 else { return }
        let want = MapAnchor.of(size: v.bounds.size)
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

    func plan(from: CLLocationCoordinate2D?, to: CLLocationCoordinate2D?) {
        guard let f = from, let t = to else { return }
        let key = (f.latitude, f.longitude, t.latitude, t.longitude)
        if let p = planned,
           abs(p.0 - key.0) < 5e-5, abs(p.1 - key.1) < 5e-5,
           abs(p.2 - key.2) < 5e-5, abs(p.3 - key.3) < 5e-5 {
            return
        }
        planned = key

        let m = manager ?? AMapNaviDriveManager.sharedInstance()
        if manager == nil {
            m.delegate = self
            if let v = view { m.addDataRepresentative(v) }
            // ── 仪表盘自己不出声 ──
            // 用户明确要求：不要导航语音播报（车机自己会报）。
            // isUseInternalTTS 头文件默认就是 NO，这里显式声明一次防止版本差异；
            // pauseNaviSpeech 把内置播报暂停（文档：不影响导航状态）。
            m.isUseInternalTTS = false
            m.pauseNaviSpeech()
            manager = m
        }
        feed(from: f)

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

    /// 算路成功 → 开始真实 GPS 导航（只启动一次）
    ///
    /// ⚠️ `startGPSNavi` 头文件原话「必须在路径规划成功的情况下，才能够开始实时导航」，
    /// 所以它只能在这个回调里调，别挪到别处。
    func driveManager(_ driveManager: AMapNaviDriveManager,
                      onCalculateRouteSuccessWith type: AMapNaviRoutePlanType) {
        guard !naviStarted else { return }
        naviStarted = true
        driveManager.startGPSNavi()
    }
}
#endif
