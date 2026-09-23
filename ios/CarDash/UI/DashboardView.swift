import SwiftUI

struct DashboardView: View {
    @ObservedObject var model: DashboardModel

    @State private var showSettings = false

    var body: some View {
        ZStack {
            // 背景铺满整块屏幕（含灵动岛/圆角区域）
            BackgroundScene()
                .ignoresSafeArea()

            // 内容走安全区：横屏时灵动岛占据左侧约 59pt，
            // 之前在这里 ignoresSafeArea 会让左上角的日期和音乐面板被它压住
            // （iPhone Air / 17 系列尤其明显）。
            GeometryReader { geo in
                let k = scaleFactor(for: geo)

                Group {
                    if geo.size.height > geo.size.width {
                        portraitLayout(k: k)
                    } else {
                        landscapeLayout(geo: geo, k: k)
                    }
                }
                .padding(.horizontal, k * 22)
                .padding(.top, k * 14)
                .padding(.bottom, k * 10)
                .frame(width: geo.size.width, height: geo.size.height)
                .contentShape(Rectangle())
                .onTapGesture { showSettings = true }
            }
        }
        .sheet(isPresented: $showSettings) {
            SettingsSheet(model: model)
        }
    }


    // MARK: - 横屏版式（原来的三栏）

    private func landscapeLayout(geo: GeometryProxy, k: CGFloat) -> some View {
        ZStack {
            VStack(spacing: 0) {
                // ── 顶栏：左上日期时间 / 中间导航摘要 / 右上海拔 ──
                // 用 ZStack 让中间那块**真正落在屏幕正中**。
                // 原来用 HStack + 两侧 Spacer：左右两块宽度本来就不一样，
                // Spacer 撑出来的「中间」会偏，看着就没居中。
                ZStack {
                    ClockPanel(scale: k)
                        .frame(maxWidth: .infinity, alignment: .leading)

                    NavSummaryPanel(nav: model.displayNav, scale: k)
                        .padding(.top, k * 4)

                    AltitudePanel(altitude: model.displayAltitude, scale: k)
                        .frame(maxWidth: .infinity, alignment: .trailing)
                }

                Spacer(minLength: 8)

                // ── 主区：左音乐 / 中车速 / 右导航 ──
                HStack(alignment: .center, spacing: 0) {
                    // 左右两块必须**等宽**，中间的车速表才会落在屏幕正中。
                    // 之前左边 0.26、右边 0.31，车速表被挤得偏左。
                    MusicPanel(music: model.displayMusic, scale: k)
                        .frame(width: geo.size.width * 0.30, alignment: .leading)

                    SpeedGauge(speed: model.displaySpeed, scale: k)
                        .frame(maxWidth: .infinity)

                    NavigationPanel(nav: model.displayNav, scale: k)
                        .frame(width: geo.size.width * 0.30, alignment: .trailing)
                }

                Spacer(minLength: 8)

                // ── 底栏：左电量续航 / 右档位总里程 ──
                HStack(alignment: .bottom) {
                    BatteryRangePanel(soc: model.displaySoc,
                                      range: model.displayRange,
                                      scale: k)
                    Spacer(minLength: 12)
                    GearOdometerPanel(gear: model.displayGear,
                                      odometer: model.displayOdometer,
                                      scale: k)
                }
            }

            // 连接状态指示灯（不干扰主视觉）
            LinkBadge(status: model.link, scale: k)
                .frame(maxWidth: .infinity, maxHeight: .infinity,
                       alignment: .bottom)
                .padding(.bottom, k * 2)
        }
    }

    // MARK: - 竖屏版式

    /// 竖屏是「窄而高」，三栏并排会挤成一团，所以改成上下排。
    ///
    /// 顺序按开车时扫视的优先级：车速居中当主角，导航紧贴其下，
    /// 音乐和底栏放最下面 —— 眼睛扫一眼就能拿到最要紧的两个数。
    private func portraitLayout(k: CGFloat) -> some View {
        VStack(spacing: 0) {
            // ── 顶栏：日期时间 / 海拔 ──
            HStack(alignment: .top) {
                ClockPanel(scale: k)
                Spacer(minLength: 8)
                AltitudePanel(altitude: model.displayAltitude, scale: k)
            }

            // ── 导航摘要：还有多久 / 多远 / 几点到 ──
            NavSummaryPanel(nav: model.displayNav, scale: k)
                .padding(.top, k * 10)

            // 连接状态：横屏放最底部（那里空），竖屏底部被底栏占满了，
            // 挪到这一带的留白里。
            LinkBadge(status: model.link, scale: k)
                .padding(.top, k * 8)

            Spacer(minLength: k * 6)

            // ── 主角：车速 ──
            SpeedGauge(speed: model.displaySpeed, scale: k * 1.05)

            Spacer(minLength: k * 6)

            // ── 导航：多少米 + 进入哪条路 ──
            NavigationPanel(nav: model.displayNav, scale: k)
                .frame(maxWidth: .infinity, alignment: .trailing)

            // ── 音乐 ──
            MusicPanel(music: model.displayMusic, scale: k)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.top, k * 22)

            Spacer(minLength: k * 6)

            // ── 底栏 ──
            // 竖屏可用宽度只有横屏一半，档位+电量挤一行会溢出
            // （实测两栏加起来约 349pt，而窄屏只剩 331pt），
            // 所以拆成两行 —— 竖屏缺的是宽度，不是高度。
            VStack(alignment: .leading, spacing: k * 7) {
                BatteryRangePanel(soc: model.displaySoc,
                                  range: model.displayRange,
                                  scale: k)
                GearOdometerPanel(gear: model.displayGear,
                                  odometer: model.displayOdometer,
                                  scale: k)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
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
