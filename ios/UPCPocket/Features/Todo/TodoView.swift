import SwiftUI

struct TodoView: View {
    @Bindable var environment: AppEnvironment
    @State private var showEditor = false
    @State private var editingTask: TodoTask?

    private var state: TodoUiState { environment.todoState }

    var body: some View {
        List {
            if let hint = state.reminderPermissionHint {
                Section {
                    Label(hint, systemImage: "bell.slash")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }

            Section {
                if state.incompleteTasks.isEmpty {
                    VStack(alignment: .leading, spacing: 6) {
                        Text("暂无未完成待办")
                            .font(.headline)
                        Text("点击右上角「+」新建待办，可分别设置独立截止时间与一次性系统提醒，数据仅保存在本机。")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                    .padding(.vertical, 6)
                } else {
                    ForEach(state.incompleteTasks) { task in
                        todoRow(task)
                    }
                    .onDelete { offsets in
                        for idx in offsets {
                            let id = state.incompleteTasks[idx].id
                            Task { await environment.deleteTodo(id) }
                        }
                    }
                }
            } header: {
                Text("未完成（\(state.incompleteTasks.count)）")
            } footer: {
                Text("按截止时间优先排列，提醒时刻不参与排序。")
            }

            if !state.completedTasks.isEmpty {
                Section("已完成（\(state.completedTasks.count)）") {
                    ForEach(state.completedTasks) { task in
                        todoRow(task)
                    }
                    .onDelete { offsets in
                        for idx in offsets {
                            let id = state.completedTasks[idx].id
                            Task { await environment.deleteTodo(id) }
                        }
                    }
                }
            }
        }
        .listStyle(.insetGrouped)
        .navigationTitle("本机待办")
        .toolbar {
            if !state.completedTasks.isEmpty {
                ToolbarItem(placement: .topBarLeading) {
                    Button("清除已完成") {
                        Task { await environment.clearCompletedTodos() }
                    }
                }
            }
            ToolbarItem(placement: .topBarTrailing) {
                Button {
                    editingTask = nil
                    showEditor = true
                } label: {
                    Image(systemName: "plus")
                }
                .accessibilityLabel("新建待办")
            }
        }
        .sheet(isPresented: $showEditor) {
            TodoEditorSheet(
                environment: environment,
                initialTask: editingTask
            )
        }
    }

    private func todoRow(_ task: TodoTask) -> some View {
        let nowMillis = Int64(Date().timeIntervalSince1970 * 1000)
        let urgent = !task.completed && TodoOrdering.isOverdueOrToday(task, nowMillis: nowMillis)
        return HStack(spacing: 12) {
            Button {
                Task { await environment.toggleTodoCompleted(task.id) }
            } label: {
                Image(systemName: task.completed ? "checkmark.circle.fill" : "circle")
                    .font(.title3)
                    .foregroundStyle(task.completed ? UPCTheme.campusJade : .secondary)
            }
            .buttonStyle(.plain)

            Button {
                editingTask = task
                showEditor = true
            } label: {
                VStack(alignment: .leading, spacing: 3) {
                    Text(task.title)
                        .strikethrough(task.completed)
                        .foregroundStyle(task.completed ? .secondary : .primary)
                        .lineLimit(2)

                    let parts: [String] = [
                        task.dueAt.map { due in
                            let label = Self.formatMillis(due)
                            return urgent ? "截止 \(label)（今日/已逾期）" : "截止 \(label)"
                        },
                        task.reminderAt.map { rem in
                            let suffix = task.reminderFiredAt != nil ? "（已提醒）" : ""
                            return "提醒 \(Self.formatMillis(rem))\(suffix)"
                        }
                    ].compactMap { $0 }

                    if !parts.isEmpty {
                        Text(parts.joined(separator: " · "))
                            .font(.caption)
                            .foregroundStyle(urgent ? Color.red : .secondary)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            .buttonStyle(.plain)
        }
        .padding(.vertical, 2)
    }

    static func formatMillis(_ millis: Int64) -> String {
        let date = Date(timeIntervalSince1970: TimeInterval(millis) / 1000.0)
        let formatter = DateFormatter()
        formatter.dateFormat = "MM-dd HH:mm"
        return formatter.string(from: date)
    }
}

struct TodoEditorSheet: View {
    @Bindable var environment: AppEnvironment
    let initialTask: TodoTask?
    @Environment(\.dismiss) private var dismiss

    @State private var title: String
    @State private var hasDueDate: Bool
    @State private var dueDate: Date
    @State private var hasReminder: Bool
    @State private var reminderDate: Date
    @State private var permissionDeniedHint = false

    init(environment: AppEnvironment, initialTask: TodoTask?) {
        self.environment = environment
        self.initialTask = initialTask
        _title = State(initialValue: initialTask?.title ?? "")
        _hasDueDate = State(initialValue: initialTask?.dueAt != nil)
        let defaultDue = Calendar.current.date(bySettingHour: 23, minute: 59, second: 0, of: Date()) ?? Date()
        _dueDate = State(initialValue: initialTask?.dueAt.map { Date(timeIntervalSince1970: TimeInterval($0) / 1000.0) } ?? defaultDue)
        _hasReminder = State(initialValue: initialTask?.reminderAt != nil)
        _reminderDate = State(
            initialValue: initialTask?.reminderAt.map { Date(timeIntervalSince1970: TimeInterval($0) / 1000.0) }
                ?? Date().addingTimeInterval(3600)
        )
    }

    var body: some View {
        NavigationStack {
            Form {
                Section("待办内容") {
                    TextField("例如：提交实验报告", text: $title)
                        .onChange(of: title) { _, newValue in
                            if newValue.count > 200 {
                                title = String(newValue.prefix(200))
                            }
                        }
                }

                Section {
                    Toggle("设置独立截止时间", isOn: $hasDueDate)
                    if hasDueDate {
                        DatePicker("截止时刻", selection: $dueDate, displayedComponents: [.date, .hourAndMinute])
                    }
                } footer: {
                    Text("截止时间仅用于截止排序，与是否开启提醒相互独立。")
                }

                Section {
                    Toggle("一次性系统提醒", isOn: $hasReminder)
                        .onChange(of: hasReminder) { _, enabled in
                            if enabled {
                                Task {
                                    let granted = await environment.requestTodoNotificationPermissionIfNeeded()
                                    permissionDeniedHint = !granted
                                }
                            } else {
                                permissionDeniedHint = false
                            }
                        }
                    if hasReminder {
                        DatePicker("提醒时刻", selection: $reminderDate, displayedComponents: [.date, .hourAndMinute])
                    }
                    if permissionDeniedHint {
                        Text("系统通知权限未开启，待办仍会正常保存；开启通知授权后可到点弹窗提醒。")
                            .font(.caption)
                            .foregroundStyle(.red)
                    }
                }
            }
            .navigationTitle(initialTask == nil ? "新建待办" : "编辑待办")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("取消") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("保存") {
                        let trimmed = title.trimmingCharacters(in: .whitespacesAndNewlines)
                        let dueMillis = hasDueDate ? Int64(dueDate.timeIntervalSince1970 * 1000) : nil
                        let remMillis = hasReminder ? Int64(reminderDate.timeIntervalSince1970 * 1000) : nil
                        Task {
                            await environment.upsertTodo(
                                id: initialTask?.id,
                                title: trimmed,
                                dueAt: dueMillis,
                                reminderAt: remMillis
                            )
                            dismiss()
                        }
                    }
                    .disabled(title.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                }
            }
        }
    }
}
