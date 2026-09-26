import SwiftUI
import UIKit

struct DashboardView: View {
    @ObservedObject var model: DashboardModel

    /// 路口放大图（导航 SDK 通过数据回调给图）。非 nil 时**盖在"速度"那一格**上 ——
    /// 用户要求：只遮速度、不挡别的元素，所以它和速度表共用同一格（见 SpeedSlot）。
    @State private var crossImage: UIImage?

    /// 导航引擎真的开始跟车了吗（`didStartNavi`）。
    /// 在那之前 `AMapNaviDriveView` 显示的是 SDK 默认位置（**北京**），
    /// 所以先用一张非导航地图垫底顶着；起步后再换成导航视图（用户实测会闪一下北京）。
    @State private var navStarted = false

    /// 「时速」那一格现在有两页（速度 / 胎压），竖屏左右滑、横屏上下滑切换。
    /// 默认停在速度页；车机没有胎压数据时锁在速度页（见 SpeedSlot.tireAvailable）。
    @State private var speedSlotPage: SpeedSlotPage = .speed

    @State private var showSettings = false
    /// 点一下屏幕才浮出设置按钮。
    /// 之前是「点屏幕任意位置直接进设置」，开车时手一碰就弹走了，
    /// 改成先浮出一个小齿轮，点它才进设置页。
    @State private var showSettingsButton = false
    @State private var hideWork: DispatchWorkItem?

    /// 地图底图：默认用高德（视觉和车机一致）。万一瓦片服务不通，
    /// 在设置里切回苹果即可 —— 不至于开天窗。
    @AppStorage("useAmapTiles") private var useAmapTiles = true
    /// 用户有没有同意高德 SDK 的隐私协议。
    /// 高德强制要求：没同意就去 new MAMapView 会拿到 nil（地图空白），
    /// 所以没同意时退回栅格地图。
    @AppStorage("amapPrivacyAgreed") private var amapAgreed = false
    @AppStorage("amapPrivacyAsked") private var amapAsked = false
    /// 视距（缩放级别），存在本地，下次打开记住。16≈200米。
    /// 自动档的差值会直接写回这里，所以它始终等于地图的实际档位。
    @AppStorage("mapZoom") private var zoom = 16
    /// 上一次的自动档，用来算差值
    @State private var lastAutoOffset = 0
    @State private var showPrivacy = false

