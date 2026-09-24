import SwiftUI
import UIKit

struct SettingsSheet: View {
    @ObservedObject var model: DashboardModel
    @Environment(\.dismiss) private var dismiss

    /// 地图底图：高德 / 苹果。万一高德瓦片加载不出来可以切回来。
    @AppStorage("useAmapTiles") private var useAmapTiles = true

    @State private var diagTitle = ""
    @State private var diagText = ""
    @State private var showDiag = false
    @State private var loadingDiag = false
    /// 一键诊断抓完已经自动放进剪贴板了 —— 打开就显示「已复制」
    @State private var autoCopied = false

    var body: some View {
        NavigationView {
            Form {
                Section {
                    TextField("留空则自动搜索", text: $model.host)
                        .keyboardType(.numbersAndPunctuation)
                        .autocorrectionDisabled()
                        .textInputAutocapitalization(.never)
                    Button("自动搜索车机") { model.searchNow() }
                    Button("立即重连") { model.reconnectNow() }
                } header: {
                    Text("车机地址")
                } footer: {
                    Text("一般不用填：连上车机所在的网络后会自动扫描网段找到它。"
                         + "手动填的话端口默认 \(DashboardModel.defaultPort)，不用写。")
                }

                Section("连接状态") {
                    HStack {
                        Text("链路")
                        Spacer()
                        Text(model.link.label)
                            .foregroundStyle(model.link.isOnline ? .green : .secondary)
                    }
                    if let h = model.carHost, !h.isEmpty {
                        HStack {
                            Text("车机地址")
                            Spacer()
                            Text(h).foregroundStyle(.secondary)
                        }
                    }
                    if let snap = model.car {
                        HStack {
                            Text("车机时间戳")
                            Spacer()
                            Text(snap.ts.map { Self.tsFormat.string(from: Date(timeIntervalSince1970: $0 / 1000)) } ?? "-")
                                .foregroundStyle(.secondary)
                        }
                        // 目的地：手动在车机上点导航也应该在这里出现，
                        // 来源会写明是「高德广播」还是「语音」，对不上就看这一行。
                        HStack {
                            Text("目的地")
                            Spacer()
                            Text(Self.destLine(snap.dest))
                                .foregroundStyle(snap.dest == nil ? .secondary : .primary)
                        }
                        .font(.footnote)
                        if let src = snap.src, !src.isEmpty {
                            ForEach(src.keys.sorted(), id: \.self) { key in
                                HStack {
                                    Text(key)
                                    Spacer()
                                    Text(src[key] ?? "-")
                                        .foregroundStyle(.secondary)
                                }
                                .font(.footnote)
                            }
                        }
                    }
                }

                Section {
                    Button("模拟一条导航路线") { model.sendMockDestination() }
                    Button("清除模拟路线", role: .destructive) {
                        model.clearMockDestination()
                    }
                    Picker("模拟转向灯（绿光）", selection: $model.mockTurn) {
                        Text("关").tag(0)
                        Text("左转").tag(1)
                        Text("右转").tag(2)
                        Text("双闪").tag(3)
                    }
                    Toggle("模拟超速（红光）", isOn: $model.mockOverspeed)
                } header: {
                    Text("测试（正式版会移除）")
                } footer: {
                    Text("在当前位置东北方向约 3 公里放一个假目的地，"
                         + "看路线画出来什么样。点「清除」恢复车机真实数据。\n"
                         + "「模拟转向灯 / 模拟超速」没连车机也能预览两边绿光、红光闪烁效果"
                         + "（绿=转向灯，红=测速电子眼+超速）。")
                }

                Section {
                    Toggle("使用高德地图底图", isOn: $useAmapTiles)
                } header: {
                    Text("地图")
                } footer: {
                    Text("默认用高德，配色和车机一致，不需要 key。"
                         + "万一瓦片加载不出来就关掉，会切回苹果地图。")
                }

                Section {
                    if loadingDiag {
                        HStack {
                            Text("正在读取…")
                            Spacer()
                            ProgressView()
                        }
                    } else {
                        Button("一键复制全部诊断（发我排查）") { loadAll() }
                        Button("logcat 取数诊断") { load("/logcat", "logcat 取数诊断") }
                        Button("状态快照（JSON）") { load("/state", "状态快照（JSON）") }
                        Button("车机运行状态") { load("/diag", "车机运行状态") }
                        Button("厂商属性扫描") { load("/scan", "厂商属性扫描") }
                        Button("桥接运行日志") { load("/log", "桥接运行日志") }
                    }
                } header: {
                    Text("车机诊断")
                } footer: {
                    Text("首选上面那条「一键复制全部诊断」—— 一次抓完"
                         + " /state、/logcat、/diag、/log、/scan 五段，开头带版本和链路信息，"
                         + "抓完直接进剪贴板，粘贴发出来就行。\n"
                         + "下面是分开看的入口：logcat 有高德广播的全部 key，"
                         + "状态快照有当前目的地和导航来源。")
                }

                Section("本机传感器") {
                    HStack {
                        Text("定位权限")
                        Spacer()
                        Text(model.locationDenied ? "已拒绝" : "正常")
                            .foregroundStyle(model.locationDenied ? .red : .secondary)
                    }
                    HStack {
                        Text("本机累计里程")
                        Spacer()
                        Text(String(format: "%.1f km", model.localOdometer))
                            .foregroundStyle(.secondary)
                    }
                }

                Section {
                    HStack {
                        Text("App 版本")
                        Spacer()
                        Text(Self.appVersion)
                            .foregroundStyle(.secondary)
                    }
                    HStack {
                        Text("桥接版本")
                        Spacer()
                        Text(bridgeVersion)
                            .foregroundStyle(.secondary)
                    }
                } header: {
                    Text("版本")
                } footer: {
                    Text("装完先看这里核对：App 版本要和刚下载的 IPA 对得上，"
                         + "桥接版本要和车机上那个 APK 对得上。")
                }
            }
            .navigationTitle("设置")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("完成") { dismiss() }
                }
            }
        }
        .navigationViewStyle(.stack)
        .sheet(isPresented: $showDiag) {
            DiagView(title: diagTitle, text: diagText, preCopied: autoCopied)
        }
    }

    private func load(_ path: String, _ title: String) {
        loadingDiag = true
        diagTitle = title
        diagText = "正在读取 \(path) …"
        model.fetchText(path: path) { text in
            diagText = text
            loadingDiag = false
            autoCopied = false
            showDiag = true
        }
    }

    /// 一键抓全部：抓完**直接放进剪贴板** —— 用户点一次就能粘出去，少一步操作。
    private func loadAll() {
        loadingDiag = true
        diagTitle = "全部诊断"
        diagText = "正在依次抓取 /state → /logcat → /diag → /log → /scan …"
        model.fetchAllDiagnostics { text in
            diagText = text
            loadingDiag = false
            UIPasteboard.general.string = text
            autoCopied = true
            showDiag = true
        }
    }

    /// App 自己的版本号，来自 Info.plist。
    /// 用处很实际：改完一轮之后要能一眼确认装的是不是刚下载的那个 IPA。
    ///
    /// ⚠️ Info.plist 里必须写 $(MARKETING_VERSION) / $(CURRENT_PROJECT_VERSION)，
    /// 不能写死 —— 之前写死成 1.0.0，导致 project.yml 改版本、CI 注入构建号
    /// 全都无效，App 里永远显示 1.0.0。
    private static var appVersion: String {
        let v = Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "?"
        let b = Bundle.main.infoDictionary?["CFBundleVersion"] as? String ?? "?"
        return "\(v) (\(b))"
    }

    /// 车机上的桥接版本，来自 /state 的 src.apkVer。
    ///
    /// 三种情况必须分开显示 —— 之前统一显示「未连接」，
    /// 结果车机装的是旧版 APK（没有 apkVer 字段）时会被误判成没连上。
    private var bridgeVersion: String {
        guard let snap = model.car else { return "未连接" }
        if let v = snap.src?["apkVer"], !v.isEmpty { return v }
        return "旧版 APK（无版本标识）"
    }

    /// 「目的地」那一行怎么显示：名字 + 来源 + 坐标。
    ///
    /// ⚠️ 车机端只有**带坐标**的目的地才会下发（见 DestSignals.usable），
    /// 所以这里能显示出来就说明能用来导航。
    /// 来源 `amap` = 高德广播（手动点导航也能认出来），`voice` = 语音助手。
    private static func destLine(_ d: Dest?) -> String {
        guard let d else { return "（车机没在导航）" }
        // 老老实实写 if-else：对 Optional 做 switch 的写法在这类地方栽过，
        // 宁可啰嗦一点，别为省两行去烧一轮 CI。
        let who: String
        if d.src == "amap" {
            who = "高德广播"
        } else if d.src == "voice" {
            who = "语音"
        } else if let s = d.src, !s.isEmpty {
            who = s
        } else {
            who = "?"
        }
        var s = "\(d.name ?? "未命名") · \(who)"
        if let la = d.lat, let lo = d.lon {
            s += String(format: " · %.5f,%.5f", la, lo)
        } else {
            s += " · 无坐标（不会导航）"
        }
        return s
    }

    private static let tsFormat: DateFormatter = {
        let f = DateFormatter()
        f.dateFormat = "HH:mm:ss"
        return f
    }()
}

/// 诊断文本查看器：等宽字体、可选中、一键复制。
struct DiagView: View {
    let title: String
    let text: String
    /// 一键诊断已经自动复制过了 —— 打开就显示「已复制」，用户直接去粘
    var preCopied = false

    @Environment(\.dismiss) private var dismiss
    @State private var copied = false

    var body: some View {
        NavigationView {
            ScrollView([.vertical, .horizontal]) {
                Text(text)
                    .font(.system(size: 12, design: .monospaced))
                    .textSelection(.enabled)
                    .fixedSize(horizontal: true, vertical: false)
                    .padding()
            }
            .navigationTitle(title)
            .navigationBarTitleDisplayMode(.inline)
            .onAppear { copied = preCopied }
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("关闭") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button(copied ? "已复制" : "复制") {
                        UIPasteboard.general.string = text
                        copied = true
                    }
                }
            }
        }
        .navigationViewStyle(.stack)
    }
}
