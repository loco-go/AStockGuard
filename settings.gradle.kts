/*
 * 文件职责：配置插件与依赖仓库、工程名称和参与构建的模块。
 * 安全约束：仓库地址必须明确且使用可信 HTTPS 来源；新增模块时保持 app 到基础模块的单向依赖。
 */
pluginManagement {
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        maven { url = uri("https://jitpack.io") }
        mavenCentral()
    }
}

rootProject.name = "AStockGuardDemo"
include(":app")
include(":core:designsystem")
