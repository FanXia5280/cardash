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
    /// 视距（缩放级别）。高德官方 API：zoomLevel，范围 3~20。
    /// 越大越近。16≈200米、17≈100米、18≈50米、19≈25米。
    let zoom: Int

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
                                   zoom: zoom)
    }

    func makeCoordinator() -> Coordinator { Coordinator() }

    final class Coordinator: NSObject, MAMapViewDelegate {

        private var line: MAPolyline?
        private var routeCount = -1
        private var pin: MAPointAnnotation?
        private var lastKey: String?
        private var lastDestKey: String?
        private var lastZoom: Int = -1

        func update(view: MAMapView,
                    coord: CLLocationCoordinate2D?,
                    heading: Double,
                    route: [CLLocationCoordinate2D],
                    dest: CLLocationCoordinate2D?,
                    zoom: Int) {

            // 视距只在用户真的改动时才动，别每帧重置，否则 +/− 没效果
            if zoom != lastZoom {
                lastZoom = zoom
                view.setZoomLevel(Double(zoom), animated: true)
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

            if route.count != routeCount {
                routeCount = route.count
                if let old = line { view.remove(old) }
                line = nil
                if route.count >= 2 {
                    // 高德算路返回的就是 GCJ-02，但我们内部统一存 WGS-84，
                    // 所以这里还是要转一道
                    let pts = route.map { p -> CLLocationCoordinate2D in ChinaCoord.toGcj(p) }
                    var coords = pts
                    let l = MAPolyline(coordinates: &coords, count: UInt(pts.count))
                    line = l
                    view.add(l)
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

        func mapView(_ mapView: MAMapView!, rendererFor overlay: MAOverlay!) -> MAOverlayRenderer! {
            guard let l = overlay as? MAPolyline else { return nil }
            let r = MAPolylineRenderer(polyline: l)
            r?.strokeColor = UIColor(red: 0.25, green: 0.62, blue: 1.0, alpha: 0.95)
            r?.lineWidth = 9
            return r
        }

        func mapView(_ mapView: MAMapView!, viewFor annotation: MAAnnotation!) -> MAAnnotationView! {
            guard !(annotation is MAUserLocation) else { return nil }
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

    var body: some View {
        #if canImport(MAMapKit)
        if amapAgreed {
            AMapNavView(coord: coord, heading: heading, route: route, dest: dest,
                        zoom: zoom)
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
