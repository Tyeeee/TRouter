package com.trouter.core.internal

import com.trouter.core.api.RouteMeta
import com.trouter.core.api.RouteTargetKind
import com.trouter.core.api.TRouterResult

/**
 * 跨进程结果编解码（跨进程版本）。
 *
 * AIDL 侧只传一个结构化字符串（字段以控制符 [SEP] 分隔，业务字段不会含该字符），
 * 避免自定义 Parcelable 泄漏到 core API；host 侧解码后重建统一密封 [TRouterResult]。
 * RouteMeta 是纯字符串数据，跨进程天然安全（无 Class 引用）。
 */
object RemoteReplyCodec {

    private const val SEP = "\u001F"

    /** 服务调用错误串前缀（跨进程接口调用，host/服务端共用同一协议前缀）。 */
    const val SERVICE_ERROR_PREFIX = "-ERR "
    private const val KIND_SUCCESS = "SUCCESS"
    private const val KIND_NOT_FOUND = "NOT_FOUND"
    private const val KIND_BLOCKED = "BLOCKED"

    data class Reply(
        val kind: String,
        val path: String,
        val group: String,
        val className: String,
        val kindName: String,
        val reason: String,
        val remoteTraceId: String,
        val costMs: Long,
        /** 传输层参数回显（host 校验「bundle 确实跨进程送达」用，见 RemoteRouterService）。 */
        val paramEcho: List<String>,
    )

    /**
     * 编码远端 TRouterResult（remote 进程侧调用）。
     * [paramEcho] 为调用方 bundle 的传输层回显（String/数值等基础类型的有序摘要，非完整 bundle）。
     * 注意：远端 NotInitialized 会被映射为 Blocked("remote 未初始化")，
     * 保证 host 侧不会收到语义错位的 NotInitialized。
     */
    fun encode(result: TRouterResult, remoteTraceId: String, costMs: Long, paramEcho: List<String> = emptyList()): String {
        val kind: String
        val path: String
        val group: String
        val className: String
        val kindName: String
        val reason: String
        when (result) {
            is TRouterResult.Success -> {
                kind = KIND_SUCCESS
                path = result.meta.path
                group = result.meta.group
                className = result.meta.targetClassName
                kindName = result.meta.kind.name
                reason = ""
            }
            is TRouterResult.NotFound -> {
                kind = KIND_NOT_FOUND
                path = result.path
                group = ""
                className = ""
                kindName = ""
                reason = ""
            }
            is TRouterResult.Blocked -> {
                kind = KIND_BLOCKED
                path = result.path
                group = ""
                className = ""
                kindName = ""
                reason = result.reason
            }
            TRouterResult.NotInitialized -> {
                kind = KIND_BLOCKED
                path = ""
                group = ""
                className = ""
                kindName = ""
                reason = "remote 未初始化（远端 TRouter 未 init）"
            }
        }
        return listOf(
            kind, path, group, className, kindName, reason, remoteTraceId, costMs.toString(),
            paramEcho.joinToString("\u0002"),
        ).joinToString(SEP)
    }

    fun parse(raw: String): Reply? {
        val parts = raw.split(SEP)
        if (parts.size < 9) return null
        val echoRaw = parts.getOrElse(8) { "" }
        return Reply(
            kind = parts[0],
            path = parts[1],
            group = parts[2],
            className = parts[3],
            kindName = parts[4],
            reason = parts[5],
            remoteTraceId = parts[6],
            costMs = parts[7].toLongOrNull() ?: -1L,
            paramEcho = if (echoRaw.isEmpty()) emptyList() else echoRaw.split("\u0002"),
        )
    }

    /** host 侧：把远端回包重建为统一密封结果。 */
    fun toLocalResult(reply: Reply): TRouterResult = when (reply.kind) {
        KIND_SUCCESS -> TRouterResult.Success(
            RouteMeta(
                path = reply.path,
                group = reply.group,
                targetClassName = reply.className,
                kind = RouteTargetKind.valueOf(reply.kindName),
            ),
        )
        KIND_NOT_FOUND -> TRouterResult.NotFound(reply.path)
        else -> TRouterResult.Blocked(reply.path, reply.reason)
    }

    fun describe(reply: Reply): String = when (reply.kind) {
        KIND_SUCCESS -> "Success(meta=${reply.path})"
        KIND_NOT_FOUND -> "NotFound(path=${reply.path})"
        else -> "Blocked(path=${reply.path}, reason=${reply.reason})"
    }
}
