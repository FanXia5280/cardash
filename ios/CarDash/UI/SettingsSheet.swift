import SwiftUI

struct SettingsSheet: View {
    @ObservedObject var model: DashboardModel
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationView {
            Form {
                Section {
                    TextField("例如 192.168.43.1", text: $model.host)
                        .keyboardType(.numbersAndPunctuation)
                        .autocorrectionDisabled()
                        .textInputAutocapitalization(.never)
                    Button("立即重连") { model.reconnectNow() }
                } header: {
                    Text("车机地址")
                } footer: {
                    Text("端口默认 \(DashboardModel.defaultPort)，留空则只显示本机 GPS 数据。")
                }

                Section("连接状态") {
                    HStack {
                        Text("链路")
                        Spacer()
                        Text(model.link.label)
                            .foregroundStyle(model.link.isOnline ? .green : .secondary)
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
    }

    private static let tsFormat: DateFormatter = {
        let f = DateFormatter()
        f.dateFormat = "HH:mm:ss"
        return f
    }()
}