    var body: some View {
        ZStack {
            // ── 背景：非导航地图（**始终挂载**）──
            if let reason = model.mapUnavailableReason {
                MapPlaceholder(reason: reason)
                    .ignoresSafeArea()
            } else {
                // 非导航地图一直垫在最底下。进导航、退出导航都**不重建**它 ——
                // 之前是「退出导航 → routeDest 变 nil → 切到另一支 → MAMapView 重建」，
                // 重建有初始化空档，表现出来就是用户说的「点退出导航车标先跳回定位点、
                // 卡一下才出现绿标」。始终挂载后，退出导航只是把上面的导航视图撤掉，
                // 底下的地图和绿标本来就在，没有断层。
                DashboardMapView(coord: model.coord,
                                 heading: model.heading,
                                 dest: model.routeDest,
                                 amapAgreed: amapAgreed,
                                 useRasterFallback: useAmapTiles,
                                 zoom: zoom,
                                 speed: model.displaySpeed)
                    .ignoresSafeArea()

                // 车机报了目的地：高德官方导航视图盖在上面。
                // didStartNavi 之前保持透明，露出底下的地图 —— 挡住 SDK 默认位置
                //（北京）那一帧（用户实测"会闪一下北京"）。
                if let dest = model.routeDest, amapAgreed {
                    NaviKitNavView(from: model.coord, to: dest,
                                   onCrossImage: { crossImage = $0 },
                                   onStarted: { navStarted = true })
                        .opacity(navStarted ? 1 : 0)
                        .ignoresSafeArea()
                }
            }

            GeometryReader { geo in
                let k = scaleFactor(for: geo)

                ZStack {
                    // ── 黑色渐变遮罩：方向跟着屏幕转 ──
                    // 参考图的做法：HUD 那一侧压暗，白字才读得清；
                    // 地图和导航路线那一侧保持通亮，不受影响。
                    //   横屏 → 压左边（速度在左边）
                    //   竖屏 → 压上边（速度在上方），到中段就淡出，
                    //          下面整块留给地图和路线
                    // 多加几段 stop，让渐变是「慢慢淡下去」而不是「很快就没」，
                    // 参考图那种自然的压暗就是这个差别。
                    //
                    // ⚠️ 2026-09-23：用户对比参考图后反馈「不够黑，靠近灵动岛那一带
                    // 要更黑一点」→ 主渐变加了浓度，并**额外压一条顶部带**（下面那层）。
                    ZStack {
                        if geo.size.height > geo.size.width {
                            LinearGradient(
                                stops: [
                                    .init(color: .black.opacity(0.86), location: 0.00),
                                    .init(color: .black.opacity(0.68), location: 0.20),
                                    .init(color: .black.opacity(0.42), location: 0.44),
                                    .init(color: .black.opacity(0.16), location: 0.58),
                                    .init(color: .black.opacity(0.00), location: 0.74),
                                ],
                                startPoint: .top, endPoint: .bottom
                            )
                        } else {
                            LinearGradient(
                                stops: [
                                    .init(color: .black.opacity(0.88), location: 0.00),
                                    .init(color: .black.opacity(0.70), location: 0.20),
                                    .init(color: .black.opacity(0.44), location: 0.42),
                                    .init(color: .black.opacity(0.18), location: 0.60),
                                    .init(color: .black.opacity(0.00), location: 0.76),
                                ],
                                startPoint: .leading, endPoint: .trailing
                            )
                        }

                        // 顶部那条：灵动岛/刘海就在这一带，压得更黑（横竖屏都要）
                        LinearGradient(
                            stops: [
                                .init(color: .black.opacity(0.52), location: 0.00),
                                .init(color: .black.opacity(0.26), location: 0.11),
                                .init(color: .black.opacity(0.00), location: 0.26),
                            ],
                            startPoint: .top, endPoint: .bottom
                        )
                    }
                    .ignoresSafeArea()
                    .allowsHitTesting(false)

                    // ── 屏幕两边那两条光 ──
                    // 超速→红、转向灯→绿，数值都是我们自己算的（见 DashboardModel
                    // 的 isOverspeed / displayTurn 与 EdgeGlow 的说明）。
                    // 放在 HUD 下面、地图上面，不挡任何操作。
                    if model.isOverspeed {
                        EdgeGlow(kind: .over, left: true, right: true, scale: k)
                            .ignoresSafeArea()
                    }
                    if let t = model.displayTurn, t == 1 || t == 2 || t == 3 {
                        // 1=左（只闪左边）2=右（只闪右边）3=双闪（两边一起）
                        EdgeGlow(kind: .turn, left: t != 2, right: t != 1, scale: k)
                            .ignoresSafeArea()
                    }

                    Group {
                        if geo.size.height > geo.size.width {
                            portraitLayout(k: k)
                        } else {
                            landscapeLayout(geo: geo, k: k)
                        }
                    }
                    .padding(.horizontal, k * 20)
                    .padding(.top, k * 12)
                    .padding(.bottom, k * 10)
                    .frame(width: geo.size.width, height: geo.size.height)

                    // 浮出来的设置按钮
                    if showSettingsButton {
                        VStack {
                            Spacer()
                            SettingsButton(scale: k) {
                                hideWork?.cancel()
                                showSettingsButton = false
                                showSettings = true
                            }
                            .padding(.bottom, k * 54)
                        }
                        .frame(maxWidth: .infinity, maxHeight: .infinity)
                        .transition(.opacity)
                    }
                }
                .frame(width: geo.size.width, height: geo.size.height)
                .contentShape(Rectangle())
                .onTapGesture { revealSettingsButton() }
            }
        }
        .animation(.easeInOut(duration: 0.18), value: showSettingsButton)
        .sheet(isPresented: $showSettings) {
            SettingsSheet(model: model)
        }
        .sheet(isPresented: $showPrivacy) {
            PrivacyConsentView { agreed in
                amapAgreed = agreed
                amapAsked = true
                showPrivacy = false
            }
        }
        .onAppear {
            // 第一次启动问一次。同意才启用高德矢量地图。
            if !amapAsked { showPrivacy = true }
            lastAutoOffset = MapZoom.autoOffset(model.displaySpeed)
            // 提前把高德导航引擎点着。它第一次创建是**同步阻塞**的，
            // 不然点「模拟导航」/ 车机一来目的地就会卡一下（用户实测反馈）。
            #if canImport(AMapNaviKit)
            if amapAgreed { NaviKitNavView.prewarm() }
            #endif
        }
        .onChange(of: MapZoom.autoOffset(model.displaySpeed)) { newOff in
            // 车速换了档（比如从怠速提到快速路），把差值写进 zoom。
            // 这样底栏 +/− 每次点击都恰好变化一档，地图和数字永远一致。
            if newOff != lastAutoOffset {
                zoom = MapZoom.clamp(zoom + (newOff - lastAutoOffset))
                lastAutoOffset = newOff
            }
        }
        .onChange(of: model.routeDest == nil) { gone in
            // 目的地没了（清除模拟路线 / 导航结束）⇒ 下次重新导航要重新"垫底"
            if gone {
                navStarted = false
                // ⚠️ 顺手把路口放大图收掉：SDK 的 hide 回调在导航视图被拆掉之后不会再来了，
                // 不收它就会一直盖在"速度"那一格上（用户实测：退出导航后放大图还在）。
                crossImage = nil
            }
        }
        .onChange(of: model.displayTire == nil) { gone in
            // 胎压数据没了（断链 / 这台车没有 TPMS / 旧版车机 APK）⇒
            // 别把用户留在一页全是 "--" 上，自动退回速度页。
            if gone { speedSlotPage = .speed }
        }
    }

