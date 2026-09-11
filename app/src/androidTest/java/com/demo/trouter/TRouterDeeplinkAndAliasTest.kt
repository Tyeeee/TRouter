package com.demo.trouter

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.trouter.core.api.GroupLoaderRegistry
import com.trouter.core.api.RouterContract
import com.trouter.core.api.TRouter
import com.trouter.core.api.TRouterConfig
import com.trouter.core.api.TRouterResult
import com.trouter.core.api.UriRouter
import com.trouter.core.testing.BaseTRouterTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 批 2b：从外部链接进入 URI/Scheme 深链 + 路径别名 路由别名（精确/正则/多 path ↔ 一页）。
 */
@RunWith(AndroidJUnit4::class)
class TRouterDeeplinkAndAliasTest : BaseTRouterTest() {

    override fun provideRegistry(): GroupLoaderRegistry = DemoRouteRegistry

    private fun reInitSchemes(schemes: Set<String>) {
        reInit(
            TRouterConfig(
                isDebug = true,
                logSink = logs,
                onLost = { lostPaths += it },
                deeplinkSchemes = schemes,
            ),
        )
    }

    /** 从外部链接进入-1：白名单 scheme 的 URI → 以 path 段命中路由并成功。 */
    @Test
    fun uriWithAllowedSchemeNavigates() {
        reInitSchemes(setOf("trouter"))
        val result = TRouter.navigateUri(Uri.parse("trouter://app/${RouterContract.PATH_SECOND.removePrefix("/")}"))
        assertTrue("应成功: $result", result is TRouterResult.Success)
        assertEquals(RouterContract.PATH_SECOND, (result as TRouterResult.Success).meta.path)
        assertTrue("onLost 不应触发", lostPaths.isEmpty())
    }

    /** 从外部链接进入-2：未启用 scheme → Blocked（明确拒绝，不静默）。 */
    @Test
    fun uriWithDisallowedSchemeIsBlocked() {
        reInitSchemes(emptySet())
        val result = TRouter.navigateUri(Uri.parse("evil://app/second"))
        assertTrue("应 Blocked: $result", result is TRouterResult.Blocked)
        assertTrue("reason 应含 scheme 未启用: ${(result as TRouterResult.Blocked).reason}",
            result.reason.contains("scheme 未启用"))
        assertTrue("onLost 不应触发", lostPaths.isEmpty())
    }

    /** 从外部链接进入-3：URI 缺路径段 → Blocked（清晰原因）。 */
    @Test
    fun uriWithoutPathIsBlocked() {
        reInitSchemes(setOf("trouter"))
        val result = TRouter.navigateUri(Uri.parse("trouter://app"))
        assertTrue("应 Blocked: $result", result is TRouterResult.Blocked)
        assertTrue("reason 应含缺少路径段: ${(result as TRouterResult.Blocked).reason}",
            result.reason.contains("缺少路径段"))
    }

    /** 从外部链接进入-4：query 参数提取为导航参数（并入 bundle）。 */
    @Test
    fun uriQueryExtractedToParams() {
        val params = UriRouter.paramsOf(Uri.parse("trouter://app/second?a=1&b=你好&c="))
        assertEquals("1", params.getString("a"))
        assertEquals("你好", params.getString("b"))
        assertEquals("", params.getString("c"))
    }

    /** 路径别名-1：精确别名 → 转向真实路由；注销后恢复 NotFound。 */
    @Test
    fun exactAliasNavigatesThenUnregisterRestores() {
        assertTrue(TRouter.registerRouteAlias("/short-second", RouterContract.PATH_SECOND))
        val result = TRouter.navigate("/short-second")
        assertTrue("别名应打开目标页: $result", result is TRouterResult.Success)
        assertEquals(RouterContract.PATH_SECOND, (result as TRouterResult.Success).meta.path)
        assertTrue("日志应记录别名解析",
            logs.lines.joinToString("\n").contains("[route][alias] from=/short-second to=${RouterContract.PATH_SECOND}"))

        assertTrue(TRouter.unregisterRouteAlias("/short-second"))
        assertEquals(TRouterResult.NotFound("/short-second"), TRouter.navigate("/short-second"))
        assertTrue(lostPaths.contains("/short-second"))
    }

    /** 路径别名-2：正则别名（regex: 前缀）命中；不匹配则 NotFound。 */
    @Test
    fun regexAliasMatchesOnlyPattern() {
        assertTrue(TRouter.registerRouteAlias("regex:/r/\\d+", RouterContract.PATH_ABOUT))
        val hit = TRouter.navigate("/r/42")
        assertTrue("正则命中应打开 About: $hit", hit is TRouterResult.Success)
        assertEquals(RouterContract.PATH_ABOUT, (hit as TRouterResult.Success).meta.path)

        val miss = TRouter.navigate("/r/abc")
        assertEquals(TRouterResult.NotFound("/r/abc"), miss)
    }

    /** 路径别名-3：别名与静态路由同名冲突 → 拒绝注册（静态优先语义由“仅在未命中时查别名”保证）。 */
    @Test
    fun duplicateAliasRejectedAndStaticWins() {
        assertFalse("与静态 path 同名的别名应拒绝", TRouter.registerRouteAlias(RouterContract.PATH_SECOND, RouterContract.PATH_ABOUT))
        assertTrue(TRouter.registerRouteAlias("/dup", RouterContract.PATH_SECOND))
        assertFalse("重复别名应拒绝", TRouter.registerRouteAlias("/dup", RouterContract.PATH_ABOUT))
        assertEquals(1, TRouter.registeredRouteAliases().size)
        // 静态路径导航不受别名影响
        val result = TRouter.navigate(RouterContract.PATH_SECOND)
        assertEquals(RouterContract.PATH_SECOND, (result as TRouterResult.Success).meta.path)
    }

    /** 路径别名-4：别名互相指向形成环 → 跳数上限截断为 Blocked（不崩溃）。 */
    @Test
    fun aliasLoopIsCapped() {
        assertTrue(TRouter.registerRouteAlias("/a", "/b"))
        assertTrue(TRouter.registerRouteAlias("/b", "/a"))
        val result = TRouter.navigate("/a")
        assertTrue("应 Blocked(alias loop): $result", result is TRouterResult.Blocked)
        assertTrue("reason 应含 alias loop: ${(result as TRouterResult.Blocked).reason}",
            result.reason.contains("alias loop"))
        assertTrue("loop 不应触发 onLost", lostPaths.isEmpty())
    }
}
