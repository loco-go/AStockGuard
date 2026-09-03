/*
 * 文件职责：配置纯 Android 设计系统模块及其编译参数。模块只提供资源和视觉规范，不依赖业务、网络或数据库实现。
 * 维护约束：依赖和 Android 配置变化需要通过完整 test、lint 与 assembleDebug 验证。
 */
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.locogo.astockguard.designsystem"
    compileSdk = 35

    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("com.google.android.material:material:1.12.0")
}
