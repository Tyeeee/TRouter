plugins {
    alias(libs.plugins.kotlin.jvm)
    `maven-publish`
}

// 统一对外坐标（命名空间）：group 与包名根保持一致，最终坐标形如 com.trouter:<模块名>:<版本号>
group = "com.trouter"
version = "1.0.1"

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

dependencies {
    implementation(project(":trouter-annotation"))
    implementation(libs.ksp.api)
}

// 发布：JVM 模块发 jar（带源码包），坐标 com.trouter:<模块名>:<版本号>
publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
        }
    }
}
