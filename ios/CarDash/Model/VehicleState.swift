import Foundation

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
    var music: MusicState?
    var nav: NavState?
    /// 各字段的来源，用于排查（vhal / none 等）
    var src: [String: String]?
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
        case "left": return "arrow.turn.up.left"
        case "slightLeft": return "arrow.up.left"
        case "right": return "arrow.turn.up.right"
        case "slightRight": return "arrow.up.right"
        case "uturn": return "arrow.uturn.left"
        case "round": return "arrow.triangle.turn.up.right.circle"
        case "merge": return "arrow.merge"
        case "arrive": return "flag.checkered"
        default: return "arrow.up"
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
