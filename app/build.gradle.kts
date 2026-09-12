plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.ksp)
    // 批次 A3：跨模块路由 path 冲突校验（构建期闸门）。插件来自 com.trouter.gradle（settings 里 includeBuild）
    id("com.trouter.route-conflict")
}

android {
    namespace = "com.demo.trouter"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.demo.trouter"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

// KSP：处理器据此推导生成包名（须与 android.namespace 保持一致）
ksp {
    arg("trouter.modulePackage", "com.demo.trouter")
    // 路径字面量处置级别：默认 warning（向后兼容）；CI/新工程可 -PtrouterPathSeverity=error 直接卡编译
    arg("trouter.pathSeverity", (findProperty("trouterPathSeverity") as String?) ?: "warning")
}

dependencies {
    implementation(project(":trouter-annotation"))
    implementation(project(":trouter-core"))
    implementation(project(":feature-demo"))
    implementation(project(":feature-about"))
    ksp(project(":trouter-processor"))
    // 回测台要检查 FRAGMENT 类路由是否真的把片段装进了容器页，因此宿主要能拿到 FragmentManager
    // 日志：TLogger（App 侧唯一的安装处在这里；TRouter 的日志也就跟全 App 一条线）
    implementation(libs.tlogger.core)
    implementation(libs.tlogger.android)

    implementation(libs.androidx.fragment)
    // 批次 A2：把 TRouter 的 Lint 规则挂到本模块的 Lint 检查里（调用点硬编码 → lint error）
    lintChecks(project(":trouter-lint"))

    // 界面全部用平台 View 写（工程里没有一处 Compose），因此不引 compose 依赖；
    // activity 是 ComponentActivity 的来源，页面基类离不开
    implementation(libs.androidx.activity)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.rules)
}

// ---------------------------------------------------------------------------
// L1 跨模块路由 path 冲突校验：已由插件 com.trouter.route-conflict 提供
// （见 com.trouter.gradle；自动发现各模块 KSP 生成目录，并挂到 check / assemble*）。
// 命令：./gradlew :app:verifyTRouterRoutes   ；配置：trouterConflict { variants / autoWire / modules }
// ---------------------------------------------------------------------------

