plugins {
    alias(libs.plugins.kotlin.jvm)
    `maven-publish`
}

// 统一对外坐标（命名空间）：group 与包名根保持一致，最终坐标形如 com.trouter:<模块名>:1.0.0
group = "com.trouter"
version = "1.0.0"

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11

    // 发布时带上源码包
    withSourcesJar()
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
    }
}

// 发布：JVM 模块发 jar（带源码包），坐标 com.trouter:<模块名>:1.0.0
publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
        }
    }
}
