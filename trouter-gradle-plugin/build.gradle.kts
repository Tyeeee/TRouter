plugins {
    `java-gradle-plugin`
    `maven-publish`
    kotlin("jvm") version "2.2.10"
}

// 统一对外坐标（命名空间）：group 与包名根保持一致，最终坐标形如 com.trouter:<模块名>:1.0.0
group = "com.trouter"
version = "1.0.0"

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
    }
}

gradlePlugin {
    plugins {
        create("routeConflict") {
            id = "com.trouter.route-conflict"
            implementationClass = "com.trouter.gradle.RouteConflictPlugin"
            displayName = "TRouter 跨模块路由冲突校验"
            description = "构建期扫描各模块 KSP 生成的路由清单，跨模块重复 path 直接使构建失败"
        }
    }
}

// 发布：com.trouter:trouter-gradle-plugin:1.0.0
// java-gradle-plugin 会额外生成插件标记产物（com.trouter.route-conflict:com.trouter.route-conflict.gradle.plugin），
// 消费方才能用 plugins { id("com.trouter.route-conflict") version "1.0.0" } 从 Maven 解析。
publishing {
    publications.withType<MavenPublication>().configureEach {
        pom {
            name.set("TRouter Route Conflict Plugin")
            description.set("TRouter 跨模块路由冲突校验插件：构建期扫描各模块 KSP 生成的路由清单，重复 path 直接失败")
        }
    }
}
