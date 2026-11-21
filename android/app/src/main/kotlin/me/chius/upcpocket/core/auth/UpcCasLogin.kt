package me.chius.upcpocket.core.auth

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Locale
import me.chius.upcpocket.core.model.SchoolLoginCredential

private fun parseQueryParams(rawQuery: String?): Map<String, List<String>>? {
    if (rawQuery.isNullOrEmpty()) return emptyMap()
    val result = linkedMapOf<String, MutableList<String>>()
    for (pair in rawQuery.split('&')) {
        if (pair.isEmpty()) continue
        val eq = pair.indexOf('=')
        val rawKey = if (eq >= 0) pair.substring(0, eq) else pair
        val rawValue = if (eq >= 0) pair.substring(eq + 1) else ""
        val key = runCatching { URLDecoder.decode(rawKey, StandardCharsets.UTF_8.name()) }.getOrNull()
            ?: return null
        val value = runCatching { URLDecoder.decode(rawValue, StandardCharsets.UTF_8.name()) }.getOrNull()
            ?: return null
        result.getOrPut(key) { mutableListOf() }.add(value)
    }
    return result
}

/**
 * Exact CAS service boundary specifying allowed login URL shape and callback path/parameters.
 */
data class UpcCasService(
    val casHost: String,
    val serviceUrl: String,
    val callbackHost: String,
    val callbackPath: String,
    val callbackParameterNames: Set<String>,
    val maxRedirectHops: Int = 8,
    val isAllowedCallbackRedirect: (URI) -> Boolean = { true },
) {
    fun isCasLoginUrl(uri: URI): Boolean {
        if (!uri.scheme.equals("https", ignoreCase = true) ||
            uri.host?.lowercase(Locale.ROOT) != casHost.lowercase(Locale.ROOT) ||
            (uri.port != -1 && uri.port != 443) ||
            uri.rawUserInfo != null ||
            uri.rawPath != "/cas/login" ||
            uri.rawFragment != null
        ) {
            return false
        }
        val query = parseQueryParams(uri.rawQuery) ?: return false
        if (query.keys.any { it !in ALLOWED_LOGIN_PARAMS }) return false
        if (query.values.any { it.size != 1 }) return false
        if (query["service"]?.singleOrNull() != serviceUrl) return false
        query["from"]?.singleOrNull()?.let { if (it != "wap") return false }
        query["noAutoRedirect"]?.singleOrNull()?.let { if (it != "1") return false }
        return true
    }

    fun isCallbackUrl(uri: URI): Boolean {
        if (!uri.scheme.equals("https", ignoreCase = true) ||
            uri.host?.lowercase(Locale.ROOT) != callbackHost.lowercase(Locale.ROOT) ||
            (uri.port != -1 && uri.port != 443) ||
            uri.rawUserInfo != null ||
            uri.rawPath != callbackPath ||
            uri.rawFragment != null
        ) {
            return false
        }
        val query = parseQueryParams(uri.rawQuery) ?: return false
        if (query.keys.any { it !in callbackParameterNames }) return false
        if (query.values.any { it.size != 1 }) return false
        val ticket = query["ticket"]?.singleOrNull() ?: return false
        if (!ticket.startsWith("ST-") || ticket.length !in 8..4096) return false
        return isAllowedCallbackRedirect(uri)
    }

    private companion object {
        val ALLOWED_LOGIN_PARAMS = setOf("service", "from", "noAutoRedirect")
    }
}

sealed interface CasLoginResult {
    class Success(
        val targetCookies: Map<String, String>,
        val finalUrl: String,
    ) : CasLoginResult {
        override fun toString(): String =
            "CasLoginResult.Success(cookieCount=${targetCookies.size})"
    }

    data object InvalidCredentials : CasLoginResult

    data class InteractionRequired(
        val interactionUrl: String,
        val reason: String,
    ) : CasLoginResult

    data class NetworkError(val message: String) : CasLoginResult

    data class UpstreamError(val message: String) : CasLoginResult
}

internal data class CasLoginForm(
    val action: String?,
    val execution: String?,
    val type: String,
    val eventId: String,
    val submit: String,
    val captchaRequired: Boolean,
    val errorText: String?,
    val hasPassword: Boolean,
    val hasSecondFactor: Boolean,
    val hasTypeField: Boolean,
    val hasEventIdField: Boolean,
)

