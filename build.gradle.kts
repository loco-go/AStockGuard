/*
 * 文件职责：声明整个工程共享的 Android Gradle Plugin 与 Kotlin 插件版本，但不在根工程直接应用。
 * 维护约束：插件升级需核对 JDK、Gradle 和 compileSdk 兼容性，并通过全量构建验证后再提交。
 */
plugins {
    id("com.android.application") version "8.7.3" apply false
    id("com.android.library") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "1.9.24" apply false
    id("org.jetbrains.kotlin.kapt") version "1.9.24" apply false
}
