import Foundation
import CoreLocation

/// 车机桥接推送的单帧数据。字段全部可选，缺省表示该信号当前不可用。
struct CarSnapshot: Codable, Equatable {
    var v: Int?
    var ts: Double?
    /// km/h
    var speed: Double?
    /// P / R / N / D
    var gear: String?
    /// 电量百分比 0-100
    var soc: Double?
    /// 剩余续航 km
    var range: Double?
    /// 总里程 km
    var odometer: Double?
    /// 海拔 m
    var altitude: Double?
    /// 当前路段限速 km/h（车机高德广播的 LIMITED_SPEED，Android 侧见 AmapSignals）。
    /// 用它跟车速比 → 超速时两边冒红（高德 SDK 的 showOverSpeedPulse 是收费接口，
    /// 我们自己算一份，不依赖它）。
    var limit: Int?
    /// 转向灯：0=灭 1=左 2=右 3=双闪（车机车身信号，见 VendorSignals.applyTurn）。
    /// 老版本车机 APK 不发这个字段 → nil → 不显示，不报错。
    var turn: Int?
    /// 前方电子眼（车机高德引导广播）。null = 前方没有
    var camera: CameraInfo?
    var music: MusicState?
    var nav: NavState?
    /// 各字段的来源，用于排查（vhal / none 等）
    var src: [String: String]?
    /// 车机导航的目的地。Android 侧从车机语音助手的 NLU 日志里解析出来
    /// （见 DestSignals），有值就自动算路线画到地图上。
    var dest: Dest?
}

/// 前方电子眼（车机高德引导广播里的 CAMERA_*）。
///
/// `type` 用的是高德官方枚举 `AMapNaviCameraType`：
///   0 测速 / 1 监控 / 2 闯红灯 / 3 违章 / 4 公交专用道 / 5 应急车道 /
///   6 非机动车道 / 8 区间测速起始 / 9 区间测速终止
/// `speed` 是**这个电子眼自己的限速**（不等于路段限速）—— 决定要不要冒红就看它。
struct CameraInfo: Codable, Equatable {
    var dist: Int?
    var type: Int?
    var speed: Int?
}

/// 路线的一段，带上高德给的路况。
/// 高德 Web API（extensions=all）的 steps[].tmcs[] 里每小段都有 status：
/// 畅通 / 缓行 / 拥堵 / 严重拥堵 —— 用它分段着色，就是高德导航那种
/// 「绿的路 + 红的堵点」，而不是自己猜一根蓝线。
struct RouteSegment {
    var points: [CLLocationCoordinate2D]
    /// 0 畅通 / 1 缓行 / 2 拥堵 / 3 严重拥堵
    var status: Int
}

/// 车机报上来的导航目的地
struct Dest: Codable, Hashable {
    var name: String?
    var lat: Double?
    var lon: Double?

    /// 换目的地才重算路线，避免每 250ms 拉一次 /state 就重算一次
    var routeKey: String { "\(name ?? "")|\(lat ?? 0)|\(lon ?? 0)" }

    /// 这份目的地可信吗？
    ///
    /// ⚠️ 背景（2026-09-23）：车机那边曾经把**车机自己的 GPS 位置**当目的地报上来，
    /// 于是 IPA 画出来的路线跟车机完全不一样，而且车一动「目的地」就变。
    /// 根因已在车机侧修掉（DestSignals 只认语音语义日志），这里再兜一道：
    /// 要么有名字（可以地理编码），要么坐标像一份**合理范围内的经纬度**。
    var isUsable: Bool {
        if let la = lat, let lo = lon, la != 0, lo != 0,
           la > 3.5, la < 53.6, lo > 73.5, lo < 135.1 {
            return true
        }
        return !(name ?? "").isEmpty
    }
}

