package top.colter.dynamic.bilibili

import kotlinx.coroutines.CancellationException
import top.colter.bilibili.exception.BiliAuthException
import top.colter.bilibili.exception.BiliBanException
import top.colter.bilibili.exception.BiliEmptyException
import top.colter.bilibili.exception.BiliException
import top.colter.bilibili.exception.BiliLoginException
import top.colter.bilibili.exception.BiliRequestException
import top.colter.dynamic.core.tools.loggerFor

private val requestFailureLogger = loggerFor<BilibiliRequestFailureHandler>()

internal class BilibiliRequestFailureHandler(
    private val configProvider: () -> BilibiliPublisherConfig,
) {
    private var consecutiveLoginFailures: Int = 0
    private var pollingPausedByLoginFailure: Boolean = false

    fun isPollingPaused(): Boolean = pollingPausedByLoginFailure

    suspend fun <T> run(
        operation: String,
        block: suspend () -> T,
    ): Result<T> {
        return try {
            val result = block()
            recordSuccess(operation)
            Result.success(result)
        } catch (error: Throwable) {
            recordFailure(operation, error)
            Result.failure(error)
        }
    }

    fun recordSuccess(operation: String) {
        if (consecutiveLoginFailures > 0 || pollingPausedByLoginFailure) {
            requestFailureLogger.info {
                "Bilibili 请求已恢复：operation=$operation，之前连续未登录失败=$consecutiveLoginFailures"
            }
        }
        consecutiveLoginFailures = 0
        pollingPausedByLoginFailure = false
    }

    fun recordFailure(operation: String, error: Throwable) {
        if (error is CancellationException) throw error

        when (error) {
            is BiliLoginException -> recordLoginFailure(operation, error)
            is BiliBanException -> recordNonLoginBiliFailure(
                operation = operation,
                error = error,
                category = "请求被风控或拦截",
            )
            is BiliAuthException -> recordNonLoginBiliFailure(
                operation = operation,
                error = error,
                category = "认证异常",
            )
            is BiliEmptyException -> recordNonLoginBiliFailure(
                operation = operation,
                error = error,
                category = "接口无数据",
            )
            is BiliRequestException -> recordNonLoginBiliFailure(
                operation = operation,
                error = error,
                category = "请求异常",
            )
            is BiliException -> recordNonLoginBiliFailure(
                operation = operation,
                error = error,
                category = "Bilibili 接口异常",
            )
            else -> recordUnknownFailure(operation, error)
        }
    }

    private fun recordLoginFailure(operation: String, error: BiliLoginException) {
        consecutiveLoginFailures += 1
        val threshold = configProvider().maxConsecutiveLoginFailures
        if (threshold > 0 && consecutiveLoginFailures >= threshold) {
            if (!pollingPausedByLoginFailure) {
                pollingPausedByLoginFailure = true
                requestFailureLogger.error(error) {
                    "Bilibili 登录状态失效，已暂停轮询请求：operation=$operation，连续未登录失败=$consecutiveLoginFailures，阈值=$threshold。请重新登录或更新 Cookie。"
                }
            } else {
                requestFailureLogger.warn(error) {
                    "Bilibili 轮询仍处于未登录暂停状态：operation=$operation，连续未登录失败=$consecutiveLoginFailures，阈值=$threshold"
                }
            }
            return
        }

        requestFailureLogger.warn(error) {
            if (threshold > 0) {
                "Bilibili 请求未登录：operation=$operation，连续未登录失败=$consecutiveLoginFailures/$threshold"
            } else {
                "Bilibili 请求未登录：operation=$operation，自动暂停已关闭"
            }
        }
    }

    private fun recordNonLoginBiliFailure(
        operation: String,
        error: BiliException,
        category: String,
    ) {
        consecutiveLoginFailures = 0
        requestFailureLogger.warn(error) {
            "Bilibili 请求失败：operation=$operation，类型=$category，原因=${error.message ?: "未知"}"
        }
    }

    private fun recordUnknownFailure(operation: String, error: Throwable) {
        consecutiveLoginFailures = 0
        requestFailureLogger.warn(error) {
            "Bilibili 请求出现未知异常：operation=$operation，类型=${error::class.qualifiedName ?: error::class.simpleName ?: "未知"}，原因=${error.message ?: "未知"}"
        }
    }
}
