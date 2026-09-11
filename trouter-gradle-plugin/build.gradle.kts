plugins {
    `java-gradle-plugin`
    kotlin("jvm") version "2.2.10"
}

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
