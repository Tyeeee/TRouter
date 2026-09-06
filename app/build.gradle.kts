plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
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
    buildFeatures {
        compose = true
    }
}

// KSP：处理器据此推导生成包名（须与 android.namespace 保持一致）
ksp {
    arg("trouter.modulePackage", "com.demo.trouter")
}

dependencies {
    implementation(project(":trouter-annotation"))
    implementation(project(":trouter-core"))
    implementation(project(":feature-demo"))
    implementation(project(":feature-about"))
    ksp(project(":trouter-processor"))

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.rules)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}

// ---------------------------------------------------------------------------
// L1：跨模块路由 path 冲突 —— 构建期校验（debug 变体）。
// 逻辑在 :trouter-processor 的 CrossModuleConflictScanner（JVM main，避免 DSL 闭包生成 bug）；
// 本脚本只接线：kspDebugKotlin 完成后以 JavaExec 扫描各模块生成目录，冲突则以非零退出使构建失败。
// ---------------------------------------------------------------------------
val verifyCrossModuleRouteConflicts = tasks.register<JavaExec>("verifyCrossModuleRouteConflicts") {
    group = "verification"
    description = "跨模块路由 path 冲突检测（构建期，L1）"
    dependsOn(":trouter-processor:jar")
    classpath = project(":trouter-processor").sourceSets["main"].runtimeClasspath
    mainClass.set("com.trouter.processor.CrossModuleConflictScanner")
    val moduleDirs = listOf(
        "app" to File(rootDir, "app/build/generated/ksp/debug/kotlin").absolutePath,
        "feature-demo" to File(rootDir, "feature-demo/build/generated/ksp/debug/kotlin").absolutePath,
        "feature-about" to File(rootDir, "feature-about/build/generated/ksp/debug/kotlin").absolutePath,
    )
    for ((moduleName, dirPath) in moduleDirs) {
        args(moduleName, dirPath)
    }
}

// kspDebugKotlin 跑完（生成完毕）后立即执行校验；JavaExec 无环（finalizer 语义）。
tasks.matching { it.name == "kspDebugKotlin" }.configureEach {
    finalizedBy(verifyCrossModuleRouteConflicts)
}
