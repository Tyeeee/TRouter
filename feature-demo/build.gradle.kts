plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.demo.trouter.feature.demo"
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
}

// KSP：独立跑处理器，按本模块 namespace 推导生成包名（各自生成注册表，host 聚合）
ksp {
    arg("trouter.modulePackage", "com.demo.trouter.feature.demo")
}

dependencies {
    implementation(project(":trouter-annotation"))
    implementation(project(":trouter-core"))
    implementation(libs.androidx.fragment)
    implementation(libs.androidx.activity)
    ksp(project(":trouter-processor"))
}