    /// 点屏幕 → 浮出齿轮；6 秒内没点它就自动收起。
    private func revealSettingsButton() {
        showSettingsButton = true
        hideWork?.cancel()
        let w = DispatchWorkItem { showSettingsButton = false }
        hideWork = w
        DispatchQueue.main.asyncAfter(deadline: .now() + 6, execute: w)
    }

    // MARK: - 横屏版式

    private func landscapeLayout(geo: GeometryProxy, k: CGFloat) -> some View {
        VStack(spacing: 0) {
            // ── 顶栏：左上日期时间 / 中间导航摘要 / 右上海拔 ──
            ZStack {
                ClockPanel(scale: k)
                    .frame(height: k * 34, alignment: .center)
                    .hudCard(k)
                    .frame(maxWidth: .infinity, alignment: .leading)

                // 注意顺序：opacity 必须在 hudCard **之后**。
                // 面板自己那层 opacity 是加在内容上的，卡片底会留在外面 ——
                // 上一版没导航时会看到一块空的深色圆角矩形，就是这么来的。
                NavSummaryPanel(nav: model.displayNav, scale: k)
                    .hudCard(k)
                    .opacity(model.displayNav?.isActive == true ? 1 : 0)
                    .padding(.top, k * 4)

                // 横屏右上角：用户要求海拔移除、这里放「退出导航」（仅导航中显示）
                if model.routeDest != nil {
                    NavExitButton(scale: k) { model.endNavigation() }
                        .frame(maxWidth: .infinity, alignment: .trailing)
                }
            }

            Spacer(minLength: 8)

            // ── 车速移到左边（参考图里大 P 的位置），右边整块留给地图 ──
            // 左边有黑色渐变垫底，白字压在上面读得清；
            // 右边地图通亮，路线不会被挡。
            SpeedSlot(image: crossImage,
                      scale: k,
                      align: .leading,
                      swipeAxis: .vertical,                       // 横屏：上下滑切换
                      tireAvailable: model.displayTire != nil,
                      page: $speedSlotPage,
                      onTap: { revealSettingsButton() }) {
                SpeedGauge(speed: model.displaySpeed, scale: k, align: .leading)
            } tire: {
                TirePressurePanel(tire: model.displayTire, scale: k)
            }
            .frame(maxWidth: .infinity, alignment: .leading)

            // 转向卡（TurnCard）已按用户要求整块移除 —— 转向看车机自己的屏幕。
            // 所以这里只剩一个弹性空隙，把底栏顶到屏幕底部。
            Spacer(minLength: 10)

            // ── 底栏：左电量续航 / 右档位总里程 ──
            // 中间那块比例尺（ZoomPill）也删了：导航视图自己会缩放，
            // 那条尺子只控制「非导航地图」的视距，导航时没有意义。
            HStack(alignment: .bottom) {
                BatteryRangePanel(soc: model.displaySoc,
                                  range: model.displayRange,
                                  scale: k)
                    .hudCard(k)
                Spacer(minLength: 10)
                GearOdometerPanel(gear: model.displayGear,
                                  odometer: model.displayOdometer,
                                  scale: k)
                    .hudCard(k)
            }
        }
        .overlay(alignment: .bottom) {
            LinkBadge(status: model.link, scale: k)
                .offset(y: k * 22)
        }
    }

