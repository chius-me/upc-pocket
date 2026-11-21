import SwiftUI

struct SchoolAccountView: View {
    @Bindable var environment: AppEnvironment

    @State private var loginName = ""
    @State private var password = ""
    @State private var isSaving = false
    @State private var showClearConfirmation = false
    @State private var feedbackMessage: String?

    private var trimmedLogin: String {
        loginName.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private var isStudentIdValid: Bool {
        StudentIdValidator.isValid(trimmedLogin)
    }

    var body: some View {
        Form {
            Section("本机凭据状态") {
                LabeledContent("状态", value: environment.accountState.status.label)
                if let masked = environment.accountState.maskedLoginName {
                    LabeledContent("已保存账号", value: masked)
                }
                LabeledContent("凭据代次", value: "#\(environment.accountState.generation)")
                if environment.accountState.status == .savedUnverified {
                    LabeledContent(
                        "学号规则",
                        value: environment.accountState.isValidStudentId ? "有效本人学号" : "非标准学号"
                    )
                }
            }

            Section {
                TextField("统一身份认证账号 / 学号", text: $loginName)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .keyboardType(.asciiCapable)

                SecureField("学校账号密码（保存后立即清空，不回显）", text: $password)
                    .textContentType(.password)
            } header: {
                Text("加密保存到本机")
            } footer: {
                Text(loginHelperText)
            }

            if let feedbackMessage {
                Section {
                    Text(feedbackMessage)
                        .font(.subheadline)
                        .foregroundStyle(UPCTheme.brandBlue)
                }
            }

            Section {
                Button {
                    saveAccount()
                } label: {
                    Text(isSaving ? "正在加密保存…" : "加密保存到本机")
                        .frame(maxWidth: .infinity)
                }
                .disabled(isSaving || trimmedLogin.isEmpty || password.isEmpty)

                if environment.accountState.status != .unset {
                    Button(role: .destructive) {
                        showClearConfirmation = true
                    } label: {
                        Text("删除学校账号")
                            .frame(maxWidth: .infinity)
                    }
                    .disabled(isSaving)
                }
            }

            Section("存储与验证边界") {
                Text("凭据通过 iOS Keychain（AfterFirstUnlockThisDeviceOnly）保存在本机，元数据写入排除云备份的无备份目录。保存成功仅代表本机已写入，不代表已登录或通过学校认证。")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
        }
        .formStyle(.grouped)
        .navigationTitle("学校账号")
        .navigationBarTitleDisplayMode(.inline)
        .onDisappear {
            password = ""
        }
        .confirmationDialog(
            "删除学校账号？",
            isPresented: $showClearConfirmation,
            titleVisibility: .visible
        ) {
            Button("确认删除", role: .destructive) {
                clearAccount()
            }
            Button("取消", role: .cancel) {}
        } message: {
            Text("将清除钥匙串中的学校凭据并递增代次以使旧会话失效，不会删除专注设置。")
        }
    }

    private var loginHelperText: String {
        if trimmedLogin.isEmpty {
            return "支持 8–16 位纯数字学号，以及 S/Z/M/TS/BY 前缀研究生学号。"
        }
        if isStudentIdValid {
            return "已识别为有效的 UPC 学号格式。"
        }
        return "可作为统一认证登录名保存；若非标准学号，校园卡余额将不发起请求。"
    }

    private func saveAccount() {
        let login = trimmedLogin
        let secret = password
        password = ""
        isSaving = true

        Task {
            let result = await environment.saveAccount(loginName: login, password: secret)
            isSaving = false
            switch result {
            case .success:
                feedbackMessage = "已加密保存到本机（状态：已保存，未向学校验证）"
            case .failure(.invalidLoginName):
                feedbackMessage = "请输入有效的学校账号或学号"
            case .failure(.invalidPassword):
                feedbackMessage = "请输入有效的学校密码"
            case .failure(.keychainFailed):
                feedbackMessage = "写入系统钥匙串失败，未保存明文"
            case .failure(.storageFailed):
                feedbackMessage = "写入本机无备份目录失败"
            }
        }
    }

    private func clearAccount() {
        isSaving = true
        Task {
            let ok = await environment.clearAccount()
            isSaving = false
            feedbackMessage = ok ? "已删除本机保存的学校账号" : "删除失败，请重试"
        }
    }
}
