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

// MARK: - 左中：当前播放音乐

struct MusicPanel: View {
    let music: MusicState?
    let scale: CGFloat

    /// 单击卡片在「歌手」和「两行歌词」之间切换
    @State private var showLyrics = false
    /// 点了但没歌词时的短暂提示 —— 没有它用户分不清
    /// 「手势没生效」和「这首歌确实没歌词」
    @State private var noLyricHint = false

    private var lyricLines: [String] {
        music?.lyricLines(at: music?.position) ?? []
    }

    var body: some View {
        HStack(spacing: scale * 14) {
            cover

            VStack(alignment: .leading, spacing: scale * 4) {
                HStack(spacing: scale * 6) {
                    Text(music?.title ?? "未在播放")
                        .font(.system(size: scale * 18, weight: .semibold))
                        .foregroundStyle(.white)
                        .lineLimit(1)
                        .truncationMode(.tail)

                    // 有歌词时给个小提示，不然用户不知道能点
                    if !lyricLines.isEmpty && !showLyrics {
                        Image(systemName: "text.quote")
                            .font(.system(size: scale * 9, weight: .semibold))
                            .foregroundStyle(.white.opacity(0.42))
                    }
                }

                if showLyrics && !lyricLines.isEmpty {
                    // 两行歌词：当前行亮、下一行暗，随播放位置自动往下走
                    VStack(alignment: .leading, spacing: scale * 2) {
                        ForEach(Array(0..<min(2, lyricLines.count)), id: \.self) { i in
                            Text(lyricLines[i])
                                .font(.system(size: scale * (i == 0 ? 13.5 : 12),
                                              weight: i == 0 ? .semibold : .regular))
                                .foregroundStyle(.white.opacity(i == 0 ? 0.95 : 0.48))
                                .lineLimit(1)
                                .truncationMode(.tail)
                        }
                    }
                    .frame(height: scale * 32, alignment: .top)
                } else if noLyricHint {
                    // 点了但没歌词：给个明确反馈，免得被当成「点了没反应」
                    Text("该歌曲暂无歌词")
                        .font(.system(size: scale * 13, weight: .regular))
                        .foregroundStyle(.white.opacity(0.42))
                        .lineLimit(1)
                } else {
                    Text(lyricHint)
                        .font(.system(size: scale * 13, weight: .regular))
                        .foregroundStyle(.white.opacity(0.62))
                        .lineLimit(1)
                        .truncationMode(.tail)
                }

                progressBar
                    .padding(.top, scale * 5)
            }
            // 自适应剩余宽度：横屏时左侧要给灵动岛让位，写死宽度会被挤爆
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .shadow(color: .black.opacity(0.35), radius: 6, y: 1)
        .contentShape(Rectangle())
        // 内层手势优先于外面「点任意位置开设置」，所以点音乐卡不会弹设置
        .onTapGesture {
            if lyricLines.isEmpty {
                // 没歌词也要给个反馈，否则会被当成「点了没反应」
                noLyricHint = true
                DispatchQueue.main.asyncAfter(deadline: .now() + 2) {
                    noLyricHint = false
                }
            } else {
                noLyricHint = false
                showLyrics.toggle()
            }
        }
    }

    private var lyricHint: String {
        if let a = music?.artist, !a.isEmpty { return a }
        return "车机暂无媒体会话"
    }

    private var cover: some View {
        ZStack {
            RoundedRectangle(cornerRadius: scale * 9)
                .fill(LinearGradient(
                    colors: [Color.white.opacity(0.22), Color.white.opacity(0.08)],
                    startPoint: .topLeading,
                    endPoint: .bottomTrailing
                ))

            if let art = CoverImageCache.image(for: music?.cover) {
                Image(uiImage: art)
                    .resizable()
                    .aspectRatio(contentMode: .fill)
                    .frame(width: scale * 48, height: scale * 48)
                    .clipShape(RoundedRectangle(cornerRadius: scale * 9))
            } else {
                Image(systemName: "music.note")
                    .font(.system(size: scale * 19, weight: .medium))
                    .foregroundStyle(.white.opacity(music == nil ? 0.45 : 0.85))
            }
        }
        .frame(width: scale * 48, height: scale * 48)
        .overlay(
            RoundedRectangle(cornerRadius: scale * 9)
                .stroke(Color.white.opacity(0.16), lineWidth: 1)
        )
    }

    private var progressBar: some View {
        GeometryReader { g in
            ZStack(alignment: .leading) {
                Capsule()
                    .fill(Color.white.opacity(0.22))
                Capsule()
                    .fill(Color.white.opacity(0.85))
                    .frame(width: g.size.width * (music?.progress ?? 0))
            }
        }
        .frame(width: scale * 168, height: scale * 3)
    }
}

// MARK: - 正下方：转向卡

/// 对应参考图底部中央那块「🚗 110米 / 进入南海路」。
///
/// 从原来的**右中栏**挪到**正下方**：一是右下角离视线远，二是背景换成地图后
/// 右边要留给地图，导航信息压在地图旁边看不清。
/// 现在做成一张独立的卡，开车时低头一眼就能看到。
struct TurnCard: View {
    let nav: NavState?
    let scale: CGFloat

