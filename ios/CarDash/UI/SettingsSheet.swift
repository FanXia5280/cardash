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
