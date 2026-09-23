import SwiftUI
import UIKit

@main
struct CarDashApp: App {
    var body: some Scene {
        WindowGroup {
            RootView()
                .statusBarHidden(true)
                .persistentSystemOverlays(.hidden)
                .preferredColorScheme(.dark)
        }
    }
}

struct RootView: View {
    @StateObject private var model = DashboardModel()
    @Environment(\.scenePhase) private var scenePhase
    /// 启动遮罩。用户要求：「加个启动动画来初始化这些你需要准备的 ——
    /// 我要在使用软件的时候看不到这些'等一会才调整到正确位置'」。
    /// 地图首帧、导航引擎首次创建（同步阻塞）、第一次定位都在这段时间里做完。
    @State private var launching = true

    var body: some View {
        ZStack {
            DashboardView(model: model)
                .onChange(of: scenePhase) { _ in
                    if scenePhase == .active {
                        UIApplication.shared.isIdleTimerDisabled = true
                    }
                }

            if launching {
                LaunchOverlay()
                    .transition(.opacity)
                    .zIndex(1)
            }
        }
        .onAppear {
            // 仪表盘必须常亮
            UIApplication.shared.isIdleTimerDisabled = true
            model.start()
            // ⚠️ 1.8 秒是"够用又不拖"的经验值：够地图出首帧、够引擎初始化，
            // 又短到不至于让人觉得软件启动慢。要调就改这个数。
            DispatchQueue.main.asyncAfter(deadline: .now() + 1.8) {
                withAnimation(.easeOut(duration: 0.5)) { launching = false }
            }
        }
    }
}

/// 启动遮罩：一块纯深色背景 + 应用名，盖住地图/引擎初始化的那一两秒。
///
/// 为什么要它：导航视图/普通地图在拿到尺寸之前没法设置「自车图标位置」，
/// 所以第一帧往往是"车在正中"，随后才挪到该在的位置 —— 直接看就是地图先顿一下
/// 再滑一下。用遮罩把这段藏掉，用户看到的就是"打开即到位"。
struct LaunchOverlay: View {
    var body: some View {
        ZStack {
            Color(hex: 0x0B0D10).ignoresSafeArea()
            VStack(spacing: 12) {
                Text("CarDash")
                    .font(.system(size: 32, weight: .semibold, design: .rounded))
                    .foregroundStyle(.white.opacity(0.95))
                Text("正在初始化地图与定位")
                    .font(.system(size: 13))
                    .foregroundStyle(.white.opacity(0.45))
                ProgressView()
                    .tint(.white.opacity(0.55))
                    .padding(.top, 4)
            }
        }
        // 挡住底下的误触（比如"模拟导航"按钮正好在手指落点）
        .contentShape(Rectangle())
        .allowsHitTesting(true)
    }
}
