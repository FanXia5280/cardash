#if canImport(AMapNaviKit)
import SwiftUI
import UIKit
import CoreLocation
import AMapNaviKit

/// 带布局回调的导航视图：屏幕旋转 / 尺寸变化时**立刻**重算自车图标位置。
///
/// 为什么要这个：SwiftUI 在横竖屏切换时**不一定**会调 `updateUIView`
/// （它的输入坐标没变），而视图 bounds 已经变了 ⇒ `screenAnchor` 还停在
/// 旧方向那一档，看起来就是「切过来先错位、要等一会（下次定位更新）才归位」。
/// 用户实测就是这个现象。在 `layoutSubviews` 里补一次，同一帧内就摆正了。
final class AnchoredDriveView: AMapNaviDriveView {
    var onLayout: (() -> Void)?
    private var lastSize: CGSize = .zero

    override func layoutSubviews() {
        super.layoutSubviews()
        // ⚠️ 只在**尺寸真的变了**才回调。layoutSubviews 可能被 SDK 内部频繁触发，
        // 每次都重设 screenAnchor 会跟导航引擎抢相机 → 表现出来就是"地图卡卡的"。
        if bounds.size != lastSize {
            lastSize = bounds.size
            onLayout?()
        }
    }
}

/// 高德**官方导航视图**（AMapNaviDriveView）—— 有目的地时整屏接管仪表背景。
///
/// 原版路线纹理、红绿灯倒计时、电子眼、车标全是 SDK 自带，不自己画。
///
/// ⚠️ 2026-09-23 半夜试过「没目的地时也用这个 view，靠巡航模式跟车」—— **不行**。
/// 官方文档《智能巡航》写明：巡航只提供电子眼/道路设施**数据**，地图视图**不跟车**，
/// 官方给的巡航 UI 方案是「自己建 MAMapView + Annotation」。实测那次停在默认位置（北京）。
/// 所以没目的地时走 `AMapNavView`（MAMapView），那边已调成同一套观感。
struct NaviKitNavView: UIViewRepresentable {
    let from: CLLocationCoordinate2D?     // WGS-84 当前位置
    let to: CLLocationCoordinate2D?       // WGS-84 目的地
    /// true = 用 SDK 的**模拟导航**（`startEmulatorNavi`）沿路线自动跑 ——
    /// 就是官方 doc 说的那种"预先了解既定路线的路况、电子眼"的效果。
    var simulate: Bool = false
    /// 路口放大图：SDK 在数据回调里给图（比例固定 25:16），nil = 收起。
    /// 只在实际导航中会有数据；SDK 自己判断何时显示/隐藏。
    var onCrossImage: ((UIImage?) -> Void)? = nil

