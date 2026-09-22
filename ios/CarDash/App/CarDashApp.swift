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

    var body: some View {
        DashboardView(model: model)
            .onAppear {
                // 仪表盘必须常亮
                UIApplication.shared.isIdleTimerDisabled = true
                model.start()
            }
            .onChange(of: scenePhase) { _ in
                if scenePhase == .active {
                    UIApplication.shared.isIdleTimerDisabled = true
                }
            }
    }
}