    // MARK: - 竖屏版式

    private func portraitLayout(k: CGFloat) -> some View {
        VStack(spacing: 0) {
            // ── 顶栏：日期时间 / 海拔 ──
            HStack(alignment: .top) {
                ClockPanel(scale: k)
                    .frame(height: k * 34, alignment: .center)
                    .hudCard(k)
                Spacer(minLength: 8)
                AltitudePanel(altitude: model.displayAltitude, scale: k)
                    .frame(height: k * 34, alignment: .center)
                    .hudCard(k)
            }

            // ── 导航摘要：还有多久 / 多远 / 几点到 ──
            NavSummaryPanel(nav: model.displayNav, scale: k)
                .hudCard(k)
                .opacity(model.displayNav?.isActive == true ? 1 : 0)
                .padding(.top, k * 10)

            // ── 车速放靠上一点（用户要求）──
            // 上面有黑色渐变垫底；渐变到屏幕中段就淡出，
            // 下面整块是通亮的地图和导航路线，不受遮罩影响。
            SpeedSlot(image: crossImage,
                      scale: k,
                      swipeAxis: .horizontal,                     // 竖屏：左右滑切换
                      tireAvailable: model.displayTire != nil,
                      page: $speedSlotPage,
                      onTap: { revealSettingsButton() }) {
                SpeedGauge(speed: model.displaySpeed, scale: k * 1.05)
            } tire: {
                TirePressurePanel(tire: model.displayTire, scale: k * 1.05)
            }
            .padding(.top, k * 4)

            // 转向卡已移除，这里只留一个弹性空隙（原来它前后各一个）
            Spacer(minLength: k * 16)

            // ── 退出导航（用户 2026-09-24 要求：竖屏放在车辆总里程上面）──
            if model.routeDest != nil {
                NavExitButton(scale: k) { model.endNavigation() }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.bottom, k * 6)
            }

            // ── 底栏：竖屏宽度只有一半，两栏并排会溢出，拆两行 ──
            // 比例尺删掉后，总里程挪到它原来的位置（右下角）——
            // 用户要求：「竖屏下的车辆总公里数放到现在比例尺缩放的地方」。
            VStack(alignment: .leading, spacing: k * 7) {
                BatteryRangePanel(soc: model.displaySoc,
                                  range: model.displayRange,
                                  scale: k)
                    .hudCard(k)
                HStack(alignment: .bottom) {
                    GearPanel(gear: model.displayGear, scale: k)
                        .hudCard(k)
                    Spacer(minLength: 8)
                    OdometerPanel(odometer: model.displayOdometer, scale: k)
                        .hudCard(k)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)

            LinkBadge(status: model.link, scale: k)
                .padding(.top, k * 8)
        }
    }

    /// 布局缩放系数。
    ///
    /// 以**短边**为基准：横屏时短边是高度、竖屏时短边是宽度。
    /// 这样同一个元素在两种方向下大小一致 —— 如果还用高度当基准，
    /// 竖屏高度 844 会把所有字撑到上限，挤成一团。
    private func scaleFactor(for geo: GeometryProxy) -> CGFloat {
        let base = min(geo.size.width, geo.size.height)
        return min(max(base / 390.0, 0.62), 1.28)
    }
}

// MARK: - 视距（缩放）控制 —— 已移除
//
// ⚠️ 2026-09-23 按用户要求删掉了右下那条「− 25米 ＋」（原来叫 ZoomPill）。
// 原因：导航用的是 AMapNaviDriveView，**它自己会按路况/车速缩放**，
// 我们那条尺子只对「非导航地图」的 MAMapView.zoomLevel 生效，导航时纯属误导。
//
// 视距本身没丢：非导航地图仍然按车速自动调档（见下面的 MapZoom.autoOffset
// 和 onAppear/onChange 里写回 zoom 的那段）。要手动调档得重新加 UI —— 先问用户。

// MARK: - 点一下屏幕才浮出来的设置按钮

