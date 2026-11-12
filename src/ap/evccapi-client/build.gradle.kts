plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.kooo.evcc.evccapi.client"
    compileSdk = 36

    defaultConfig {
        minSdk = 28
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    /*
     * 关掉 lint（2026-11-12）
     * 离线环境缺 com.android.tools.lint:lint-gradle:31.5.2，
     * extractDebugAnnotations 会因此失败。SDK 是官方发布代码，不需要本地 lint。
     */
    lint { abortOnError = false; checkReleaseBuilds = false }

    /*
     * Kotlin 的 jvmTarget 必须和 compileOptions 的 sourceCompatibility 一致。
     * 不指定时 Kotlin 用当前 JDK（21）作默认，而 Java 是 11，
     * Gradle 会报 "Inconsistent JVM-target compatibility"(2026-11-12)。
     */
    kotlinOptions {
        jvmTarget = "11"
    }
}

dependencies {
    api(project(":evccapi-contract"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    testImplementation("junit:junit:4.13.2")
}