struct MusicState: Codable, Equatable {
    var title: String?
    var artist: String?
    var album: String?
    var playing: Bool?
    /// 当前播放位置（秒）
    var position: Double?
    /// 总时长（秒）
    var duration: Double?
    /// 专辑封面，base64 编码的 JPEG（车机端已缩到 240px）
    var cover: String?
    /// 同步歌词，紧凑格式：「起始秒|歌词」逐行、\n 连接
    var lrc: String?

    /// 按播放位置取出 [当前行, 下一行]。
    ///
    /// 歌词在本地按 position 切行，所以能跟着进度条一起往前走，
    /// 不用等车机 200ms 推一次 —— 那样会明显慢半拍。
    func lyricLines(at position: Double?) -> [String] {
        guard let raw = lrc, !raw.isEmpty else { return [] }
        let lines = parseLrc(raw)
        guard !lines.isEmpty else { return [] }

        let pos = position ?? 0
        var idx = 0
        for (i, item) in lines.enumerated() {
            if item.time <= pos + 0.15 { idx = i } else { break }
        }
        var out: [String] = [lines[idx].text]
        if idx + 1 < lines.count { out.append(lines[idx + 1].text) }
        return out
    }

    private func parseLrc(_ raw: String) -> [(time: Double, text: String)] {
        var out: [(Double, String)] = []
        for line in raw.split(separator: "\n") {
            guard let bar = line.firstIndex(of: "|") else { continue }
            guard let t = Double(line[line.startIndex..<bar]) else { continue }
            let text = String(line[line.index(after: bar)...])
                .trimmingCharacters(in: .whitespaces)
            if text.isEmpty { continue }
            out.append((t, text))
        }
        return out
    }

    var progress: Double {
        guard let p = position, let d = duration, d > 1 else { return 0 }
        return min(max(p / d, 0), 1)
    }

    var isEmpty: Bool {
        (title ?? "").isEmpty && (artist ?? "").isEmpty
    }
}

struct NavState: Codable, Equatable {
    var active: Bool?
    var title: String?
    var subtitle: String?
    var distance: String?
    /// 第二个距离，例如「注意距离 191 米」
    var after: String?
    /// 剩余时间，例如「48 分钟」—— 顶栏中间
    var eta: String?
    /// 剩余总里程，例如「201 公里」—— 顶栏中间
    var remain: String?
    /// 转向类型：left/right/slightLeft/slightRight/straight/uturn/round/arrive/merge
    var turn: String?
    /// 预计到达时间文案（高德 ETA_TEXT 原文）
    var arrive: String?

    var isActive: Bool { active ?? false }

    /// 转向图标（SF Symbol）。认不出来时退回直行箭头。
    var symbolName: String {
        switch turn ?? "" {
        case "straight": return "arrow.up"
        case "left": return "arrow.turn.up.left"
        case "slightLeft": return "arrow.up.left"
        case "keepLeft": return "arrow.up.left"
        case "right": return "arrow.turn.up.right"
        case "slightRight": return "arrow.up.right"
        case "keepRight": return "arrow.up.right"
        case "uturn": return "arrow.uturn.left"
        case "leftUturn": return "arrow.uturn.left"
        case "rightUturn": return "arrow.uturn.right"
        case "round": return "arrow.triangle.turn.up.right.circle"
        case "merge": return "arrow.merge"
        case "arrive": return "flag.checkered"
        case "": return "arrow.up"
        // 认不出的转向：显示问号箭头，别假装是直行 ——
        // 之前一直显示直线箭头就是因为「认不出」被当成了「直行」。
        default: return "questionmark"
        }
    }
}

/// 连接状态
enum LinkStatus: Equatable {
    case idle
    case searching
    case scanning
    case online
    case failed(String)

    var label: String {
        switch self {
        case .idle: return "未连接"
        case .searching: return "正在连接车机…"
        case .scanning: return "正在搜索车机…"
        case .online: return "已连接"
        case .failed(let m): return m
        }
    }

    var isOnline: Bool {
        if case .online = self { return true }
        return false
    }
}
