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

    var isActive: Bool { active ?? false }
}

/// 连接状态
enum LinkStatus: Equatable {
    case idle
    case searching
    case online
    case failed(String)

    var label: String {
        switch self {
        case .idle: return "未连接"
        case .searching: return "正在连接车机…"
        case .online: return "已连接"
        case .failed(let m): return m
        }
    }

    var isOnline: Bool {
        if case .online = self { return true }
        return false
    }
}
