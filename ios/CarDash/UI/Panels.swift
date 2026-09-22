import SwiftUI

// MARK: - 左上：日期 + 时间

struct ClockPanel: View {
    let now: Date
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
        HStack(alignment: .firstTextBaseline, spacing: scale * 10) {
            Text(Self.dateFmt.string(from: now))
                .foregroundStyle(.white.opacity(0.70))
            Text(Self.timeFmt.string(from: now))
                .foregroundStyle(.white)
        }
        .font(.system(size: scale * 25, weight: .medium, design: .rounded))
        .monospacedDigit()
        .shadow(color: .black.opacity(0.35), radius: 6, y: 1)
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

    private var text: String {
        guard let s = speed, s.isFinite else { return "--" }
        return "\(Int(s.rounded()))"
    }

    var body: some View {
        VStack(spacing: scale * 0) {
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

    var body: some View {
        HStack(spacing: scale * 14) {
            cover

            VStack(alignment: .leading, spacing: scale * 4) {
                Text(music?.title ?? "未在播放")
                    .font(.system(size: scale * 18, weight: .semibold))
                    .foregroundStyle(.white)
                    .lineLimit(1)
                    .truncationMode(.tail)

                Text(music?.artist ?? "车机暂无媒体会话")
                    .font(.system(size: scale * 13, weight: .regular))
                    .foregroundStyle(.white.opacity(0.62))
                    .lineLimit(1)
                    .truncationMode(.tail)

                progressBar
                    .padding(.top, scale * 5)
            }
            .frame(width: scale * 168, alignment: .leading)
        }
        .shadow(color: .black.opacity(0.35), radius: 6, y: 1)
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

// MARK: - 右中：导航信息

struct NavigationPanel: View {
    let nav: NavState?
    let scale: CGFloat

    private var active: Bool { nav?.isActive ?? false }

    var body: some View {
        VStack(alignment: .trailing, spacing: scale * 8) {
            if active {
                if let d = nav?.distance, !d.isEmpty {
                    Text(d)
                        .font(.system(size: scale * 15, weight: .bold, design: .rounded))
                        .foregroundStyle(Color(hex: 0x7CE0A0))
                }
                Text(nav?.title ?? "")
                    .font(.system(size: scale * 23, weight: .semibold))
                    .foregroundStyle(.white)
                    .multilineTextAlignment(.trailing)
                    .lineLimit(2)
                Text(nav?.subtitle ?? "")
                    .font(.system(size: scale * 14, weight: .regular))
                    .foregroundStyle(.white.opacity(0.62))
                    .lineLimit(1)
            } else {
                Text("暂无导航信息")
                    .font(.system(size: scale * 23, weight: .semibold))
                    .foregroundStyle(.white.opacity(0.92))
                Text("请在车机开启导航")
                    .font(.system(size: scale * 14, weight: .regular))
                    .foregroundStyle(.white.opacity(0.58))
            }
        }
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
