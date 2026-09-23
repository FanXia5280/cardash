import SwiftUI

struct DashboardView: View {
    @ObservedObject var model: DashboardModel

    @State private var showSettings = false
    /// 点一下屏幕才浮出设置按钮。
    /// 之前是「点屏幕任意位置直接进设置」，开车时手一碰就弹走了，
    /// 改成先浮出一个小齿轮，点它才进设置页。
    @State private var showSettingsButton = false
    @State private var hideWork: DispatchWorkItem?

    var body: some View {
        ZStack {
            // ── 背景：导航地图 ──
            // 定位不可用时退回原来的动态背景，不至于开天窗。
            if let reason = model.mapUnavailableReason {
                MapPlaceholder(reason: reason)
                    .ignoresSafeArea()
            } else {
                NavMapView(coord: model.coord,
                           heading: model.heading,
                           track: model.track)
                    .ignoresSafeArea()
            }

            // 压一层很淡的上下暗角：白色 HUD 压在任何地图底色上都能读清，
            // 中间留亮，视线还是在地图上
            LinearGradient(
                colors: [.black.opacity(0.36), .black.opacity(0.10), .black.opacity(0.42)],
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

                    // 浮出来的设置按钮：放在底栏上方，不挡任何数据
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
            // 用 ZStack 让中间那块**真正落在屏幕正中**。
            // 原来用 HStack + 两侧 Spacer：左右两块宽度本来就不一样，
            // Spacer 撑出来的「中间」会偏，看着就没居中。
            ZStack {
                ClockPanel(scale: k)
                    .hudCard(k)
                    .frame(maxWidth: .infinity, alignment: .leading)

                NavSummaryPanel(nav: model.displayNav, scale: k)
                    .hudCard(k)
                    .padding(.top, k * 4)

                AltitudePanel(altitude: model.displayAltitude, scale: k)
                    .hudCard(k)
                    .frame(maxWidth: .infinity, alignment: .trailing)
            }

            Spacer(minLength: 8)

            // ── 主区：左音乐 / 中车速 ──
            // 左侧给了音乐卡就占掉一块宽度，右侧对称留一块空位，
            // 中间的车速表才会落在屏幕正中。
            HStack(alignment: .center, spacing: 0) {
                MusicPanel(music: model.displayMusic, scale: k)
                    .hudCard(k)
                    .frame(width: geo.size.width * 0.34, alignment: .leading)

                SpeedGauge(speed: model.displaySpeed, scale: k)
                    .frame(maxWidth: .infinity)

                Color.clear
                    .frame(width: geo.size.width * 0.34)
            }

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

    /// 竖屏是「窄而高」，三栏并排会挤成一团，所以改成上下排。
    ///
    /// 顺序按开车时扫视的优先级：车速居中当主角，转向卡紧贴其下，
    /// 音乐和底栏放最下面 —— 扫一眼就能拿到最要紧的两个数。
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
                .padding(.top, k * 10)

            Spacer(minLength: k * 6)

            // ── 主角：车速 ──
            SpeedGauge(speed: model.displaySpeed, scale: k * 1.05)

            Spacer(minLength: k * 6)

            // ── 转向卡 ──
            TurnCard(nav: model.displayNav, scale: k)

            Spacer(minLength: k * 6)

            // ── 音乐 ──
            MusicPanel(music: model.displayMusic, scale: k)
                .hudCard(k)
                .frame(maxWidth: .infinity, alignment: .leading)

            Spacer(minLength: k * 6)

            // ── 底栏 ──
            // 竖屏可用宽度只有横屏一半，档位+电量挤一行会溢出
            //（实测两栏加起来约 349pt，而窄屏只剩 331pt），
            // 所以拆成两行 —— 竖屏缺的是宽度，不是高度。
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

            // 连接状态：竖屏底部被底栏占满，放最后一行
            LinkBadge(status: model.link, scale: k)
                .padding(.top, k * 8)
        }
    }

    /// 布局缩放系数。
    ///
    /// 以**短边**为基准：横屏时短边是高度、竖屏时短边是宽度。
    /// 这样同一个元素在两种方向下大小一致 —— 如果还用高度当基准，
    /// 竖屏高度 844 会把所有字撑到上限，挤成一团。
    /// 下限避免小屏糊在一起，上限避免大屏（Pro Max / Air）字过大。
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