private struct SettingsButton: View {
    let scale: CGFloat
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Image(systemName: "gearshape.fill")
                .font(.system(size: scale * 15, weight: .semibold))
                .foregroundStyle(.white.opacity(0.88))
                .frame(width: scale * 40, height: scale * 40)
                .background(Circle().fill(Color.black.opacity(0.5)))
                .overlay(
                    Circle().stroke(Color.white.opacity(0.20), lineWidth: 1)
                )
                .shadow(color: .black.opacity(0.3), radius: 6, y: 2)
        }
        .buttonStyle(.plain)
    }
}

// MARK: - 连接状态

private struct LinkBadge: View {
    let status: LinkStatus
    let scale: CGFloat
    @State private var visible = true

    var body: some View {
        HStack(spacing: scale * 5) {
            Circle()
                .fill(status.isOnline ? Color.green : Color.orange)
                .frame(width: scale * 6, height: scale * 6)
            Text(status.isOnline ? "车机已连接" : status.label)
                .font(.system(size: scale * 10, weight: .medium))
                .foregroundStyle(.white.opacity(0.5))
        }
        .padding(.horizontal, scale * 10)
        .padding(.vertical, scale * 3)
        .background(Capsule().fill(Color.black.opacity(0.28)))
        .opacity(status.isOnline && !visible ? 0.0 : 1.0)
        .onAppear {
            withAnimation(.easeInOut(duration: 0.6).delay(4)) { visible = false }
        }
    }
}

// MARK: - 屏幕两边的光（转向绿 / 超速红）

/// 屏幕左右两条边缘光。
///
/// * **转向灯（绿）**：跟着车机转向灯闪（约 1.5Hz，和真车一个节奏）——
///   左灯亮只闪左边，右灯亮只闪右边，双闪两边一起。
/// * **超速（红）**：车速超过当前路段限速时两边同时呼吸。
///
/// 为什么自己画：高德的 `showOverSpeedPulse` 头文件里写明是**收费接口**
/// （要提工单向高德申请），而且它只画在导航视图内部；转向灯更是 SDK
/// 完全没有的数据。这层只是叠在 HUD 上的装饰，`allowsHitTesting(false)`，
/// 不吃手势、不影响地图。
struct EdgeGlow: View {
    enum Kind { case turn, over }

    let kind: Kind
    let left: Bool
    let right: Bool
    let scale: CGFloat

    @State private var lit = false

    var body: some View {
        let color: Color = (kind == .turn) ? Color(hex: 0x2BE05A) : Color(hex: 0xFF3B30)
        // 转向灯是"闪"，超速是"呼吸"（慢一点，别晃眼）
        let period: Double = (kind == .turn) ? 0.34 : 0.75

        ZStack {
            if left { edge(color, isLeft: true) }
            if right { edge(color, isLeft: false) }
        }
        .opacity(lit ? 1 : 0.06)
        .allowsHitTesting(false)
        .onAppear {
            withAnimation(.easeInOut(duration: period).repeatForever(autoreverses: true)) {
                lit = true
            }
        }
    }

    /// 一条从边缘往里淡出的光带
    private func edge(_ color: Color, isLeft: Bool) -> some View {
        LinearGradient(
            colors: [color.opacity(0.92), color.opacity(0.0)],
            startPoint: isLeft ? .leading : .trailing,
            endPoint: isLeft ? .trailing : .leading
        )
        .frame(width: max(60, 96 * scale))
        .blur(radius: 5)
        .frame(maxWidth: .infinity, maxHeight: .infinity,
               alignment: isLeft ? .leading : .trailing)
    }
}

// MARK: - 退出导航按钮

/// 「退出导航」（用户 2026-09-24 要求加的）。
///
/// 竖屏放在车辆总里程上面、横屏放在原海拔的位置（横屏不再显示海拔）。
/// 只在导航中显示。点了**只结束 iPhone 这边**的导航（见
/// `DashboardModel.endNavigation`）—— 车机那边照常导，换目的地会自动跟上新路线；
/// 车机继续推同一条目的地时不会把路线算回来。
struct NavExitButton: View {
    let scale: CGFloat
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: scale * 6) {
                Image(systemName: "xmark.circle.fill")
                    .font(.system(size: scale * 14, weight: .semibold))
                Text("退出导航")
                    .font(.system(size: scale * 13, weight: .semibold))
            }
            .foregroundStyle(.white.opacity(0.92))
            .frame(height: scale * 32)
            .padding(.horizontal, scale * 14)
            .background(Capsule().fill(Color.black.opacity(0.55)))
            .overlay(Capsule().stroke(Color.white.opacity(0.22), lineWidth: 1))
        }
        .buttonStyle(.plain)
    }
}

