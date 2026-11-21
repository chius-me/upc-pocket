package me.chius.upcpocket.feature.account

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumTopAppBar
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import me.chius.upcpocket.core.designsystem.components.ExpressiveGroupedCard
import me.chius.upcpocket.core.designsystem.components.ExpressiveHeroCard
import me.chius.upcpocket.core.designsystem.components.ExpressiveListRow
import me.chius.upcpocket.core.designsystem.components.ExpressiveSectionHeader
import me.chius.upcpocket.core.designsystem.theme.GroupedCardPosition
import me.chius.upcpocket.core.model.AccountStatus
import me.chius.upcpocket.core.model.AccountWriteFailure
import me.chius.upcpocket.core.model.AccountWriteResult
import me.chius.upcpocket.core.model.SchoolAccountState
import me.chius.upcpocket.core.model.StudentIdValidator

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SchoolAccountScreen(
    accountState: SchoolAccountState,
    onBack: () -> Unit,
    onSaveAccount: suspend (loginName: String, password: CharArray) -> AccountWriteResult,
    onClearAccount: suspend () -> Boolean,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    var loginInput by remember { mutableStateOf("") }
    var passwordInput by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var isSaving by remember { mutableStateOf(false) }
    var showClearDialog by remember { mutableStateOf(false) }
    var feedbackMessage by remember { mutableStateOf<String?>(null) }
    var pendingSaveRequest by remember { mutableStateOf<Pair<String, CharArray>?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            passwordInput = ""
        }
    }

    androidx.compose.runtime.LaunchedEffect(pendingSaveRequest) {
        val request = pendingSaveRequest ?: return@LaunchedEffect
        isSaving = true
        val result = onSaveAccount(request.first, request.second)
        isSaving = false
        pendingSaveRequest = null
        feedbackMessage = when (result) {
            AccountWriteResult.Success -> "已加密保存到本机（状态：已保存，未向学校验证）"
            is AccountWriteResult.Failure -> when (result.reason) {
                AccountWriteFailure.INVALID_LOGIN_NAME -> "请输入有效的学校账号或学号"
                AccountWriteFailure.INVALID_PASSWORD -> "请输入有效的学校密码"
                AccountWriteFailure.ENCRYPTION_FAILED -> "Android Keystore 加密失败，未保存明文"
                AccountWriteFailure.STORAGE_FAILED -> "写入本机私有目录失败，请重试"
            }
        }
    }

    var pendingClearRequest by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(pendingClearRequest) {
        if (!pendingClearRequest) return@LaunchedEffect
        isSaving = true
        val ok = onClearAccount()
        isSaving = false
        pendingClearRequest = false
        feedbackMessage = if (ok) "已删除本机保存的学校账号与密文" else "删除失败，请重试"
    }

    val trimmedLogin = loginInput.trim()
    val studentIdValid = StudentIdValidator.isValid(trimmedLogin)

    val triggerSave = {
        if (!isSaving) {
            val passwordChars = passwordInput.toCharArray()
            passwordInput = ""
            showPassword = false
            pendingSaveRequest = trimmedLogin to passwordChars
        }
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text("删除学校账号？") },
            text = {
                Text("将清除本机加密保存的学校账号与密码，并递增账号代次以使关联业务会话失效。")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearDialog = false
                        pendingClearRequest = true
                    },
                ) {
                    Text("确认删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) {
                    Text("取消")
                }
            },
        )
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            MediumTopAppBar(
                title = { Text("学校账号") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = "返回",
                        )
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .consumeWindowInsets(innerPadding),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = innerPadding.calculateTopPadding() + 8.dp,
                bottom = innerPadding.calculateBottomPadding() + 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            item {
                val subtitle = when (accountState.status) {
                    AccountStatus.UNSET -> "默认不保存凭据；保存后状态为“已保存（未验证）”，不会自动登录或遍历业务。"
                    AccountStatus.SAVED_UNVERIFIED -> {
                        val masked = accountState.maskedLoginName ?: "已配置"
                        val idNote = if (accountState.isValidStudentId) "有效本人学号" else "非标准学号（校园卡余额将跳过）"
                        "$masked · 代次 #${accountState.generation} · $idNote"
                    }
                    AccountStatus.RECOVERY_REQUIRED -> "检测到本机加密凭据损坏或密钥失效，已清理异常状态，请重新保存。"
                }
                ExpressiveHeroCard(
                    title = "本机凭据状态",
                    subtitle = subtitle,
                    icon = Icons.Rounded.Key,
                    badgeText = accountState.status.label,
                    modifier = Modifier.padding(bottom = 18.dp),
                )
            }

            item {
                ExpressiveSectionHeader(
                    title = "加密保存到本机",
                    subtitle = "保存不等于已登录；密码绝不回显、不写日志",
                    modifier = Modifier.padding(bottom = 6.dp),
                )
            }

            item {
                ExpressiveGroupedCard(
                    position = GroupedCardPosition.SINGLE,
                    modifier = Modifier.padding(bottom = 18.dp),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        OutlinedTextField(
                            value = loginInput,
                            onValueChange = { loginInput = it },
                            label = { Text("统一身份认证账号 / 学号") },
                            placeholder = { Text("例如 8–16 位学号，或 S/Z/M/TS/BY 开头") },
                            singleLine = true,
                            leadingIcon = {
                                Icon(Icons.Rounded.Person, contentDescription = null)
                            },
                            supportingText = {
                                val helper = when {
                                    trimmedLogin.isEmpty() -> "支持本科生纯数字学号及 S/Z/M/TS/BY 研究生学号"
                                    studentIdValid -> "已识别为有效的 UPC 学号格式"
                                    else -> "可保存为统一认证登录名；若非标准学号，校园卡余额将不发请求"
                                }
                                Text(helper)
                            },
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Ascii,
                                imeAction = ImeAction.Next,
                            ),
                            modifier = Modifier.fillMaxWidth(),
                        )

                        OutlinedTextField(
                            value = passwordInput,
                            onValueChange = { passwordInput = it },
                            label = { Text("学校账号密码") },
                            singleLine = true,
                            leadingIcon = {
                                Icon(Icons.Rounded.Lock, contentDescription = null)
                            },
                            trailingIcon = {
                                IconButton(onClick = { showPassword = !showPassword }) {
                                    Icon(
                                        imageVector = if (showPassword) {
                                            Icons.Rounded.VisibilityOff
                                        } else {
                                            Icons.Rounded.Visibility
                                        },
                                        contentDescription = if (showPassword) "隐藏密码" else "显示密码",
                                    )
                                }
                            },
                            visualTransformation = if (showPassword) {
                                VisualTransformation.None
                            } else {
                                PasswordVisualTransformation()
                            },
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Password,
                                imeAction = ImeAction.Done,
                            ),
                            keyboardActions = KeyboardActions(
                                onDone = { triggerSave() },
                            ),
                            modifier = Modifier.fillMaxWidth(),
                        )

                        if (!feedbackMessage.isNullOrBlank()) {
                            Text(
                                text = feedbackMessage.orEmpty(),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Button(
                                onClick = triggerSave,
                                enabled = !isSaving && trimmedLogin.isNotEmpty() && passwordInput.isNotEmpty(),
                                modifier = Modifier.weight(1f),
                            ) {
                                Text("加密保存到本机")
                            }

                            if (accountState.status != AccountStatus.UNSET) {
                                OutlinedButton(
                                    onClick = { showClearDialog = true },
                                    enabled = !isSaving,
                                    colors = ButtonDefaults.outlinedButtonColors(
                                        contentColor = MaterialTheme.colorScheme.error,
                                    ),
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.DeleteOutline,
                                        contentDescription = null,
                                        modifier = Modifier.padding(end = 4.dp),
                                    )
                                    Text("删除")
                                }
                            }
                        }
                    }
                }
            }

            item {
                ExpressiveSectionHeader(
                    title = "存储与验证边界",
                    modifier = Modifier.padding(bottom = 6.dp),
                )
            }

            item {
                ExpressiveListRow(
                    title = "Android Keystore AES-256-GCM",
                    subtitle = "密文写入应用私有 noBackupFilesDir，不进入系统云备份或换机迁移。",
                    leadingIcon = Icons.Rounded.Lock,
                    position = GroupedCardPosition.TOP,
                )
            }

            item {
                ExpressiveListRow(
                    title = "按需认证，不后台遍历",
                    subtitle = "保存操作仅加密落盘；M1 起仅在打开课表或查询时向 cas.upc.edu.cn 提交一次表单。",
                    leadingIcon = Icons.Rounded.Person,
                    position = GroupedCardPosition.BOTTOM,
                )
            }

            item {
                Spacer(modifier = Modifier.height(8.dp))
            }
        }
    }
}