/**
 * Shared single-shot UPC CAS username/password form authenticator.
 *
 * Security invariants:
 * - Never follows redirects automatically.
 * - Detects captcha / SMS token challenges before password submission and delegates to WebView.
 * - Submits the password at most once per invocation to an exact verified `cas.upc.edu.cn` form.
 * - Rejects explicit credential errors without auto-retrying.
 * - Keeps cookies in a scoped in-memory jar and only exports cookies for [UpcCasService.callbackHost].
 */
class UpcCasLogin(
    private val service: UpcCasService,
    private val transport: CasHttpTransport = DefaultCasHttpTransport,
) {
    suspend fun login(
        credential: SchoolLoginCredential,
        casUrl: String,
        isAllowedAppHop: (URI) -> Boolean = { uri ->
            uri.scheme.equals("https", ignoreCase = true) &&
                uri.host?.lowercase(Locale.ROOT) == service.callbackHost.lowercase(Locale.ROOT) &&
                (uri.port == -1 || uri.port == 443) &&
                uri.rawUserInfo == null &&
                uri.rawFragment == null
        },
    ): CasLoginResult {
        val casUri = parseUriOrNull(casUrl)
        if (casUri == null || !service.isCasLoginUrl(casUri)) {
            return CasLoginResult.UpstreamError("CAS 登录入口参数校验未通过")
        }

        val jar = UpcCasCookieJar(allowedHosts = setOf(service.casHost, service.callbackHost))
        return try {
            val getResponse = executeWithJar(jar, CasHttpRequest(url = casUrl, method = "GET"))
            if (getResponse.statusCode in 300..399) {
                return CasLoginResult.UpstreamError("学校认证页返回意外重定向（HTTP ${getResponse.statusCode}）")
            }
            if (getResponse.statusCode == 429) {
                return CasLoginResult.UpstreamError("学校认证请求过于频繁，请稍后重试")
            }
            if (getResponse.statusCode !in setOf(200, 401, 403)) {
                return CasLoginResult.UpstreamError("学校认证服务暂时不可用（HTTP ${getResponse.statusCode}）")
            }

            val form = runCatching { parseCasLoginForm(getResponse.body) }.getOrNull()
            if (form != null && (form.captchaRequired || form.hasSecondFactor)) {
                return CasLoginResult.InteractionRequired(
                    interactionUrl = casUrl,
                    reason = if (form.hasSecondFactor) "需要二次验证" else "需要图形或行为验证码",
                )
            }
            if (form != null && isExplicitCasCredentialRejection(form.errorText)) {
                return CasLoginResult.InvalidCredentials
            }
            val validatedAction = validateAndResolveFormAction(form, casUrl)
                ?: return if (getResponse.statusCode in setOf(401, 403)) {
                    CasLoginResult.UpstreamError("学校认证拒绝访问（HTTP ${getResponse.statusCode}）")
                } else {
                    CasLoginResult.UpstreamError("未检测到可验证的 CAS 登录表单")
                }

            val validatedForm = requireNotNull(form)
            val formBody = DefaultCasHttpTransport.formEncode(
                listOf(
                    "username" to credential.loginName,
                    "password" to String(credential.password),
                    "type" to validatedForm.type,
                    "execution" to validatedForm.execution.orEmpty(),
                    "_eventId" to validatedForm.eventId,
                    "submit" to validatedForm.submit,
                ),
            )

            val postResponse = executeWithJar(
                jar = jar,
                request = CasHttpRequest(
                    url = validatedAction,
                    method = "POST",
                    headers = mapOf(
                        "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
                        "Origin" to "https://${service.casHost}",
                        "Referer" to casUrl,
                    ),
                    formBody = formBody,
                ),
            )

            if (postResponse.statusCode in 300..399) {
                val location = postResponse.location
                    ?: return CasLoginResult.UpstreamError("CAS 认证重定向缺少目标地址")
                val targetUri = resolveHttpUri(validatedAction, location)
                    ?: return CasLoginResult.UpstreamError("CAS 认证重定向目标无效")

                return when {
                    service.isCasLoginUrl(targetUri) -> classifyReturnedCasPage(
                        jar = jar,
                        targetUrl = targetUri.toString(),
                        interactionUrl = casUrl,
                    )
                    service.isCallbackUrl(targetUri) -> followCallbackHops(
                        jar = jar,
                        startUri = targetUri,
                        isAllowedAppHop = isAllowedAppHop,
                    )
                    else -> CasLoginResult.UpstreamError("CAS 回调目标未通过域与参数白名单校验")
                }
            }

            if (postResponse.statusCode == 429) {
                return CasLoginResult.UpstreamError("学校认证请求过于频繁，请稍后重试")
            }
            if (postResponse.statusCode !in setOf(200, 401, 403)) {
                return CasLoginResult.UpstreamError("学校认证提交返回 HTTP ${postResponse.statusCode}")
            }

            val returnedForm = runCatching { parseCasLoginForm(postResponse.body) }.getOrNull()
            if (returnedForm != null) {
                if (returnedForm.captchaRequired || returnedForm.hasSecondFactor) {
                    return CasLoginResult.InteractionRequired(
                        interactionUrl = casUrl,
                        reason = if (returnedForm.hasSecondFactor) "需要二次验证" else "需要验证码验证",
                    )
                }
                if (isExplicitCasCredentialRejection(returnedForm.errorText)) {
                    return CasLoginResult.InvalidCredentials
                }
            }
            CasLoginResult.InteractionRequired(
                interactionUrl = casUrl,
                reason = "需要完成交互式学校登录验证",
            )
        } catch (failure: CasTransportException) {
            when (failure.failure) {
                CasTransportFailure.NETWORK,
                CasTransportFailure.TIMEOUT,
                -> CasLoginResult.NetworkError(failure.message ?: "无法连接学校认证服务，请检查网络后重试")
                CasTransportFailure.TLS,
                CasTransportFailure.TOO_LARGE,
                CasTransportFailure.PROTOCOL,
                -> CasLoginResult.UpstreamError(failure.message ?: "学校认证连接或协议异常")
            }
        } finally {
            jar.clear()
        }
    }

    private suspend fun classifyReturnedCasPage(
        jar: UpcCasCookieJar,
        targetUrl: String,
        interactionUrl: String,
    ): CasLoginResult {
        val response = executeWithJar(jar, CasHttpRequest(url = targetUrl, method = "GET"))
        if (response.statusCode == 429) {
            return CasLoginResult.UpstreamError("学校认证请求过于频繁，请稍后重试")
        }
        if (response.statusCode >= 500 || response.statusCode !in setOf(200, 401, 403)) {
            return CasLoginResult.UpstreamError("CAS 返回页面异常（HTTP ${response.statusCode}）")
        }
        val form = runCatching { parseCasLoginForm(response.body) }.getOrNull()
        if (form != null) {
            if (form.captchaRequired || form.hasSecondFactor) {
                return CasLoginResult.InteractionRequired(
                    interactionUrl = interactionUrl,
                    reason = if (form.hasSecondFactor) "需要二次验证" else "需要验证码验证",
                )
            }
            if (isExplicitCasCredentialRejection(form.errorText)) {
                return CasLoginResult.InvalidCredentials
            }
        }
        return CasLoginResult.InteractionRequired(
            interactionUrl = interactionUrl,
            reason = "需要通过页面完成学校验证",
        )
    }

    private suspend fun followCallbackHops(
        jar: UpcCasCookieJar,
        startUri: URI,
        isAllowedAppHop: (URI) -> Boolean,
    ): CasLoginResult {
        var current = startUri
        repeat(service.maxRedirectHops) {
            if (!isAllowedAppHop(current)) {
                return CasLoginResult.UpstreamError("业务回调跳转超出了允许的官方路径")
            }
            val currentUrl = current.toString()
            val response = executeWithJar(jar, CasHttpRequest(url = currentUrl, method = "GET"))
            if (response.statusCode in 300..399) {
                val location = response.location
                    ?: return CasLoginResult.UpstreamError("业务回调重定向缺少 Location")
                current = resolveHttpUri(currentUrl, location)
                    ?: return CasLoginResult.UpstreamError("业务回调重定向地址非法")
                return@repeat
            }
            if (response.statusCode == 401 || response.statusCode == 403) {
                return CasLoginResult.UpstreamError("业务回调鉴权被拒绝（HTTP ${response.statusCode}）")
            }
            if (response.statusCode != 200) {
                return CasLoginResult.UpstreamError("业务回调返回 HTTP ${response.statusCode}")
            }
            val appCookies = jar.cookiesForHost(service.callbackHost)
            if (appCookies.isEmpty()) {
                return CasLoginResult.UpstreamError("登录完成但未收到业务会话 Cookie")
            }
            return CasLoginResult.Success(
                targetCookies = appCookies,
                finalUrl = currentUrl,
            )
        }
        return CasLoginResult.UpstreamError("业务回调跳转次数超过上限")
    }

    private suspend fun executeWithJar(
        jar: UpcCasCookieJar,
        request: CasHttpRequest,
    ): CasHttpResponse {
        val cookieHeader = jar.cookieHeader(request.url)
        val mergedHeaders = if (cookieHeader != null) {
            request.headers.filterKeys { !it.equals("Cookie", ignoreCase = true) } +
                ("Cookie" to cookieHeader)
        } else {
            request.headers.filterKeys { !it.equals("Cookie", ignoreCase = true) }
        }
        val response = transport.execute(request.copy(headers = mergedHeaders))
        jar.saveFromHeaders(request.url, response.setCookieHeaders)
        return response
    }

    private fun validateAndResolveFormAction(form: CasLoginForm?, pageUrl: String): String? {
        if (form == null) return null
        if (!form.hasPassword || form.captchaRequired || form.hasSecondFactor) return null
        if (!form.hasTypeField || form.type != "username_password") return null
        if (!form.hasEventIdField || form.eventId != "submit") return null
        val execution = form.execution ?: return null
        if (!isValidCasExecution(execution)) return null
        val actionUri = resolveHttpUri(pageUrl, form.action ?: pageUrl) ?: return null
        return if (service.isCasLoginUrl(actionUri)) actionUri.toString() else null
    }

    private companion object {
        const val MAX_CAS_EXECUTION_CHARS = 64 * 1024
        val EXPLICIT_CREDENTIAL_ERRORS = setOf(
            "用户名或密码错误",
            "账号或密码错误",
            "用户名或密码不正确",
            "账号或密码不正确",
            "密码错误",
            "密码不正确",
            "Invalidusernameorpassword",
            "Invalidcredentials",
        )
        val CAS_FORM = Regex("<form\\b[^>]*>.*?</form>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        val CAS_FORM_OPEN = Regex("<form\\b([^>]*)>", RegexOption.IGNORE_CASE)
        val CAS_INPUT = Regex("<input\\b([^>]*)>", RegexOption.IGNORE_CASE)
        val CAS_HIDDEN_ATTR = Regex("(?:^|\\s)hidden(?:\\s|=|$)", RegexOption.IGNORE_CASE)
        val CAS_PASSWORD_INPUT = Regex("<input\\b[^>]*type\\s*=\\s*[\"']password[\"']", RegexOption.IGNORE_CASE)
        val CAS_ERROR_DIV = Regex(
            "<div\\b[^>]*id\\s*=\\s*[\"']errorDiv[\"'][^>]*>(.*?)</div>",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )
        val CAS_TAG = Regex("<[^>]+>")
        val CAS_WHITESPACE = Regex("\\s+")
        val CAS_ATTRIBUTE = Regex(
            "([A-Za-z_:][-A-Za-z0-9_:.]*)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s>]+))",
        )

        fun parseUriOrNull(raw: String): URI? = runCatching { URI(raw) }.getOrNull()

        fun resolveHttpUri(base: String, raw: String): URI? {
            val baseUri = parseUriOrNull(base) ?: return null
            return runCatching { baseUri.resolve(raw) }.getOrNull()
        }

        fun isValidCasExecution(execution: String?): Boolean =
            execution != null &&
                execution.isNotBlank() &&
                execution.length <= MAX_CAS_EXECUTION_CHARS &&
                execution.none { it <= ' ' || it == '\u007f' }

        fun isExplicitCasCredentialRejection(errorText: String?): Boolean {
            val normalized = errorText.orEmpty().replace(CAS_WHITESPACE, "")
            return normalized in EXPLICIT_CREDENTIAL_ERRORS
        }

        fun parseCasLoginForm(html: String): CasLoginForm {
            val markup = CasCaptchaChallengeDetector.visibleMarkup(html)
            val forms = CAS_FORM.findAll(markup).map { it.value }.toList()
            val chosen = forms.firstOrNull {
                isVisibleCasForm(it) && CAS_PASSWORD_INPUT.containsMatchIn(it)
            } ?: forms.firstOrNull(::isVisibleCasForm)

            val fields = linkedMapOf<String, String>()
            var hasPassword = false
            var action: String? = null
            chosen?.let { form ->
                val open = CAS_FORM_OPEN.find(form)?.groupValues?.get(1).orEmpty()
                action = casAttribute(open, "action")
                CAS_INPUT.findAll(form).forEach { match ->
                    val attrs = match.groupValues[1]
                    val name = casAttribute(attrs, "name") ?: return@forEach
                    if (casAttribute(attrs, "type").equals("password", ignoreCase = true)) {
                        hasPassword = true
                    }
                    fields.putIfAbsent(name, casAttribute(attrs, "value").orEmpty())
                }
            }

            val error = CAS_ERROR_DIV.find(markup)?.groupValues?.get(1)
                ?.replace(CAS_TAG, " ")
                ?.let(::decodeCasEntities)
                ?.replace(CAS_WHITESPACE, " ")
                ?.trim()
                ?.takeIf { it.isNotEmpty() }

            val captcha = CasCaptchaChallengeDetector.inspect(html).active
            val secondFactor = if (chosen != null && hasPassword) {
                hasActiveSecondFactor(chosen, fields["type"])
            } else {
                forms.any { form -> hasActiveSecondFactor(form, activeFormType(form)) }
            }

            return CasLoginForm(
                action = action,
                execution = fields["execution"],
                type = fields["type"] ?: "username_password",
                eventId = fields["_eventId"] ?: "submit",
                submit = fields["submit"] ?: "登录",
                captchaRequired = captcha,
                errorText = error,
                hasPassword = hasPassword,
                hasSecondFactor = secondFactor,
                hasTypeField = fields.containsKey("type"),
                hasEventIdField = fields.containsKey("_eventId"),
            )
        }

        fun activeFormType(form: String): String? = CAS_INPUT.findAll(form).firstOrNull { match ->
            casAttribute(match.groupValues[1], "name").equals("type", ignoreCase = true)
        }?.let { match -> casAttribute(match.groupValues[1], "value") }

        fun isVisibleCasForm(form: String): Boolean {
            val open = CAS_FORM_OPEN.find(form)?.groupValues?.get(1).orEmpty()
            if (CAS_HIDDEN_ATTR.containsMatchIn(open) ||
                casAttribute(open, "aria-hidden").equals("true", ignoreCase = true)
            ) {
                return false
            }
            val style = casAttribute(open, "style").orEmpty()
                .filterNot { it.isWhitespace() }
                .lowercase(Locale.ROOT)
            return "display:none" !in style && "visibility:hidden" !in style
        }

        fun hasActiveSecondFactor(form: String, formType: String?): Boolean {
            if (!isVisibleCasForm(form)) return false
            if (formType.equals("username_smstoken", ignoreCase = true)) return true
            return CAS_INPUT.findAll(form).any { match ->
                val attributes = match.groupValues[1]
                val name = casAttribute(attributes, "name")
                val type = casAttribute(attributes, "type")
                name.equals("username_smstoken", ignoreCase = true) &&
                    !type.equals("hidden", ignoreCase = true)
            }
        }

        fun casAttribute(attrs: String, name: String): String? =
            CAS_ATTRIBUTE.findAll(attrs).firstOrNull {
                it.groupValues[1].equals(name, ignoreCase = true)
            }?.groupValues?.let { groups ->
                groups[2].ifEmpty { groups[3].ifEmpty { groups[4] } }
            }?.let(::decodeCasEntities)

        fun decodeCasEntities(value: String): String = value
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
    }
}
