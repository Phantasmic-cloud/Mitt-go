plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

/** Miuix 0.9.4 internally expects Compose 1.12.0. */
val composeVersion = "1.12.0"

android {
    namespace = "com.phantasmic.mitt.go"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.phantasmic.mitt.go"
        minSdk = 33
        targetSdk = 36
        versionCode = 77
        versionName = "2.0.2"

        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            // 关键：debug 包以前带 android:debuggable="true"，ART 会关掉 AOT/inlining，
            // Compose 的重组是纯 Kotlin 执行，受影响最大。关掉 debuggable 后
            // 仍然是 debug 变体（不跑 R8、构建快），但按正常方式做 AOT 编译。
            isDebuggable = false
        }
        release {
            // 还没有专属发布密钥：先用 debug keystore 签名，保证能直接覆盖安装。
            // 等正式发布前把这里换成自己的 release keystore（放到 keystore.properties，别提交）。
            signingConfig = signingConfigs.getByName("debug")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }

    packaging {
        // The Go .so has to be page aligned to be mapped straight out of the
        // APK; AGP's zip writer does not guarantee that here, so let the
        // installer extract native libraries instead (extractNativeLibs=true).
        jniLibs {
            useLegacyPackaging = true
        }
        resources {
            excludes += setOf(
                "META-INF/AL2.0",
                "META-INF/LGPL2.1",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/*.kotlin_module",
            )
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

configurations.all {
    exclude(group = "org.jetbrains.compose")
    exclude(group = "org.jetbrains.compose.foundation")
    exclude(group = "org.jetbrains.compose.runtime")
    exclude(group = "org.jetbrains.compose.ui")
    exclude(group = "org.jetbrains.compose.material3")
    exclude(group = "org.jetbrains.compose.material")
    exclude(group = "org.jetbrains.compose.animation")
    exclude(group = "org.jetbrains.compose.components")
    exclude(group = "org.jetbrains.compose.collection-internal")
    resolutionStrategy {
        force(
            "androidx.compose.ui:ui:$composeVersion",
            "androidx.compose.ui:ui-graphics:$composeVersion",
            "androidx.compose.ui:ui-text:$composeVersion",
            "androidx.compose.ui:ui-unit:$composeVersion",
            "androidx.compose.ui:ui-geometry:$composeVersion",
            "androidx.compose.ui:ui-util:$composeVersion",
            "androidx.compose.foundation:foundation:$composeVersion",
            "androidx.compose.foundation:foundation-layout:$composeVersion",
            "androidx.compose.runtime:runtime:$composeVersion",
            "androidx.compose.runtime:runtime-saveable:$composeVersion",
            "androidx.compose.animation:animation:$composeVersion",
            "androidx.compose.animation:animation-core:$composeVersion",
            "androidx.compose.material:material-ripple:$composeVersion",
        )
    }
}

dependencies {
    implementation("androidx.compose.ui:ui:$composeVersion")
    implementation("androidx.compose.ui:ui-graphics:$composeVersion")
    implementation("androidx.compose.foundation:foundation:$composeVersion")
    implementation("androidx.compose.material3:material3:1.5.0-alpha22")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")

    implementation("top.yukonga.miuix.kmp:miuix-ui-android:0.9.4")
    implementation("top.yukonga.miuix.kmp:miuix-preference-android:0.9.4")
    implementation("top.yukonga.miuix.kmp:miuix-blur-android:0.9.4")
    implementation("top.yukonga.miuix.kmp:miuix-shader-android:0.9.4")
    implementation("top.yukonga.miuix.kmp:miuix-squircle-android:0.9.4")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
