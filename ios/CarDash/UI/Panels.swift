import SwiftUI
import UIKit

// MARK: - HUD 卡片底

/// 半透明深色圆角底。
///
/// 背景换成地图之后，白字直接压在浅色路网上会糊掉，
/// 所以统一加一层薄底 —— 参考图里的元素也都是这么处理的。
struct HudCard: ViewModifier {
    let scale: CGFloat
    let padding: CGFloat

    func body(content: Content) -> some View {
        content
            .padding(.horizontal, scale * 11 * padding)
            .padding(.vertical, scale * 6 * padding)
            .background(
                RoundedRectangle(cornerRadius: scale * 11, style: .continuous)
                    .fill(Color.black.opacity(0.40))
            )
    }
}

extension View {
    func hudCard(_ scale: CGFloat, padding: CGFloat = 1.0) -> some View {
        modifier(HudCard(scale: scale, padding: padding))
    }
}

// MARK: - 封面解码缓存

/// 车机每 250ms 就会把同一张封面的 base64 重发一遍，这里做一层单条缓存，
/// 避免每秒解码 4 次 JPEG。
enum CoverImageCache {
    private static var key: String?
    private static var image: UIImage?

    static func image(for base64: String?) -> UIImage? {
        guard let b64 = base64, !b64.isEmpty else {
            key = nil
            image = nil
            return nil
        }
        if b64 == key { return image }
        key = b64
        image = Data(base64Encoded: b64).flatMap { UIImage(data: $0) }
        return image
    }
}

// MARK: - 左上：日期 + 时间

struct ClockPanel: View {
    let scale: CGFloat

    private static let dateFmt: DateFormatter = {
        let f = DateFormatter()
        f.locale = Locale(identifier: "zh_CN")
        f.dateFormat = "MM/dd"
        return f
    }()

    private static let timeFmt: DateFormatter = {
        let f = DateFormatter()
        f.locale = Locale(identifier: "zh_CN")
        f.dateFormat = "HH:mm"
        return f
    }()

    var body: some View {
        // 用 TimelineView 自己驱动秒级刷新，**不依赖父视图的计时器**。
        // 之前挂在父视图的 Timer 上：父视图只要有一次不再重绘，
        // 时间就停在那不动了（用户反馈「时间不会刷新」）。
        TimelineView(.periodic(from: .now, by: 1)) { ctx in
            HStack(alignment: .firstTextBaseline, spacing: scale * 10) {
                Text(Self.dateFmt.string(from: ctx.date))
                    .foregroundStyle(.white.opacity(0.70))
                Text(Self.timeFmt.string(from: ctx.date))
                    .foregroundStyle(.white)
            }
            .font(.system(size: scale * 25, weight: .medium, design: .rounded))
            .monospacedDigit()
            .shadow(color: .black.opacity(0.35), radius: 6, y: 1)
        }
    }
}

// MARK: - 右上：海拔

struct AltitudePanel: View {
    let altitude: Double?
    let scale: CGFloat

    var body: some View {
        HStack(spacing: scale * 7) {
            Image(systemName: "mountain.2.fill")
                .font(.system(size: scale * 16, weight: .semibold))
            Text(altitude.map { "\(Int($0.rounded()))m" } ?? "--")
                .font(.system(size: scale * 22, weight: .semibold, design: .rounded))
                .monospacedDigit()
        }
        .foregroundStyle(.white.opacity(0.92))
        .shadow(color: .black.opacity(0.35), radius: 6, y: 1)
    }
}

// MARK: - 左下：电量 + 剩余续航

struct BatteryRangePanel: View {
    let soc: Double?
    let range: Double?
    let scale: CGFloat

    var body: some View {
        HStack(spacing: scale * 12) {
            BatteryGlyph(level: soc, scale: scale)

            Text(soc.map { "\(Int($0.rounded()))%" } ?? "--%")
                .font(.system(size: scale * 19, weight: .semibold, design: .rounded))
                .monospacedDigit()
                .foregroundStyle(.white)

            Text(range.map { "\(Int($0.rounded())) km" } ?? "-- km")
                .font(.system(size: scale * 19, weight: .medium, design: .rounded))
                .monospacedDigit()
                .foregroundStyle(.white.opacity(0.85))
        }
        .shadow(color: .black.opacity(0.35), radius: 6, y: 1)
    }
}

struct BatteryGlyph: View {
    let level: Double?
    let scale: CGFloat

    var body: some View {
        let w = scale * 30
        let h = scale * 15
        let inset = scale * 2.5
        let ratio = CGFloat(min(max((level ?? 0) / 100.0, 0), 1))

        ZStack(alignment: .leading) {
            RoundedRectangle(cornerRadius: h * 0.30)
                .stroke(Color.white.opacity(0.50), lineWidth: max(1, scale * 1.1))
                .frame(width: w, height: h)

            RoundedRectangle(cornerRadius: h * 0.22)
                .fill(ratio > 0.2 ? Color(hex: 0x6FE07A) : Color(hex: 0xE0645A))
                .frame(width: max(0, (w - inset * 2) * ratio), height: h - inset * 2)
                .padding(.leading, inset)
        }
        .overlay(alignment: .trailing) {
            Capsule()
                .fill(Color.white.opacity(0.50))
                .frame(width: scale * 2.5, height: h * 0.42)
                .offset(x: scale * 4)
        }
        .frame(width: w + scale * 6, height: h)
    }
}

