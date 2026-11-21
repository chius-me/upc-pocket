package me.chius.upcpocket.feature.calendar

import android.content.Context
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest
import java.util.Locale
import javax.net.ssl.SSLException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.chius.upcpocket.core.auth.CasHttpRequest
import me.chius.upcpocket.core.auth.CasHttpTransport
import me.chius.upcpocket.core.auth.CasTransportException
import me.chius.upcpocket.core.auth.DefaultCasHttpTransport

internal fun interface CalendarBinaryFetcher {
    suspend fun fetchBytes(url: String): ByteArray
}

/**
 * Fetches public UPC Academic Affairs calendar articles and caches high-res schedule images
 * in `noBackupFilesDir/academic_calendar` so they work offline without cloud backup leakage.
 */
class AcademicCalendarRepository internal constructor(
    private val cacheDir: File,
    private val transport: CasHttpTransport = DefaultCasHttpTransport,
    private val binaryFetcher: CalendarBinaryFetcher = DefaultCalendarBinaryFetcher,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    constructor(
        context: Context,
        transport: CasHttpTransport = DefaultCasHttpTransport,
    ) : this(
        cacheDir = File(context.applicationContext.noBackupFilesDir, DIR_NAME),
        transport = transport,
    )

    private val mutex = Mutex()
    private val _uiState = MutableStateFlow(CalendarUiState())
    val uiState: StateFlow<CalendarUiState> = _uiState.asStateFlow()

    suspend fun loadLatestIfEmpty() {
        val current = _uiState.value
        if (current.articles.isNotEmpty() && current.imageUrls.isNotEmpty()) return
        refresh()
    }

    suspend fun refresh() = withContext(ioDispatcher) {
        mutex.withLock {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            try {
                val listResponse = transport.execute(
                    CasHttpRequest(url = CalendarProtocol.LIST_URL, method = "GET"),
                )
                if (listResponse.statusCode != 200) {
                    throw CalendarException("校历栏目返回 HTTP ${listResponse.statusCode}")
                }
                val articles = CalendarProtocol.parseList(listResponse.body)
                if (articles.isEmpty()) {
                    throw CalendarException("未解析到教务处校历文章列表")
                }
                val prevSelected = _uiState.value.selectedArticle
                val target = prevSelected?.let { prev ->
                    articles.firstOrNull { it.pageUrl == prev.pageUrl }
                } ?: articles.first()

                loadArticleLocked(articles = articles, target = target)
            } catch (e: CancellationException) {
                throw e
            } catch (e: CasTransportException) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = e.message ?: "无法连接教务处官网，请检查网络",
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = e.message ?: "加载校历失败，请重试",
                    )
                }
            }
        }
    }

    suspend fun selectArticle(article: CalendarArticleSummary) = withContext(ioDispatcher) {
        mutex.withLock {
            val articles = _uiState.value.articles.ifEmpty { listOf(article) }
            _uiState.update {
                it.copy(selectedArticle = article, isLoading = true, errorMessage = null)
            }
            try {
                loadArticleLocked(articles = articles, target = article)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = e.message ?: "加载该学年校历失败",
                    )
                }
            }
        }
    }

    private suspend fun loadArticleLocked(
        articles: List<CalendarArticleSummary>,
        target: CalendarArticleSummary,
    ) {
        requireAllowedJwcUrl(target.pageUrl)
        val articleResponse = transport.execute(CasHttpRequest(url = target.pageUrl, method = "GET"))
        if (articleResponse.statusCode != 200) {
            throw CalendarException("校历页面返回 HTTP ${articleResponse.statusCode}")
        }
        val imageUrls = CalendarProtocol.parseArticleImages(articleResponse.body, target.pageUrl)
        if (imageUrls.isEmpty()) {
            throw CalendarException("该校历页面未包含图片附件，请尝试在浏览器打开")
        }

        val cachedPaths = linkedMapOf<String, String>()
        cacheDir.mkdirs()
        imageUrls.forEach { imageUrl ->
            requireAllowedJwcUrl(imageUrl)
            val file = File(cacheDir, fileNameForUrl(imageUrl))
            if (!file.isFile || file.length() == 0L) {
                val bytes = binaryFetcher.fetchBytes(imageUrl)
                val tmp = File(cacheDir, "${file.name}.tmp")
                tmp.writeBytes(bytes)
                if (file.exists()) file.delete()
                if (!tmp.renameTo(file)) {
                    file.writeBytes(bytes)
                    tmp.delete()
                }
            }
            if (file.isFile && file.length() > 0L) {
                cachedPaths[imageUrl] = file.absolutePath
            }
        }
        _uiState.update {
            it.copy(
                articles = articles,
                selectedArticle = target,
                imageUrls = imageUrls,
                cachedImagePaths = cachedPaths,
                isLoading = false,
                errorMessage = null,
            )
        }
    }

    private fun requireAllowedJwcUrl(rawUrl: String) {
        val uri = runCatching { URI(rawUrl) }.getOrNull() ?: throw CalendarException("校历地址不合法")
        if (!uri.scheme.equals("https", ignoreCase = true) ||
            uri.host?.lowercase(Locale.ROOT) != CalendarProtocol.HOST ||
            uri.rawUserInfo != null ||
            uri.port !in setOf(-1, 443)
        ) {
            throw CalendarException("拒绝访问非教务处官方 HTTPS 地址")
        }
    }

    private fun fileNameForUrl(url: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(url.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return "$digest.img"
    }

    private object DefaultCalendarBinaryFetcher : CalendarBinaryFetcher {
        private const val MAX_IMAGE_BYTES = 12 * 1024 * 1024

        override suspend fun fetchBytes(url: String): ByteArray = withContext(Dispatchers.IO) {
            val uri = URI(url)
            if (!uri.scheme.equals("https", ignoreCase = true) ||
                uri.host?.lowercase(Locale.ROOT) != CalendarProtocol.HOST
            ) {
                throw CalendarException("校历图片地址校验失败")
            }
            val conn = (uri.toURL().openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                instanceFollowRedirects = false
                connectTimeout = 10_000
                readTimeout = 25_000
                setRequestProperty("User-Agent", "UPCPocket-Native/0.3")
            }
            try {
                val status = conn.responseCode
                if (status != 200) {
                    throw CalendarException("校历图片下载失败（HTTP $status）")
                }
                val contentLength = conn.contentLengthLong
                if (contentLength > MAX_IMAGE_BYTES) {
                    throw CalendarException("校历图片体积超出限制")
                }
                val output = ByteArrayOutputStream()
                conn.inputStream.use { input ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        if (output.size() + n > MAX_IMAGE_BYTES) {
                            throw CalendarException("校历图片体积超出限制")
                        }
                        output.write(buffer, 0, n)
                    }
                }
                output.toByteArray()
            } catch (e: CalendarException) {
                throw e
            } catch (e: SSLException) {
                throw CalendarException("教务处证书校验失败", e)
            } catch (e: IOException) {
                throw CalendarException("下载校历图片失败，请检查网络", e)
            } finally {
                conn.disconnect()
            }
        }
    }

    private companion object {
        const val DIR_NAME = "academic_calendar"
    }
}
