// `java` 在这个脚本里被 Gradle 的 java 插件扩展占用，用别名 import 绕开，
// 否则 `java.util.Properties` 会被解析成 `JavaPluginExtension.util`。
import java.util.Properties as JavaProperties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
}

// 签名：**仓库内固定一份**，本地与 GitHub Actions 用同一把 key。
//
// 为什么必须固定：CI 跑在全新 runner 上，Android debug keystore 是 runner 本地现生成的
// ⇒ 每次构建签名都不同 ⇒ 覆盖安装必报「签名不一致」，只能先卸载。
//
// 优先级：仓库根的 keystore.properties（已 gitignore，私钥不想进库时用）> signing/signing.properties。
// 换私钥：替换 signing/hyperextreme.p12 并同步 signing.properties；换完要卸载重装一次。
val keystoreProps = listOf(
    rootProject.file("keystore.properties"),
    rootProject.file("signing/signing.properties"),
).firstOrNull { it.exists() }?.let { f ->
    JavaProperties().apply { f.inputStream().use(::load) }
}

android {
    namespace = "com.frederic22.hyperextreme"
    compileSdk = 37
    buildToolsVersion = "37.0.0"
    ndkVersion = "28.2.13676358"

    // 桌面桥（home_bridge）：native 子工程（libxposed API 102 native 入口），仅 arm64-v8a；
    // libc++ 静态链接，避免运行期依赖 libc++_shared。
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    defaultConfig {
        applicationId = "com.frederic22.hyperextreme.liquid"
        minSdk = 33
        targetSdk = 37
        versionCode = 5
        versionName = "0.5 Beta2"

        ndk {
            abiFilters += "arm64-v8a"
        }
        externalNativeBuild {
            cmake {
                arguments.add("-DANDROID_STL=c++_static")
            }
        }
    }

    signingConfigs {
        // debug / release **共用**这一份签名：同一台机器、CI、不同次构建签出来完全一致。
        // 默认取仓库里的 signing/hyperextreme.p12（PKCS12，口令在 signing.properties）；
        // 没有配置文件时退回 debug keystore —— 只影响 clone 后第一次构建能否直接装。
        if (keystoreProps != null) {
            create("stable") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
                storeType = keystoreProps.getProperty("storeType") ?: "PKCS12"
            }
        }
    }

    buildTypes {
        debug {
            // 与 release 同签名（见 signingConfigs 注释）：否则 CI 产物覆盖安装会签名冲突。
            signingConfig = if (keystoreProps != null) {
                signingConfigs.getByName("stable")
            } else {
                signingConfigs.getByName("debug")
            }
        }
        release {
            // R8 全量裁剪 + 资源收缩。
            //
            // 【为什么以前是 false】原判断是"Xposed 入口按类名找，混淆会弄丢" —— 这是误判：
            // 按类名加载的入口只有两个（HookEntry / XposedProvider），都在
            // app/proguard-rules.pro 里显式 keep 住了。R8 剪掉的是**没被引用到的库代码**：
            // 本次打包前 dex 里有 17,803 个类，工程自己的不到 300 个，其余全是
            // androidx.compose.*（text / lazy / gestures / animation…）和 miuix 的 316 个
            // basic 组件 —— 一行都没执行过。
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = if (keystoreProps != null) {
                signingConfigs.getByName("stable")
            } else {
                logger.warn("未找到 signing/signing.properties —— release 退回 debug 签名（每次 CI 都会变）")
                signingConfigs.getByName("debug")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    packaging {
        // dex 压缩进 APK（minSdk >= 28 时 AGP 默认是不压缩、安装时直接 mmap，
        // 体积会大 20MB 左右）。这里要的是包小，所以打开 legacy 打包。
        dex {
            useLegacyPackaging = true
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // libxposed API 102（由 io.github.libxposed:api:102.0.0 的 AAR 解出的 classes.jar）。
    // 只参与编译，运行时由框架提供，绝不打包进 APK。
    compileOnly(files("libs/libxposed-api-102.jar"))

    implementation(libs.androidx.activity.compose)
    implementation(libs.jetbrains.compose.foundation)
    implementation(libs.miuix.core)
    implementation(libs.miuix.ui)
    implementation(libs.miuix.icons)
    // 底栏玻璃：改用 miuix 官方的 miuix-blur（AGSL 手写可分离高斯 + 自适应降采样），
    // 不再用 kyant backdrop 的 RenderEffect.createBlurEffect 那条链。
    implementation(libs.miuix.blur)
    // 悬浮底栏（从 HyperChanger 移植：Apache-2.0）用到的液态玻璃与形状库
    implementation(libs.kyant.backdrop)
    implementation(libs.kyant.shapes)
    // 空间壁纸的深度推理（Depth Anything V2 ViT-S int8）。
    // **完整版**：mobile（minimal build）缺 ConvInteger 等算子，实测跑不动这个模型，
    // 2026-10-22 用户决定退回完整版（so 17.8MB），模型直接用 `.onnx`。
    implementation(libs.onnxruntime.android)
}