// MARK: - 「速度」那一格（速度表 / 胎压 两页可切换）

/// 「速度」那一格。**两张页**：速度表、胎压。
///
/// 用户 2026-09-26 的要求（原话确认过）：
/// * 胎压显示在**时速那一格**；
/// * **竖屏左右滑**切到胎压、**横屏上下滑**切到胎压；
/// * **胎压的层级要高于路口图** —— 显示胎压时路口图**不显示**；
///   切换回时速时，路口图**照旧盖住时速**。
///
/// 实现就是"按当前页决定画什么"，而不是靠 ZStack 顺序去赌：
/// * **时速页** = 速度表 +（有图就）路口图盖上来 —— 和以前完全一样；
/// * **胎压页** = 只画胎压面板，**路口图这一段代码根本不进** ⇒ 不可能遮挡胎压。
///
/// 路口图仍然和这一格共用位置、高度锁定在 `150*scale`（比速度表矮）——
/// 那是 2026-09-24 定下的"只遮速度、不挡别的元素、布局不抖"，别改。
struct SpeedSlot<Gauge: View, Tire: View>: View {
    let image: UIImage?
    let scale: CGFloat
    /// 和速度表的对齐方式保持一致（竖屏 .center、横屏 .leading）
    var align: Alignment = .center
    /// 滑动轴：竖屏 `.horizontal`（左右滑）、横屏 `.vertical`（上下滑）
    var swipeAxis: Axis = .horizontal
    /// 有没有胎压数据。没有就锁在速度页（免得滑到一页全是 "--"）
    let tireAvailable: Bool
    @Binding var page: SpeedSlotPage
    /// 点一下这一格 ⇒ 交给外层"浮出设置齿轮"。
    /// ⚠️ 这格现在必须吃手势（要能滑），所以不能再 `allowsHitTesting(false)`；
    ///    为了不把外层"点屏幕浮出齿轮"吞掉，这里自己把点击转出去。
    let onTap: () -> Void
    @ViewBuilder let gauge: () -> Gauge
    @ViewBuilder let tire: () -> Tire

    var body: some View {
        ZStack(alignment: align) {
            if page == .tire {
                // 胎压页：**不画路口图**（用户要求：胎压的层级高于路口图）
                tire()
            } else {
                gauge()

                if let image {
                    Image(uiImage: image)
                        .resizable()
                        .aspectRatio(25.0 / 16.0, contentMode: .fit)
                        .frame(maxHeight: 150 * scale)
                        .background(Color(hex: 0x101418))       // 不透明：真把速度盖住
                        .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                        .overlay(
                            RoundedRectangle(cornerRadius: 12, style: .continuous)
                                .stroke(Color.white.opacity(0.16), lineWidth: 1)
                        )
                        .shadow(color: .black.opacity(0.4), radius: 12, y: 2)
                        .transition(.opacity)
                }
            }
        }
        .overlay(alignment: .bottom) { pageDots }
        .animation(.easeInOut(duration: 0.18), value: image == nil)
        .animation(.easeInOut(duration: 0.18), value: page)
        .contentShape(Rectangle())
        .onTapGesture(perform: onTap)
        .gesture(
            DragGesture(minimumDistance: 18)
                .onEnded { v in
                    guard tireAvailable else { return }
                    let primary = (swipeAxis == .horizontal)
                        ? v.translation.width : v.translation.height
                    guard abs(primary) > 28 else { return }
                    // 往"下一张"的方向滑 = 胎压；往回滑 = 速度
                    let next: SpeedSlotPage = (primary < 0) ? .tire : .speed
                    if next != page { page = next }
                }
        )
    }

    /// 底部两个小点：告诉用户"这一格有两页、可以滑"（没胎压数据就不显示）
    @ViewBuilder private var pageDots: some View {
        if tireAvailable {
            HStack(spacing: scale * 4) {
                dot(active: page == .speed)
                dot(active: page == .tire)
            }
            .padding(.bottom, scale * 2)
            .allowsHitTesting(false)
        }
    }

    private func dot(active: Bool) -> some View {
        Circle()
            .fill(Color.white.opacity(active ? 0.85 : 0.28))
            .frame(width: scale * 5, height: scale * 5)
    }
}

/// 「速度」那一格的两张页
enum SpeedSlotPage {
    case speed
    case tire
}
