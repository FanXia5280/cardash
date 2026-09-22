import SwiftUI
import UIKit

struct SettingsSheet: View {
    @ObservedObject var model: DashboardModel
    @Environment(\.dismiss) private var dismiss

    @State private var diagTitle = ""
    @State private var diagText = ""
    @State private var showDiag = false
    @State private var loadingDiag = false

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
                    if loadingDiag {
                        HStack {
                            Text("正在读取…")
                            Spacer()
                            ProgressView()
                        }
                    } else {
                        Button("logcat 取数诊断") { load("/logcat", "logcat 取数诊断") }
                        Button("厂商属性扫描") { load("/scan", "厂商属性扫描") }
                        Button("车机运行状态") { load("/diag", "车机运行状态") }
                        Button("桥接运行日志") { load("/log", "桥接运行日志") }
                    }
                } header: {
                    Text("车机诊断")
                } footer: {
                    Text("车机上没有浏览器也没关系，这里直接读。"
                         + "打开后点右上角「复制」就能整段发出来。")
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
            DiagView(title: diagTitle, text: diagText)
        }
    }

    private func load(_ path: String, _ title: String) {
        loadingDiag = true
        diagTitle = title
        diagText = "正在读取 \(path) …"
        model.fetchText(path: path) { text in
            diagText = text
            loadingDiag = false
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
