import SwiftUI

struct DashboardView: View {
    @ObservedObject var model: DashboardModel

    @State private var now = Date()
    @State private var showSettings = false

    private let ticker = Timer.publish(every: 1, on: .main, in: .common).autoconnect()

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

                ZStack {
                    VStack(spacing: 0) {
                        // ── 顶栏：左上日期时间 / 右上海拔 ──
                        HStack(alignment: .top) {
                            ClockPanel(now: now, scale: k)
                            Spacer(minLength: 12)
                            AltitudePanel(altitude: model.displayAltitude, scale: k)
                        }

                        Spacer(minLength: 8)

                        // ── 主区：左音乐 / 中车速 / 右导航 ──
                        HStack(alignment: .center, spacing: 0) {
                            MusicPanel(music: model.displayMusic, scale: k)
                                .frame(width: geo.size.width * 0.26, alignment: .leading)

                            SpeedGauge(speed: model.displaySpeed, scale: k)
                                .frame(maxWidth: .infinity)

                            NavigationPanel(nav: model.displayNav, scale: k)
                                .frame(width: geo.size.width * 0.31, alignment: .trailing)
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
                .padding(.horizontal, k * 22)
                .padding(.top, k * 14)
                .padding(.bottom, k * 10)
                .frame(width: geo.size.width, height: geo.size.height)
                .contentShape(Rectangle())
                .onTapGesture { showSettings = true }
            }
        }
        .onReceive(ticker) { now = $0 }
        .sheet(isPresented: $showSettings) {
            SettingsSheet(model: model)
        }
    }

    /// 布局缩放系数。
    ///
    /// 以横屏高度为基准，加下限避免小屏糊成一团、加上限避免大屏
    /// （iPhone 17 Pro Max / Air）把字撑得过大。横向不参与缩放 ——
    /// 各面板是按屏幕宽度的比例分栏的，本身就会自适应。
    private func scaleFactor(for geo: GeometryProxy) -> CGFloat {
        min(max(geo.size.height / 390.0, 0.62), 1.28)
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
