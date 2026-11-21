import SwiftUI

struct AboutView: View {
    var body: some View {
        Form {
            Section {
                VStack(alignment: .leading, spacing: 6) {
                    Text("UPC Pocket")
                        .font(.title2.weight(.semibold))
                    Text("面向中国石油大学（华东）的非官方纯原生校园工具 · 版本 \(appVersion)")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                }
                .padding(.vertical, 4)
            }

            Section("定位与声明") {
                Text("本应用不是学校官方客户端。所有校园数据查询直接请求学校官方服务，不经过自建账号或数据中转服务器。")
                    .font(.subheadline)
            }

            Section("开源协议与致谢") {
                Text("本仓库代码采用 MIT 许可证。石大服务入口与只读协议语义参考自 UPCPocket 与 UPC-MCP（MIT License, Copyright (c) 2026 3beuml）。")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            }
        }
        .formStyle(.grouped)
        .navigationTitle("关于")
        .navigationBarTitleDisplayMode(.inline)
    }

    private var appVersion: String {
        Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "0.2.0"
    }
}
