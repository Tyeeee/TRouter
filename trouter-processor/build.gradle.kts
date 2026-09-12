plugins {
    alias(libs.plugins.kotlin.jvm)
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

dependencies {
    implementation(project(":trouter-annotation"))
    implementation(libs.ksp.api)
}
