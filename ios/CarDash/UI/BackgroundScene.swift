import SwiftUI

/// 日落渐变 + 多层山脊剪影，全部用代码绘制，不依赖图片资源。
struct BackgroundScene: View {
    var body: some View {
        ZStack {
            LinearGradient(
                stops: [
                    .init(color: Color(hex: 0x241B3A), location: 0.00),
                    .init(color: Color(hex: 0x4B2A55), location: 0.22),
                    .init(color: Color(hex: 0x8C3F55), location: 0.42),
                    .init(color: Color(hex: 0xD4673C), location: 0.60),
                    .init(color: Color(hex: 0xF09A4A), location: 0.74),
                    .init(color: Color(hex: 0xF7C173), location: 0.88),
                    .init(color: Color(hex: 0x2A1B22), location: 1.00)
                ],
                startPoint: .top,
                endPoint: .bottom
            )

            // 落日余晖
            RadialGradient(
                colors: [Color(hex: 0xFFD9A0).opacity(0.55), .clear],
                center: UnitPoint(x: 0.62, y: 0.70),
                startRadius: 0,
                endRadius: 260
            )
            .blendMode(.screen)

            MountainRidge(points: Ridges.far, baseline: 0.92)
                .fill(Color(hex: 0x5A3348).opacity(0.55))
            MountainRidge(points: Ridges.mid, baseline: 1.0)
                .fill(Color(hex: 0x3B2233).opacity(0.85))
            MountainRidge(points: Ridges.near, baseline: 1.0)
                .fill(Color(hex: 0x1C1119))

            // 上下压暗，保证文字可读
            LinearGradient(
                colors: [Color.black.opacity(0.38), .clear, .clear, Color.black.opacity(0.42)],
                startPoint: .top,
                endPoint: .bottom
            )
        }
        .ignoresSafeArea()
    }
}

private enum Ridges {
    /// 归一化坐标 (x, y)，y 越小越高
    static let far: [CGPoint] = [
        CGPoint(x: -0.02, y: 0.55), CGPoint(x: 0.06, y: 0.40), CGPoint(x: 0.13, y: 0.50),
        CGPoint(x: 0.21, y: 0.30), CGPoint(x: 0.29, y: 0.46), CGPoint(x: 0.37, y: 0.34),
        CGPoint(x: 0.45, y: 0.50), CGPoint(x: 0.54, y: 0.32), CGPoint(x: 0.62, y: 0.48),
        CGPoint(x: 0.70, y: 0.38), CGPoint(x: 0.78, y: 0.52), CGPoint(x: 0.87, y: 0.36),
        CGPoint(x: 0.95, y: 0.50), CGPoint(x: 1.02, y: 0.42)
    ]

    static let mid: [CGPoint] = [
        CGPoint(x: -0.02, y: 0.72), CGPoint(x: 0.05, y: 0.58), CGPoint(x: 0.12, y: 0.68),
        CGPoint(x: 0.20, y: 0.50), CGPoint(x: 0.28, y: 0.66), CGPoint(x: 0.36, y: 0.56),
        CGPoint(x: 0.44, y: 0.70), CGPoint(x: 0.53, y: 0.52), CGPoint(x: 0.61, y: 0.68),
        CGPoint(x: 0.69, y: 0.58), CGPoint(x: 0.77, y: 0.72), CGPoint(x: 0.86, y: 0.56),
        CGPoint(x: 0.94, y: 0.70), CGPoint(x: 1.02, y: 0.62)
    ]

    static let near: [CGPoint] = [
        CGPoint(x: -0.02, y: 0.90), CGPoint(x: 0.04, y: 0.78), CGPoint(x: 0.11, y: 0.88),
        CGPoint(x: 0.19, y: 0.72), CGPoint(x: 0.27, y: 0.86), CGPoint(x: 0.35, y: 0.76),
        CGPoint(x: 0.43, y: 0.90), CGPoint(x: 0.52, y: 0.74), CGPoint(x: 0.60, y: 0.88),
        CGPoint(x: 0.68, y: 0.78), CGPoint(x: 0.76, y: 0.92), CGPoint(x: 0.85, y: 0.76),
        CGPoint(x: 0.93, y: 0.90), CGPoint(x: 1.02, y: 0.82)
    ]
}

private struct MountainRidge: Shape {
    let points: [CGPoint]
    let baseline: CGFloat

    func path(in rect: CGRect) -> Path {
        var p = Path()
        guard let first = points.first else { return p }
        p.move(to: CGPoint(x: first.x * rect.width, y: first.y * rect.height))
        for pt in points.dropFirst() {
            p.addLine(to: CGPoint(x: pt.x * rect.width, y: pt.y * rect.height))
        }
        p.addLine(to: CGPoint(x: rect.width, y: baseline * rect.height))
        p.addLine(to: CGPoint(x: 0, y: baseline * rect.height))
        p.closeSubpath()
        return p
    }
}

extension Color {
    init(hex: UInt32) {
        self.init(
            .sRGB,
            red: Double((hex >> 16) & 0xFF) / 255.0,
            green: Double((hex >> 8) & 0xFF) / 255.0,
            blue: Double(hex & 0xFF) / 255.0,
            opacity: 1.0
        )
    }
}
