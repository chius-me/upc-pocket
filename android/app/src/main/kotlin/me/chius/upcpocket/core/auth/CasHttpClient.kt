package me.chius.upcpocket.core.auth

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import javax.net.ssl.SSLException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class CasHttpRequest(
    val url: String,
    val method: String = "GET",
    val headers: Map<String, String> = emptyMap(),
    val formBody: String? = null,
) {
    override fun toString(): String =
        "CasHttpRequest(method=$method, url=$url, hasBody=${formBody != null})"
}

class CasHttpResponse(
    val statusCode: Int,
    val location: String?,
    val setCookieHeaders: List<String>,
    val body: String,
    val contentType: String? = null,
) {
    override fun toString(): String =
        "CasHttpResponse(statusCode=$statusCode, cookies=${setCookieHeaders.size}, bodyLength=${body.length})"
}

enum class CasTransportFailure {
    NETWORK,
    TIMEOUT,
    TLS,
    TOO_LARGE,
    PROTOCOL,
}

class CasTransportException(
    val failure: CasTransportFailure,
    message: String? = null,
    cause: Throwable? = null,
) : IOException(message ?: failure.name, cause)

fun interface CasHttpTransport {
    suspend fun execute(request: CasHttpRequest): CasHttpResponse
}

object DefaultCasHttpTransport : CasHttpTransport {
    const val MAX_RESPONSE_BYTES = 2 * 1024 * 1024

    override suspend fun execute(request: CasHttpRequest): CasHttpResponse = withContext(Dispatchers.IO) {
        val uri = runCatching { URI(request.url) }.getOrNull()
            ?: throw CasTransportException(CasTransportFailure.PROTOCOL, "Invalid URL")
        if (!uri.scheme.equals("https", ignoreCase = true)) {
            throw CasTransportException(CasTransportFailure.PROTOCOL, "Only HTTPS is allowed for CAS/app requests")
        }

        val connection = (uri.toURL().openConnection() as HttpURLConnection)
        try {
            connection.requestMethod = request.method
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.connectTimeout = 10_000
            connection.readTimeout = 20_000
            connection.setRequestProperty("Cache-Control", "no-cache, no-store")
            connection.setRequestProperty("User-Agent", "UPCPocket-Native/0.1")
            request.headers.forEach { (key, value) ->
                connection.setRequestProperty(key, value)
            }

            if (request.formBody != null) {
                connection.doOutput = true
                if (!request.headers.keys.any { it.equals("Content-Type", ignoreCase = true) }) {
                    connection.setRequestProperty(
                        "Content-Type",
                        "application/x-www-form-urlencoded; charset=UTF-8",
                    )
                }
                val bodyBytes = request.formBody.toByteArray(StandardCharsets.UTF_8)
                connection.setFixedLengthStreamingMode(bodyBytes.size)
                connection.outputStream.use { it.write(bodyBytes) }
            }

            val status = connection.responseCode
            val contentLength = connection.contentLengthLong
            if (contentLength > MAX_RESPONSE_BYTES) {
                throw CasTransportException(CasTransportFailure.TOO_LARGE)
            }

            var location: String? = null
            var contentType: String? = null
            val cookies = mutableListOf<String>()
            connection.headerFields.forEach { (name, values) ->
                if (name == null || values == null) return@forEach
                when {
                    name.equals("Location", ignoreCase = true) -> location = values.lastOrNull()
                    name.equals("Set-Cookie", ignoreCase = true) -> cookies.addAll(values)
                    name.equals("Content-Type", ignoreCase = true) -> contentType = values.lastOrNull()
                }
            }

            val stream = runCatching {
                if (status in 200..399) connection.inputStream else connection.errorStream
            }.getOrNull()

            val body = if (stream != null) {
                stream.use { input ->
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (output.size() + count > MAX_RESPONSE_BYTES) {
                            throw CasTransportException(CasTransportFailure.TOO_LARGE)
                        }
                        output.write(buffer, 0, count)
                    }
                    output.toString(StandardCharsets.UTF_8.name())
                }
            } else {
                ""
            }

            CasHttpResponse(
                statusCode = status,
                location = location,
                setCookieHeaders = cookies,
                body = body,
                contentType = contentType,
            )
        } catch (e: CasTransportException) {
            throw e
        } catch (e: SocketTimeoutException) {
            throw CasTransportException(CasTransportFailure.TIMEOUT, "学校认证或课表请求超时，请重试", e)
        } catch (e: SSLException) {
            throw CasTransportException(CasTransportFailure.TLS, "安全连接校验失败，已终止访问", e)
        } catch (e: IOException) {
            throw CasTransportException(CasTransportFailure.NETWORK, "无法连接学校服务，请检查网络后重试", e)
        } finally {
            connection.disconnect()
        }
    }

    fun formEncode(items: List<Pair<String, String>>): String =
        items.joinToString("&") { (key, value) ->
            URLEncoder.encode(key, StandardCharsets.UTF_8.name()) + "=" +
                URLEncoder.encode(value, StandardCharsets.UTF_8.name())
        }
}