    func makeUIView(context: Context) -> AMapNaviDriveView {
        // Key 和隐私接口在 App 启动时已经设置过；这里再设一遍是幂等的
        AMapServices.shared().apiKey = AMapConfig.iOSKey
        AMapServices.shared().enableHTTPS = true

        let v = AnchoredDriveView(frame: CGRect.zero)
        v.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        v.delegate = context.coordinator
        // 旋转时立刻重摆车头位置（见 AnchoredDriveView 的说明）
        v.onLayout = { [weak v, weak co = context.coordinator] in
            guard let v else { return }
            co?.applyAnchor(view: v)
        }

        // 只要路线本身。高德导航视图默认会把整套控件都画上
        // （转向面板、退出/设置按钮、右侧光柱条、全览按钮、比例尺），
        // 和我们的仪表盘叠在一起非常乱。showUIElements=false 关掉这些
        // 控件；路线、红绿灯、电子眼、车标是地图元素，不受影响，仍然显示。
        v.showUIElements = false
        // 路口放大图：⚠️ 这里必须设 **false**，否则拿不到图。
        // 头文件原话：「10.0.2版本开始，自定义 View 中如果设置了 showCrossImage 为 YES，
        // 回调中 crossImage 为 nil」—— 而它默认就是 YES。
        // 我们要自己画（用户要求：显示在"速度"那一块，只遮速度、不挡别的元素），
        // 所以关掉 SDK 内置显示，改成从数据回调里拿图自己摆。
        v.showCrossImage = false
        v.showTrafficBar = false      // 右侧那条彩色光柱单独关掉
        // 比例尺也关掉（用户要求：导航自己会缩放，不需要尺子）。
        // 文档原话：showScale 只在 showUIElements = NO 时才可设 —— 正好符合。
        v.showScale = false

        // ── 路线上显示什么（用户 2026-09-24 提的几条，全照头文件的默认值/开关来）──
        // ⚠️ 头文件原话：下面这些开关大多**默认 NO** ——"没显示"不是 bug，是没开。
        // 1) 走过的路置灰：头文件「走过的路线是否置灰, 默认为 NO」
        //    ← 用户说"跑过的路不灰、还是绿的"，就是这个没开
        v.showGreyAfterPass = true
        // 2) 电子眼：showCamera 默认 YES，但**距离**默认 NO
        v.showCamera = true
        v.showCameraDistance = true
        // 3) 红绿灯**图标**（默认 YES、免费）
        v.showTrafficLights = true
        // ⛔ 红绿灯**倒计时**（showTrafficLightView）与超速脉冲（showOverSpeedPulse）
        //    都是**收费接口**：用户 2026-09-24 明确要求"只用官方 SDK 的免费功能" ⇒ 不开。
        //    要开得提高德商务合作工单（价格不公开，见交接文档待办 9）。
        //    ⚠️ 别顺手打开"试试" —— 没付费权限时它们**什么都不画**，白开。
        //
        // 超速那条我们自己有一份免费的（车机高德广播的限速 + 实时车速 → EdgeGlow 红光），
        // 数据同样来自原厂，不依赖这个收费接口。

        // ── 日夜模式：按日出日落自动切换 ──
        // AMapNaviDriveView.mapViewModeType，枚举定义在 AMapNaviCommonObj.h：
        //   0 = Day   1 = Night   2 = DayNightAuto（按日出日落自动）   3 = Custom
        // ⚠️ 默认值是 **0 = Day** —— 这就是「晚上地图还是白的」的原因。
        // 这里故意用 rawValue 取 2，不写 .dayNightAuto 这类 case 名：
        // SDK 的枚举名字踩过坑，rawValue 是 ABI 级稳定的（头文件里写死的 2）。
        if let auto = AMapNaviViewMapModeType(rawValue: 2) {
            v.mapViewModeType = auto
        }

        context.coordinator.onCrossImage = onCrossImage
        context.coordinator.attach(view: v)
        context.coordinator.update(naviView: v, from: from, to: to, simulate: simulate)
        return v
    }

    func updateUIView(_ v: AMapNaviDriveView, context: Context) {
        // 自车图标位置随屏幕方向走（横屏靠右 / 竖屏靠下）
        context.coordinator.applyAnchor(view: v)
        context.coordinator.onCrossImage = onCrossImage
        context.coordinator.update(naviView: v, from: from, to: to, simulate: simulate)
    }

    func makeCoordinator() -> NaviCoordinator { NaviCoordinator() }

