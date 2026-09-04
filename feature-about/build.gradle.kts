plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.demo.trouter.feature.about"
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

// KSP：独立跑处理器，按本模块 namespace 推导生成包名
ksp {
    arg("trouter.modulePackage", "com.demo.trouter.feature.about")
}

dependencies {
    implementation(project(":trouter-annotation"))
    implementation(project(":trouter-core"))
    implementation(libs.androidx.activity)
    ksp(project(":trouter-processor"))
}
