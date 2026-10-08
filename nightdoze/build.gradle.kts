// 夜眠息屏：单 module，不引入多 module / DI 框架（PRD 7.1）
// 版本组合：AGP 8.5.2 + Kotlin 2.0.0 + Gradle 8.7。
// 这套组合在同一台开发机上跑通过完整构建，且全部可从 google() / mavenCentral() 取得，
// 没有依赖任何私有仓库。
plugins {
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "2.0.0" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.0" apply false
}