// MARK: - 中央：车速

struct SpeedGauge: View {
    let speed: Double?
    let scale: CGFloat
    /// 数字和 km/h 在这一块里怎么摆：
    ///   横屏 .leading —— 速度贴左边，对应参考图里大 P 的位置
    ///   竖屏 .center —— 居中
    var align: HorizontalAlignment = .center

    private var text: String {
        guard let s = speed, s.isFinite else { return "--" }
        return "\(Int(s.rounded()))"
    }

    var body: some View {
        VStack(alignment: align, spacing: scale * 0) {
            Text(text)
                .font(.system(size: scale * 140, weight: .thin, design: .rounded))
                .monospacedDigit()
                .foregroundStyle(.white)
                .minimumScaleFactor(0.45)
                .lineLimit(1)
                .shadow(color: .black.opacity(0.28), radius: 12, y: 2)

            Text("km/h")
                .font(.system(size: scale * 19, weight: .medium, design: .rounded))
                .foregroundStyle(.white.opacity(0.62))
                .padding(.top, -scale * 6)
        }
    }
}

// MARK: - 正下方：转向卡（已移除）
//
// ⚠️ 2026-09-23 按用户要求**删掉了**这张卡（原来显示「↗ 进入 大成路北段」）。
// 它显示的内容和车机自己的导航卡是重复的，而且车机换个转向它就变，
// 叠在地图上很乱。转向提示以**车机屏幕**为准，iPhone 这边不显示。
// 对应的结构体 TurnCard 一起删了，别再加回来（要加就先问用户）。

// MARK: - 顶栏中间：还有多久 / 还有多远到目的地

/// 对应参考图顶栏中间那两格：「⬆ 48 分钟  201 公里」。
/// 导航没开的时候整块淡出，不占视觉。
struct NavSummaryPanel: View {
    let nav: NavState?
    let scale: CGFloat

    private var active: Bool { nav?.isActive ?? false }

    var body: some View {
        HStack(spacing: scale * 13) {
            // 终点标识 —— 按用户要求，这里不再显示左转/右转箭头
            // （转向提示看车机自己的屏幕，iPhone 只标「这是去终点的行程」）。
            // flag.checkered 需要 iOS 16，工程的 deploymentTarget 就是 16.0。
            Image(systemName: "flag.checkered")
                .font(.system(size: scale * 15, weight: .bold))
                .foregroundStyle(.white.opacity(0.88))

            if let eta = nav?.eta, !eta.isEmpty {
                Text(eta)
                    .font(.system(size: scale * 17, weight: .semibold, design: .rounded))
                    .monospacedDigit()
                    .foregroundStyle(.white)
            }
            if let r = nav?.remain, !r.isEmpty {
                Text(r)
                    .font(.system(size: scale * 17, weight: .semibold, design: .rounded))
                    .monospacedDigit()
                    .foregroundStyle(.white.opacity(0.9))
            }
            // 预计几点到达 —— 按用户要求从右边挪到顶栏
            if let arr = nav?.arrive, !arr.isEmpty {
                Text(arr)
                    .font(.system(size: scale * 15, weight: .medium, design: .rounded))
                    .monospacedDigit()
                    // 用户 2026-09-24：别用灰的，和旁边两格一个亮度
                    .foregroundStyle(.white.opacity(0.9))
            }
        }
        .opacity(active ? 1 : 0)
        .animation(.easeInOut(duration: 0.25), value: active)
        .shadow(color: .black.opacity(0.35), radius: 6, y: 1)
    }
}

// MARK: - 右下：档位 + 总里程

/// 档位和总里程。**横屏**下是一张卡（原来就是这样）；
/// 竖屏下两者分开放（用户要求：总里程挪到原来比例尺的位置），
/// 所以拆成 GearPanel / OdometerPanel 两个独立的块，各自能单独包 hudCard。
struct GearOdometerPanel: View {
    let gear: String?
    let odometer: Double?
    let scale: CGFloat

    var body: some View {
        HStack(spacing: scale * 16) {
            GearPanel(gear: gear, scale: scale)
            OdometerPanel(odometer: odometer, scale: scale)
        }
        .shadow(color: .black.opacity(0.35), radius: 6, y: 1)
    }
}

/// 只要档位（P R N D）
struct GearPanel: View {
    let gear: String?
    let scale: CGFloat

    private let order = ["P", "R", "N", "D"]