    private var active: Bool { nav?.isActive ?? false }

    /// 道路名优先用 subtitle（车机把转向动作放 title、路名放 subtitle）
    private var road: String? {
        for c in [nav?.subtitle, nav?.title] {
            if let s = c, !s.isEmpty, s != nav?.distance { return s }
        }
        return nil
    }

    var body: some View {
        HStack(spacing: scale * 13) {
            Image(systemName: nav?.symbolName ?? "arrow.up")
                .font(.system(size: scale * 34, weight: .bold))
                .foregroundStyle(Color(hex: 0x8FD8FF))
                .frame(width: scale * 42, height: scale * 42)

            VStack(alignment: .leading, spacing: scale * 1) {
                Text(nav?.distance ?? nav?.title ?? "--")
                    .font(.system(size: scale * 26, weight: .bold, design: .rounded))
                    .monospacedDigit()
                    .foregroundStyle(.white)
                    .lineLimit(1)
                    .minimumScaleFactor(0.5)

                if let road {
                    Text("进入 \(road)")
                        .font(.system(size: scale * 15, weight: .semibold))
                        .foregroundStyle(.white.opacity(0.92))
                        .lineLimit(1)
                        .truncationMode(.tail)
                        .minimumScaleFactor(0.6)
                }
            }
        }
        .hudCard(scale, padding: 1.3)
        // 没导航时整块淡出，但保留占位，避免布局跳动
        .opacity(active ? 1 : 0)
        .animation(.easeInOut(duration: 0.2), value: active)
        .shadow(color: .black.opacity(0.35), radius: 6, y: 1)
    }
}

// MARK: - 顶栏中间：还有多久 / 还有多远到目的地

/// 对应参考图顶栏中间那两格：「⬆ 48 分钟  201 公里」。
/// 导航没开的时候整块淡出，不占视觉。
struct NavSummaryPanel: View {
    let nav: NavState?
    let scale: CGFloat

    private var active: Bool { nav?.isActive ?? false }

    var body: some View {
        HStack(spacing: scale * 13) {
            Image(systemName: nav?.symbolName ?? "arrow.up")
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
                    .foregroundStyle(.white.opacity(0.70))
            }
        }
        .opacity(active ? 1 : 0)
        .animation(.easeInOut(duration: 0.25), value: active)
        .shadow(color: .black.opacity(0.35), radius: 6, y: 1)
    }
}

// MARK: - 右下：档位 + 总里程

struct GearOdometerPanel: View {
    let gear: String?
    let odometer: Double?
    let scale: CGFloat

    private let order = ["P", "R", "N", "D"]

    private var odoText: String {
        guard let o = odometer, o.isFinite else { return "-- km" }
        let f = NumberFormatter()
        f.numberStyle = .decimal
        f.maximumFractionDigits = 0
        let s = f.string(from: NSNumber(value: o)) ?? "\(Int(o))"
        return "\(s) km"
    }

    var body: some View {
        HStack(spacing: scale * 16) {
            HStack(spacing: scale * 12) {
                ForEach(order, id: \.self) { g in
                    Text(g)
                        .font(.system(size: scale * 19,
                                      weight: g == gear ? .bold : .medium,
                                      design: .rounded))
                        .foregroundStyle(g == gear ? Color.white : Color.white.opacity(0.28))
                }
            }

            Text(odoText)
                .font(.system(size: scale * 19, weight: .medium, design: .rounded))
                .monospacedDigit()
                .foregroundStyle(.white.opacity(0.88))
        }
        .shadow(color: .black.opacity(0.35), radius: 6, y: 1)
    }
}
