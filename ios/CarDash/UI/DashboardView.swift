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
    @AppStorage("mapZoom") private var zoom = 16
    @State private var showPrivacy = false

    var body: some View {
        ZStack {
            // ── 背景：导航地图 ──
            if let reason = model.mapUnavailableReason {
                MapPlaceholder(reason: reason)
                    .ignoresSafeArea()
            } else {
                DashboardMapView(coord: model.coord,
                                 heading: model.heading,
                                 route: model.route,
                                 dest: model.routeDest,
                                 amapAgreed: amapAgreed,
                                 useRasterFallback: useAmapTiles,
                                 zoom: zoom)
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
                    // 参考图那种自然的压暗就是这个差别
                    if geo.size.height > geo.size.width {
                        LinearGradient(
                            stops: [
                                .init(color: .black.opacity(0.72), location: 0.00),
                                .init(color: .black.opacity(0.56), location: 0.22),
                                .init(color: .black.opacity(0.34), location: 0.45),
                                .init(color: .black.opacity(0.14), location: 0.60),
                                .init(color: .black.opacity(0.00), location: 0.76),
                            ],
                            startPoint: .top, endPoint: .bottom
                        )
                        .ignoresSafeArea()
                        .allowsHitTesting(false)
                    } else {
                        LinearGradient(
                            stops: [
                                .init(color: .black.opacity(0.72), location: 0.00),
                                .init(color: .black.opacity(0.56), location: 0.24),
                                .init(color: .black.opacity(0.32), location: 0.48),
                                .init(color: .black.opacity(0.13), location: 0.64),
                                .init(color: .black.opacity(0.00), location: 0.80),
                            ],
                            startPoint: .leading, endPoint: .trailing
                        )
                        .ignoresSafeArea()
                        .allowsHitTesting(false)
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

            Spacer(minLength: 8)

            // ── 转向卡：参考图里那个「110米 进入南海路」，放正下方 ──
            TurnCard(nav: model.displayNav, scale: k)

            Spacer(minLength: 10)

            // ── 底栏：左电量续航 / 右档位总里程 ──
            HStack(alignment: .bottom) {
                BatteryRangePanel(soc: model.displaySoc,
                                  range: model.displayRange,
                                  scale: k)
                    .hudCard(k)
                Spacer(minLength: 10)
                ZoomPill(zoom: zoom, scale: k) {
                    zoom = min(19, zoom + 1)
                } onZoomOut: {
                    zoom = max(12, zoom - 1)
                }
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

            Spacer(minLength: k * 16)

            // ── 转向卡 ──
            TurnCard(nav: model.displayNav, scale: k)

            Spacer(minLength: k * 6)

            // ── 底栏：竖屏宽度只有一半，两栏并排会溢出，拆两行 ──
            VStack(alignment: .leading, spacing: k * 7) {
                BatteryRangePanel(soc: model.displaySoc,
                                  range: model.displayRange,
                                  scale: k)
                    .hudCard(k)
                HStack {
                    GearOdometerPanel(gear: model.displayGear,
                                      odometer: model.displayOdometer,
                                      scale: k)
                        .hudCard(k)
                    Spacer(minLength: 8)
                    ZoomPill(zoom: zoom, scale: k) {
                        zoom = min(19, zoom + 1)
                    } onZoomOut: {
                        zoom = max(12, zoom - 1)
                    }
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

// MARK: - 视距（缩放）控制

/// 对应高德导航里那个「50米 / 100米 / 200米」的视距缩放。
/// 直接调 MAMapView.zoomLevel（高德官方 API），越大越近。
/// 做成一条横向胶囊，样式和其它 HUD 卡一致，不突兀。
private struct ZoomPill: View {
    let zoom: Int
    let scale: CGFloat
    let onZoomIn: () -> Void
    let onZoomOut: () -> Void

    var body: some View {
        HStack(spacing: scale * 12) {
            Button(action: onZoomOut) {
                Image(systemName: "minus")
                    .font(.system(size: scale * 13, weight: .semibold))
                    .foregroundStyle(.white.opacity(0.85))
                    .frame(width: scale * 26, height: scale * 26)
                    .background(Circle().fill(Color.white.opacity(0.10)))
            }
            .buttonStyle(.plain)

            Text(ZoomPill.scaleLabel(zoom))
                .font(.system(size: scale * 13, weight: .semibold, design: .rounded))
                .monospacedDigit()
                .foregroundStyle(.white.opacity(0.92))
                .frame(minWidth: scale * 46)

            Button(action: onZoomIn) {
                Image(systemName: "plus")
                    .font(.system(size: scale * 13, weight: .semibold))
                    .foregroundStyle(.white.opacity(0.85))
                    .frame(width: scale * 26, height: scale * 26)
                    .background(Circle().fill(Color.white.opacity(0.10)))
            }
            .buttonStyle(.plain)
        }
        .hudCard(scale)
    }

    /// zoomLevel → 比例尺文案。高德的尺子随 zoom 走，近似值够用。
    static func scaleLabel(_ zoom: Int) -> String {
        switch zoom {
        case 12: return "5公里"
        case 13: return "2公里"
        case 14: return "1公里"
        case 15: return "500米"
        case 16: return "200米"
        case 17: return "100米"
        case 18: return "50米"
        case 19: return "25米"
        default: return "\(zoom)"
        }
    }
}

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