    var body: some View {
        HStack(spacing: scale * 12) {
            ForEach(order, id: \.self) { g in
                Text(g)
                    .font(.system(size: scale * 19,
                                  weight: g == gear ? .bold : .medium,
                                  design: .rounded))
                    .foregroundStyle(g == gear ? Color.white : Color.white.opacity(0.28))
            }
        }
        .shadow(color: .black.opacity(0.35), radius: 6, y: 1)
    }
}

// MARK: - 中央：胎压（「时速」那一格的第二页）

/// 四轮胎压面板 —— 「时速」那一格的第二页（竖屏左右滑 / 横屏上下滑切换，见 `SpeedSlot`）。
///
/// 视觉照用户 2026-09-26 给的参考图：**透明车底盘轮廓 + 四个轮子各一个读数**
/// （左前 / 右前 / 左后 / 右后）。
///
/// ⚠️ 数值直接用车机给的**字符串**，单位固定标 `bar`，**不做任何换算** ——
/// 车机那边存的就是底包 native 格式化好的字符串（见 `TireInfo` 的注释）。
struct TirePressurePanel: View {
    let tire: TireInfo?
    let scale: CGFloat

    /// ⚠️ 2026-09-26 用户反馈「胎压和时速大小不一样、切换很生硬」⇒
    /// 这里改成**和 `SpeedSlot` 两页共用的内容框完全一致**（300×176），
    /// 并且把车底盘/读数一起放大撑满它 —— 这样切换时外框不跳、观感也一致。
    private var w: CGFloat { scale * 300 }
    private var h: CGFloat { scale * 176 }

    var body: some View {
        ZStack {
            chassis
            VStack(spacing: 0) {
                HStack(spacing: 0) {
                    reading(tire?.fl)
                    Spacer(minLength: scale * 10)
                    reading(tire?.fr)
                }
                Spacer(minLength: 0)
                HStack(spacing: 0) {
                    reading(tire?.rl)
                    Spacer(minLength: scale * 10)
                    reading(tire?.rr)
                }
            }
        }
        .frame(width: w, height: h)
        .shadow(color: .black.opacity(0.28), radius: 10, y: 2)
    }

    /// 透明车底盘：**只描边、不填充**，车头朝上。
    /// 前挡风 / 后窗各画一条弧线 —— 光看轮廓也能认出哪头是车头。
    private var chassis: some View {
        let bw = w * 0.22
        let bh = h * 0.68
        return ZStack {
            RoundedRectangle(cornerRadius: bw * 0.30, style: .continuous)
                .stroke(Color.white.opacity(0.34), lineWidth: max(1, scale * 1.6))
                .frame(width: bw, height: bh)

            Path { p in
                p.move(to: CGPoint(x: bw * 0.08, y: bh * 0.32))
                p.addQuadCurve(to: CGPoint(x: bw * 0.92, y: bh * 0.32),
                               control: CGPoint(x: bw * 0.50, y: bh * 0.14))
            }
            .stroke(Color.white.opacity(0.26), lineWidth: max(1, scale * 1.4))
            .frame(width: bw, height: bh)

            Path { p in
                p.move(to: CGPoint(x: bw * 0.12, y: bh * 0.76))
                p.addQuadCurve(to: CGPoint(x: bw * 0.88, y: bh * 0.76),
                               control: CGPoint(x: bw * 0.50, y: bh * 0.90))
            }
            .stroke(Color.white.opacity(0.20), lineWidth: max(1, scale * 1.2))
            .frame(width: bw, height: bh)
        }
    }

    /// 单个轮子的读数：大数字 + 小单位。没有值就显示 `--`（不隐藏，位置才稳定）
    private func reading(_ raw: String?) -> some View {
        var v: String? = nil
        if let t = tire { v = t.value(raw) }
        return VStack(spacing: -scale * 1) {
            Text(v ?? "--")
                .font(.system(size: scale * 36, weight: .semibold, design: .rounded))
                .monospacedDigit()
                .foregroundStyle(.white)
                .minimumScaleFactor(0.6)
                .lineLimit(1)
            Text("bar")
                .font(.system(size: scale * 14, weight: .medium, design: .rounded))
                .foregroundStyle(.white.opacity(0.55))
        }
        .frame(width: scale * 92)
    }
}

/// 只要总里程。竖屏下它被单独放到右下角（原来比例尺的位置）
struct OdometerPanel: View {
    let odometer: Double?
    let scale: CGFloat

    private var odoText: String {
        guard let o = odometer, o.isFinite else { return "-- km" }
        let f = NumberFormatter()
        f.numberStyle = .decimal
        f.maximumFractionDigits = 0
        let s = f.string(from: NSNumber(value: o)) ?? "\(Int(o))"
        return "\(s) km"
    }

    var body: some View {
        Text(odoText)
            .font(.system(size: scale * 19, weight: .medium, design: .rounded))
            .monospacedDigit()
            .foregroundStyle(.white.opacity(0.88))
            .shadow(color: .black.opacity(0.35), radius: 6, y: 1)
    }
}
