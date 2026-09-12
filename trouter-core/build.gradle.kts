plugins {
    alias(libs.plugins.android.library)
    `maven-publish`
}

// 统一对外坐标（命名空间）：group 与包名根保持一致，最终坐标形如 com.trouter:<模块名>:<版本号>
group = "com.trouter"
version = "0.1.0"

android {
    namespace = "com.trouter.core"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        minSdk = 24
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        aidl = true   // V4.0：IRouterService（src/main/aidl）
    }

    // 发布要走 release 这一份产物，并且带上源码包
    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

dependencies {
    // 默认日志通道：TLogger（库只调 TLogger.logger(...)，不 install —— 装日志是宿主的决定）
    implementation(libs.tlogger.core)

    implementation(libs.androidx.fragment)
    implementation(libs.androidx.core.ktx)

    // 测试底座 BaseTRouterTest/TestConfig 位于 src/debug，依赖只进 debug 变体
    debugImplementation(libs.junit)
    debugImplementation(libs.androidx.test.core)
}

// 发布：安卓库发 AAR（带源码包），坐标 com.trouter:trouter-core:<版本号>
// 用 afterEvaluate 是因为 release 这个产物要等配置阶段结束才存在
afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
            }
        }
    }
}
