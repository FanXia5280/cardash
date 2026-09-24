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
    var nav: NavState?
    /// 各字段的来源，用于排查（vhal / none 等）
    var src: [String: String]?
    /// 车机导航的目的地。有值就自动跟着导航。
    ///
    /// Android 侧有两条来源（见 DestSignals）：高德车机版广播（权威，手动点导航也能认出来）
    /// 和语音助手的 NLU 日志（兜底）。`src` 会告诉我们是哪一条。
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
/// 车机报上来的导航目的地
struct Dest: Codable, Hashable {
    var name: String?
    var lat: Double?
    var lon: Double?
    /// 来源：`amap` = 高德车机版广播（权威，手动点导航也有）；
    /// `voice` = 车机语音助手的 NLU 日志（兜底）。只用于诊断显示。
    var src: String?

    /// 换目的地才重算路线，避免每 250ms 拉一次 /state 就重算一次。
    ///
    /// 坐标 + 名字一起去重。⚠️ 2026-09-24 实车教训：车机端曾把坐标舍到 2 位小数
    /// （≈1 公里误差），附近两个目的地舍完 key 相同 ⇒ 用户切了目的地、
    /// iPhone 却认为"没变"不重算 —— 表现就是「切换目的地后 iOS 不更新路线」。
    /// 车机端坐标精度修好后这里按理只剩坐标就够，但把名字也算上，
    /// 「切了目的地必须更新」这条就算坐标真相同也保得住。
    var routeKey: String { "\(lat ?? 0)|\(lon ?? 0)|\(name ?? "")" }

    /// 这份目的地可信吗？
    ///
    /// ⚠️ **2026-09-24 改紧：必须有合法坐标才算数**（以前"只有名字也算"）。
    ///
    /// 原因：车机侧任何一行带 `"destName":"X"` 的日志都可能被当成目的地
    /// （别的进程也会打日志），而"只有名字"会让我们拿 X 去 `CLGeocoder`
    /// 编一个地方出来 —— 编到另一个区/另一个市都很正常，表现就是
    /// 「车机没改目的地，IPA 自己改了」。用户明确要求：**中间绝不导航到别的目的地**。
    ///
    /// 现在名字只用来显示（诊断里看得到），不再触发导航。
    /// 车机侧也已经保证下发的目的地一定带坐标（见 DestSignals.usable）。
    var isUsable: Bool {
        guard let la = lat, let lo = lon, la != 0, lo != 0 else { return false }
        return la > 3.5 && la < 53.6 && lo > 73.5 && lo < 135.1
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
