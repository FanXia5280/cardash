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

    var body: some View {
        ZStack {
            // ── 背景：导航地图 ──
            if let reason = model.mapUnavailableReason {
                MapPlaceholder(reason: reason)
                    .ignoresSafeArea()
            } else {
                NavMapView(coord: model.coord,
                           heading: model.heading,
                           route: model.route,
                           dest: model.routeDest,
                           useAmapTiles: useAmapTiles)
                    // 地图**铺满整屏**。
                    // 上一版在这里套了个径向遮罩当「渐变地图」，结果四角全黑、
                    // 只剩中间一个聚光斑，又脏又挡地图 —— 参考图不是这样，
                    // 它是整块地图通亮，只在上下压两条渐变带给 HUD 垫底。
                    .ignoresSafeArea()
            }

            // 只在上、下各压一条渐变带给 HUD 垫底，**中间大片保持通亮**。
            // 这样既保证白字读得清，又不会把地图挡住 —— 和参考图一致。
            LinearGradient(
                stops: [
                    .init(color: .black.opacity(0.58), location: 0.00),
                    .init(color: .black.opacity(0.10), location: 0.15),
                    .init(color: .black.opacity(0.04), location: 0.58),
                    .init(color: .black.opacity(0.40), location: 0.82),
                    .init(color: .black.opacity(0.66), location: 1.00),
                ],
                startPoint: .top, endPoint: .bottom
            )
            .ignoresSafeArea()
            .allowsHitTesting(false)

            GeometryReader { geo in
                let k = scaleFactor(for: geo)

                ZStack {
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
                    .hudCard(k)
                    .frame(maxWidth: .infinity, alignment: .trailing)
            }

            Spacer(minLength: 8)

            // ── 中间：只有车速 ──
            // 音乐卡已按要求移除，地图这块不再被占宽度。
            SpeedGauge(speed: model.displaySpeed, scale: k)
                .frame(maxWidth: .infinity)

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
                Spacer(minLength: 12)
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
                ClockPanel(scale: k).hudCard(k)
                Spacer(minLength: 8)
                AltitudePanel(altitude: model.displayAltitude, scale: k).hudCard(k)
            }

            // ── 导航摘要：还有多久 / 多远 / 几点到 ──
            NavSummaryPanel(nav: model.displayNav, scale: k)
                .hudCard(k)
                .opacity(model.displayNav?.isActive == true ? 1 : 0)
                .padding(.top, k * 10)

            Spacer(minLength: k * 6)

            // ── 主角：车速 ──
            SpeedGauge(speed: model.displaySpeed, scale: k * 1.05)

            Spacer(minLength: k * 6)

            // ── 转向卡 ──
            TurnCard(nav: model.displayNav, scale: k)

            Spacer(minLength: k * 6)

            // ── 底栏：竖屏宽度只有一半，两栏并排会溢出，拆两行 ──
            VStack(alignment: .leading, spacing: k * 7) {
                BatteryRangePanel(soc: model.displaySoc,
                                  range: model.displayRange,
                                  scale: k)
                    .hudCard(k)
                GearOdometerPanel(gear: model.displayGear,
                                  odometer: model.displayOdometer,
                                  scale: k)
                    .hudCard(k)
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
