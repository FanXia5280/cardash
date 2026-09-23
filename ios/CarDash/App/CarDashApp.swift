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
    /// 外圈一直在转、车标在呼吸（用户要求「循环绘制」）
    @State private var spin = false
    @State private var breathe = false

    var body: some View {
        ZStack {
            Color(hex: 0x0B0D10).ignoresSafeArea()
            VStack(spacing: 14) {
                emblem
                Text("CarDash")
                    .font(.system(size: 30, weight: .semibold, design: .rounded))
                    .foregroundStyle(.white.opacity(0.95))
                Text("正在初始化地图与定位")
                    .font(.system(size: 13))
                    .foregroundStyle(.white.opacity(0.45))
            }
        }
        // 挡住底下的误触（比如"模拟导航"按钮正好在手指落点）
        .contentShape(Rectangle())
        .allowsHitTesting(true)
        .onAppear {
            // 循环动画：遮罩还在就一直转
            withAnimation(.linear(duration: 1.6).repeatForever(autoreverses: false)) {
                spin = true
            }
            withAnimation(.easeInOut(duration: 1.1).repeatForever(autoreverses: true)) {
                breathe = true
            }
        }
    }

    /// 中间那个标：外圈转 + 车标呼吸
    private var emblem: some View {
        ZStack {
            Circle()
                .stroke(Color.white.opacity(0.10), lineWidth: 1)
                .frame(width: 86, height: 86)
            Circle()
                .trim(from: 0, to: 0.20)
                .stroke(Color(hex: 0x2E7BE8),
                        style: StrokeStyle(lineWidth: 3, lineCap: .round))
                .frame(width: 86, height: 86)
                .rotationEffect(.degrees(spin ? 360 : 0))
            logo
        }
        .opacity(breathe ? 1 : 0.78)
    }

    /// 车标。
    ///
    /// ⚠️ 现在 App 里**没有任何图片资源**（连 Assets.xcassets 都没有），
    /// 所以先用一个箭头占位。深蓝车标的 PNG 一旦放进 Assets（名字叫
    /// `DeepalLogo`），这里自动就换成真车标了 —— 代码不用改。
    /// （用户的车机包里有车标图，但资源名被混淆成 res/jf.webp 这种，认不出来。）
    @ViewBuilder
    private var logo: some View {
        if let img = UIImage(named: "DeepalLogo") {
            Image(uiImage: img)
                .resizable()
                .scaledToFit()
                .frame(width: 48, height: 48)
        } else {
            Image(systemName: "arrowtriangle.up.fill")
                .font(.system(size: 26, weight: .bold))
                .foregroundStyle(Color(hex: 0x2E7BE8))
        }
    }
}
