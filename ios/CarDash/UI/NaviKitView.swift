#if canImport(AMapNaviKit)
import SwiftUI
import UIKit
import CoreLocation
import AMapNaviKit

/// 高德**官方导航视图**（AMapNaviView）。
///
/// 之前自己用 MAPolyline 画路线，怎么调都和高德有差距。
/// 这个直接用官方导航引擎：路线（含路况红/黄/绿）、3D 车标、
/// 红绿灯倒计时、电子眼提醒，全部是 SDK 自带的原版效果，不再自绘。
///
/// 用法：车机报了目的地时整屏接管仪表背景；
/// 没有目的地时退回原来的普通地图。
struct NaviKitNavView: UIViewRepresentable {
    let from: CLLocationCoordinate2D?     // WGS-84 当前位置
    let to: CLLocationCoordinate2D?       // WGS-84 目的地

    func makeUIView(context: Context) -> AMapNaviView {
        // Key 和隐私接口在 App 启动时已经设置过；这里再设一遍是幂等的，
        // 防止「一打开就有目的地、普通地图还没初始化过」的时序
        AMapServices.shared().apiKey = AMapConfig.iOSKey
        AMapServices.shared().enableHTTPS = true

        // 不配置任何属性 —— AMapNaviView 的默认样式就是官方导航
        // （原版路线纹理、红绿灯、电子眼、3D 车模、自动车头朝上）
        let v = AMapNaviView(frame: CGRect.zero, naviView: AMapNaviViewOptions())
        context.coordinator.attach(view: v)
        context.coordinator.plan(from: from, to: to)
        return v
    }

    func updateUIView(_ v: AMapNaviView, context: Context) {
        context.coordinator.plan(from: from, to: to)
    }

    func makeCoordinator() -> NaviCoordinator { NaviCoordinator() }
}

final class NaviCoordinator: NSObject, AMapNaviDriveManagerDelegate {
    private weak var view: AMapNaviView?
    private var manager: AMapNaviDriveManager?
    /// 上一次算路的 (起点纬,起经,终纬,终经)，目的地/位置没大变就不重算
    private var planned: (Double, Double, Double, Double)?
    private var naviStarted = false

    func attach(view v: AMapNaviView) { view = v }

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
            manager = m
        }

        // 导航 SDK 的坐标是 GCJ-02，我们内部统一存 WGS-84，转一道
        let g1 = ChinaCoord.toGcj(f)
        let g2 = ChinaCoord.toGcj(t)
        let s = AMapNaviPoint.location(withLatitude: CGFloat(g1.latitude),
                                       longitude: CGFloat(g1.longitude))!
        let e = AMapNaviPoint.location(withLatitude: CGFloat(g2.latitude),
                                       longitude: CGFloat(g2.longitude))!
        m.calculateDriveRoute(withStart: [s], end: [e], wayPoints: nil,
                              drivingStrategy: .DrivingStrategySingleDefault)
    }

    /// 算路成功 → 开始真实 GPS 导航（只启动一次）
    func driveManager(onCalculateRouteSuccess driveManager: AMapNaviDriveManager) {
        guard !naviStarted else { return }
        naviStarted = true
        driveManager.startGPSNavi()
    }
}
#endif
