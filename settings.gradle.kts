pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
    // 批次 A3：跨模块路由冲突校验插件（独立构建，随仓库分发，消费方 plugins { id("com.trouter.route-conflict") } 一行接入）
    includeBuild("trouter-gradle-plugin")
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "TRouterDemo"
include(":app")
include(":feature-demo")
include(":feature-about")
include(":trouter-annotation")
include(":trouter-processor")
include(":trouter-core")
include(":trouter-lint")
 