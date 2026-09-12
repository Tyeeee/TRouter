plugins {
    id("com.android.lint")
    alias(libs.plugins.kotlin.jvm)
}

// 统一对外坐标（命名空间）：group 与包名根保持一致，最终坐标形如 com.trouter:<模块名>:1.0.0
group = "com.trouter"
version = "1.0.0"

// 说明：本模块只在构建期使用（Lint 检查），不打包进 APK。
// Lint 32.x（AGP 9.3.2 线）自身要求 JVM 17，因此这里的目标版本是 17，而不是其他模块的 11。
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    compileOnly(libs.lint.api)
    // 测试期需要 lint-api（Detector/Issue 等）与官方测试底座
    testImplementation(libs.lint.api)
    testImplementation(libs.lint.tests)
    testImplementation(libs.junit)
}
