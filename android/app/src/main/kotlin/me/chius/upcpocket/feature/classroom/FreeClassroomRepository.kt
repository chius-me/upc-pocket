package me.chius.upcpocket.feature.classroom

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class FreeClassroomRepository internal constructor(
    private val requester: suspend (String) -> String = ::defaultRequestFreeClassrooms,
    private val clock: () -> Long = System::currentTimeMillis,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend fun query(query: FreeClassroomQuery): Result<FreeClassroomResult> =
        withContext(ioDispatcher) {
            try {
                query.validate()
                val formBody = FreeClassroomProtocol.formBody(query)
                val response = requester(formBody)
                val parsed = FreeClassroomProtocol.parse(
                    body = response,
                    query = query,
                    fetchedAtEpochMillis = clock().coerceAtLeast(1L),
                )
                Result.success(parsed)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Result.failure(error)
            }
        }
}

private suspend fun defaultRequestFreeClassrooms(formBody: String): String =
    withContext(Dispatchers.IO) {
        val connection = URI(FreeClassroomProtocol.ENDPOINT).toURL().openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            connection.useCaches = false
            connection.doOutput = true
            connection.setRequestProperty(
                "Content-Type",
                "application/x-www-form-urlencoded; charset=UTF-8",
            )
            connection.setRequestProperty("Accept", "application/json")

            val bytes = formBody.toByteArray(Charsets.UTF_8)
            connection.setFixedLengthStreamingMode(bytes.size)
            connection.outputStream.use { it.write(bytes) }

            when (val status = connection.responseCode) {
                200 -> Unit
                in 300..399, 401, 403 -> throw IOException("学校空闲教室服务暂时不可用（HTTP $status）")
                else -> throw IOException("学校空闲教室服务暂时不可用（HTTP $status）")
            }

            connection.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (output.size() + count > FreeClassroomProtocol.MAX_RESPONSE_BYTES) {
                        throw IOException("空闲教室响应过大，请稍后重试")
                    }
                    output.write(buffer, 0, count)
                }
                output.toString(Charsets.UTF_8.name())
            }
        } finally {
            connection.disconnect()
        }
    }
