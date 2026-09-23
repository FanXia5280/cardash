import SwiftUI

struct DashboardView: View {
    @ObservedObject var model: DashboardModel

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
            // ── 背景：导航地图 ──
            if let reason = model.mapUnavailableReason {
                MapPlaceholder(reason: reason)
                    .ignoresSafeArea()
            } else if let dest = model.routeDest, amapAgreed {
                // 车机报了目的地：整屏交给高德官方导航视图。
                // 原版路线、3D 车标、红绿灯倒计时、电子眼，全是 SDK 自带。
                NaviKitNavView(from: model.coord, to: dest)
                    .ignoresSafeArea()
            } else {
                // 普通地图：**画得比屏幕大，再整体挪一点**。
                //
                // 地图里的「车头 / 当前位置」永远在视图正中，所以把视图画大 1.35 倍
                // 再往右（横屏）/ 往下（竖屏）推，车头就落在屏幕的非中心位置了 ——
                // 和导航视图的 screenAnchor 保持一致（横屏 0.58 / 竖屏 y 0.60）。
                //
                // 为什么不直接改经纬度：相机有俯角、车头还会旋转，屏幕位移换算成
                // 经纬度随朝向/缩放变化，算不准；把视图画大再挪，跟数学无关，稳。
                // ⚠️ 位移量不能超过 (1.35-1)/2 = 0.175 个屏幕，否则边缘露白。
                GeometryReader { g in
                    let anchor: CGPoint = g.size.height > g.size.width
                        ? CGPoint(x: 0.50, y: 0.60)      // 竖屏：往下一点（别被车速压住）
                        : CGPoint(x: 0.58, y: 0.50)      // 横屏：往右一点（左边留给大号车速）
                    DashboardMapView(coord: model.coord,
                                     heading: model.heading,
                                     route: model.route,
                                     dest: model.routeDest,
                                     amapAgreed: amapAgreed,
                                     useRasterFallback: useAmapTiles,
                                     zoom: zoom,
                                     speed: model.displaySpeed,
                                     segments: model.routeSegments)
                        .frame(width: g.size.width * 1.35, height: g.size.height * 1.35)
                        .offset(x: (anchor.x - 0.5) * g.size.width,
                                y: (anchor.y - 0.5) * g.size.height)
                }
                // 地图**铺满整屏**。
                // 上一版在这里套了个径向遮罩当「渐变地图」，结果四角全黑、
                // 只剩中间一个聚光斑，又脏又挡地图 —— 参考图不是这样，
                // 它是整块地图通亮，只在上下压两条渐变带给 HUD 垫底。
                .ignoresSafeArea()
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

                AltitudePanel(altitude: model.displayAltitude, scale: k)
                    .frame(height: k * 34, alignment: .center)
                    .hudCard(k)
                    .frame(maxWidth: .infinity, alignment: .trailing)
            }

            Spacer(minLength: 8)

            // ── 车速移到左边（参考图里大 P 的位置），右边整块留给地图 ──
            // 左边有黑色渐变垫底，白字压在上面读得清；
            // 右边地图通亮，路线不会被挡。
            SpeedGauge(speed: model.displaySpeed, scale: k, align: .leading)
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
            SpeedGauge(speed: model.displaySpeed, scale: k * 1.05)
                .padding(.top, k * 4)

            // 转向卡已移除，这里只留一个弹性空隙（原来它前后各一个）
            Spacer(minLength: k * 16)

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
