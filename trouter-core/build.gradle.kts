plugins {
    alias(libs.plugins.android.library)
}

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
}

dependencies {
    implementation(libs.androidx.fragment)
    implementation(libs.androidx.core.ktx)
    implementation(libs.timber)

    // 测试底座 BaseTRouterTest/TestConfig 位于 src/debug，依赖只进 debug 变体
    debugImplementation(libs.junit)
    debugImplementation(libs.androidx.test.core)
}
