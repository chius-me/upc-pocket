package me.chius.upcpocket.core.model

/**
 * Valid UPC student ID formats per specs/account-contract.md:
 * - 8..16 ASCII digits
 * - S / Z / M + 8 ASCII digits (length 9, case-sensitive)
 * - TS + 9 ASCII digits (length 11, case-sensitive)
 * - BY + 10 ASCII digits (length 12, case-sensitive)
 */
object StudentIdValidator {
    private const val MIN_DIGITS_LENGTH = 8
    private const val MAX_DIGITS_LENGTH = 16

    fun isValid(value: String): Boolean = when {
        value.length in MIN_DIGITS_LENGTH..MAX_DIGITS_LENGTH && hasAsciiDigitsFrom(value, 0) -> true
        value.length == 9 && (value[0] == 'S' || value[0] == 'Z' || value[0] == 'M') ->
            hasAsciiDigitsFrom(value, 1)
        value.length == 11 && value.startsWith("TS") -> hasAsciiDigitsFrom(value, 2)
        value.length == 12 && value.startsWith("BY") -> hasAsciiDigitsFrom(value, 2)
        else -> false
    }

    private fun hasAsciiDigitsFrom(value: String, startIndex: Int): Boolean =
        (startIndex until value.length).all { value[it] in '0'..'9' }
}

enum class AccountStatus(val label: String) {
    UNSET("未保存"),
    SAVED_UNVERIFIED("已保存（未验证）"),
    RECOVERY_REQUIRED("需要重新保存"),
}

data class SchoolStudentIdentity(
    val accountId: String,
    val studentId: String,
    val generation: Long,
) {
    init {
        require(StudentIdValidator.isValid(studentId)) { "Invalid studentId format" }
    }
}

data class SchoolAccountState(
    val status: AccountStatus = AccountStatus.UNSET,
    val generation: Long = 0L,
    val accountId: String? = null,
    val maskedLoginName: String? = null,
    val isValidStudentId: Boolean = false,
    val savedAtEpochMillis: Long? = null,
)

enum class AccountWriteFailure {
    INVALID_LOGIN_NAME,
    INVALID_PASSWORD,
    ENCRYPTION_FAILED,
    STORAGE_FAILED,
}

sealed interface AccountWriteResult {
    data object Success : AccountWriteResult
    data class Failure(val reason: AccountWriteFailure) : AccountWriteResult
}

internal fun maskLoginName(loginName: String): String {
    val trimmed = loginName.trim()
    return when {
        trimmed.isEmpty() -> ""
        trimmed.length <= 4 -> "*".repeat(trimmed.length)
        else -> "${trimmed.take(2)}${"*".repeat((trimmed.length - 4).coerceAtLeast(3))}${trimmed.takeLast(2)}"
    }
}
