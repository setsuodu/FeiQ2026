pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
        google()
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
    }
}

// 不再使用 foojay-resolver（会触发 IBM_SEMERU 等与旧 Gradle 不兼容的问题）
rootProject.name = "MiniFeiQ"