    /// 提前把导航引擎点着。
    ///
    /// 高德的单例（AMapNaviDriveManager）第一次创建要初始化整套引擎，
    /// 是**同步阻塞**的 —— 用户实测「点模拟导航会卡一下」就是这个。
    /// 放到 App 启动时（启动遮罩后面）空跑一次，之后就顺了。
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
                            AMapNaviDriveViewDelegate,
                            AMapNaviDriveDataRepresentable {

    /// 路口放大图有/无（SDK 给图，nil = 收起）。转发给 UI（见 DashboardView 的 SpeedSlot）。
    var onCrossImage: ((UIImage?) -> Void)?

    private weak var view: AMapNaviDriveView?
    private var manager: AMapNaviDriveManager?
    /// 上一次算路的**目的地**（只跟终点的纬经度有关）
    private var plannedDest: (Double, Double)?
    private var plannedSimulate: Bool?
    /// 当前引擎是怎么跑起来的：nil / "gps"（实时导航）/ "emulator"（模拟导航）
    private var startedMode: String?
    private var simulate = false
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
    /// ⚠️ **模拟导航时绝不能喂** —— 那会把模拟车拽回真实位置，模拟就跑不动了。
    func feed(from: CLLocationCoordinate2D?) {
        // 模拟导航**开始之后**不能再喂真实定位（会把模拟车拽回真实位置）；
        // 但**开始之前**必须喂 —— 否则地图先停在高德的默认位置（北京），
        // 用户实测「模拟导航先显示北京，然后才从定位开始」就是这一条。
        if simulate && startedMode != nil { return }
        guard let m = manager, let f = from else { return }
        if let l = lastFed,
           abs(l.latitude - f.latitude) < 1e-6, abs(l.longitude - f.longitude) < 1e-6 {
            return                      // 没动过就别打扰引擎
        }
        lastFed = f
        m.setExternalLocation(CLLocation(latitude: f.latitude, longitude: f.longitude),
                              isAMapCoordinate: false)
    }

    /// ⚠️ 形参**不要**叫 `view`：那会盖住上面的 `private weak var view`，
    /// 在函数体里 `view` 就变成了非可选类型 —— `if let v = view` 会直接编译不过
    ///（2026-09-23 CI 就是这么挂的：`initializer for conditional binding must have
    ///  Optional type`）。所以这里用 `naviView`。
    func update(naviView: AMapNaviDriveView, from: CLLocationCoordinate2D?,
                to: CLLocationCoordinate2D?, simulate: Bool) {
        self.simulate = simulate

        guard let t = to else {
            // 目的地没了：把引擎停掉，别让导航/模拟在后台空转（费电）
            if let m = manager, startedMode != nil {
                m.stopNavi()
                startedMode = nil
                plannedDest = nil
                plannedSimulate = nil
            }
            return
        }
        guard let f = from else { return }

        // ⚠️ 顺序很关键：**先建引擎 → 再喂位置 → 最后才谈算路**。
        // 以前是先 feed 后建引擎 —— 第一次调用时 manager 还是 nil，feed 等于没做，
        // 于是地图先显示高德的默认位置（**北京**），要等下一次更新才跳过来。
        // 用户实测「模拟导航先显示北京，然后才从定位开始导航」就是这个。
        let m = manager ?? AMapNaviDriveManager.sharedInstance()
        if manager == nil {
            m.delegate = self
            m.addDataRepresentative(naviView)
            // 路口放大图走**数据回调**（AMapNaviDriveDataRepresentable），不在 delegate 里，
            // 所以自己也得注册进去 —— 官方文档要求"算路之前"注册好，否则第一次进路口收不到。
            m.addDataRepresentative(self)
            // ── 仪表盘自己不出声 ──
            // 用户明确要求：不要导航语音播报（车机自己会报）。
            // isUseInternalTTS 头文件默认就是 NO，这里显式声明一次防止版本差异；
            // pauseNaviSpeech 把内置播报暂停（文档：不影响导航状态）。
            m.isUseInternalTTS = false
            m.pauseNaviSpeech()
            manager = m
        }
        feed(from: f)

        // ── 只有**目的地变了**（或模拟开关变了）才重新算路 ──
        // ⚠️ 千万别把起点也算进 key：车一动就重算路线，白费流量，还会把导航重置
        //（1.6.12 之前就是这么写的，等于每开 5 米重算一次）。
        if let p = plannedDest,
           abs(p.0 - t.latitude) < 5e-5, abs(p.1 - t.longitude) < 5e-5,
           plannedSimulate == simulate {
            return
        }
        plannedDest = (t.latitude, t.longitude)
        plannedSimulate = simulate

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

    /// 算路成功 → 启动导航。
    ///
    /// ⚠️ `startGPSNavi` / `startEmulatorNavi` 头文件都要求「**必须在路径规划成功的情况下**」
    /// 才能调用，所以只能在这个回调里调，别挪别处。
    ///
    /// 模拟导航按官方文档实现（《实时导航与模拟导航》）：
    /// 「模拟导航的实现步骤与实时导航基本一致，区别就是在路径规划成功的回调函数中
    ///   调用 startEmulatorNavi 方法开启模拟导航。」
    func driveManager(_ driveManager: AMapNaviDriveManager,
                      onCalculateRouteSuccessWith type: AMapNaviRoutePlanType) {
        if simulate {
            if startedMode == "gps" { driveManager.stopNavi() }   // 换模式前先停
            guard startedMode != "emulator" else { return }
            startedMode = "emulator"
            driveManager.setEmulatorNaviSpeed(60)                 // 官方默认 60 km/h
            driveManager.startEmulatorNavi()
        } else {
            if startedMode == "emulator" { driveManager.stopNavi() }
            guard startedMode != "gps" else { return }
            startedMode = "gps"
            driveManager.startGPSNavi()
        }
    }

    // MARK: - 路口放大图（AMapNaviDriveDataRepresentable）

    /// 该显示路口放大图了 —— SDK 直接把图画好给你（比例固定 **25:16**）。
    ///
    /// ⚠️ 前提是 driveView 的 `showCrossImage` 必须是 **false**
    /// （头文件：「自定义View中如果设置了showCrossImage为YES，回调中crossImage为nil」）。
    /// 显示/隐藏的时机由 SDK 判断，我们只管把图摆到"速度"那一格去。
    func driveManager(_ driveManager: AMapNaviDriveManager,
                      showCrossImage crossImage: UIImage?) {
        guard let crossImage else { return }
        onCrossImage?(crossImage)
    }

    /// 过了路口 / 该收起来了 —— 收图之后速度表自己会露出来。
    func driveManagerHideCrossImage(_ driveManager: AMapNaviDriveManager) {
        onCrossImage?(nil)
    }
}
#endif
