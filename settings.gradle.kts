pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "dsh-android"
include(":app")
// 壳源码放在 android/app/（与 runtime/、scripts/、docs/ 平级）；
// Gradle 根工程仍在仓库根，wrapper 与 local.properties 不用挪。
project(":app").projectDir = file("android/app")
