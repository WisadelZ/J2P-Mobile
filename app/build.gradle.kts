import com.android.build.api.variant.FilterConfiguration
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.chaquo.python")
}

android {
    namespace = "com.j2pmobile.android"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.j2pmobile.android"
        minSdk = 24          // Chaquopy 17.0 requires minSdk >= 24
        targetSdk = 36
        versionCode = 2040401
        versionName = "v2.4.4"

        // Chaquopy 强制要求 ndk.abiFilters 写在 defaultConfig（写进 buildType 会直接报错），
        // 这里声明「支持的 ABI 全集」；最终产出哪几个包由下面的 splits 决定。
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    // 【一次构建出 3 个包】按 ABI 拆分，并额外产出双架构 universal 包：
    //   app-<buildType>-arm64-v8a.apk  —— 仅 arm64-v8a（真机，体积最小）
    //   app-<buildType>-x86_64.apk     —— 仅 x86_64（模拟器，体积最小）
    //   app-<buildType>.apk            —— 双架构 universal（无后缀，体积略大，任何设备都能装）
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "x86_64")
            isUniversalApk = true
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // 【签名固定】debug 包必须始终用同一把密钥，否则「覆盖安装」会因签名不一致而失败
    // （曾发生：构建环境未设 ANDROID_USER_HOME 时，AGP 会在 ~/.android 新建一把
    //   debug.keystore，导致 APK 签名与之前的包不同，只能卸载原包再装）。
    // 密钥收在工程的 keystore/ 下（已 gitignore，不随仓库分发）：
    // - 本地有它 → 显式使用，签名稳定；
    // - 全新克隆没有它 → 不配置签名，回退到 AGP 自动生成的调试密钥，仍可构建。
    val fixedKeystore = rootProject.file("keystore/debug.keystore")
    if (fixedKeystore.exists()) {
        signingConfigs {
            create("debugFixed") {
                storeFile = fixedKeystore
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }

    buildTypes {
        debug {
            if (fixedKeystore.exists()) {
                signingConfig = signingConfigs.getByName("debugFixed")
            }
        }
        release {
            // 出发布包时用： gradlew assembleRelease（会产出 arm64-v8a / x86_64 / 双架构 共 3 个包）
            isMinifyEnabled = false
            // 临时沿用工程内密钥，保证产物可安装验证；正式发布前须换成独立的 release 密钥。
            if (fixedKeystore.exists()) {
                signingConfig = signingConfigs.getByName("debugFixed")
            }
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

chaquopy {
    defaultConfig {
        // 与目标 Python 主次号一致 (Chaquopy 17.0 要求)
        version = "3.11"
        // 宿主 Python（仅构建期使用，打包进 APK 的是 Chaquopy 自己的运行时）。
        // 路径**不写死在源码里**（源码需保持不含本机路径），按以下顺序取：
        //   1. -Pchaquopy.buildPython=<路径>  或环境变量 CHAQUOPY_BUILD_PYTHON
        //   2. local.properties 里的 chaquopy.buildPython（该文件已 gitignore，属本机配置）
        //   3. 都没有 → 不设置，交给 Chaquopy 自行探测（便于仓库被他人克隆后构建）
        val localProps = Properties().apply {
            rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
        }
        val buildPythonPath = (findProperty("chaquopy.buildPython") as String?)?.takeIf { it.isNotBlank() }
            ?: System.getenv("CHAQUOPY_BUILD_PYTHON")?.takeIf { it.isNotBlank() }
            ?: localProps.getProperty("chaquopy.buildPython")?.takeIf { it.isNotBlank() }
        if (buildPythonPath != null) buildPython(buildPythonPath)
        pip {
            // 依赖策略：全局关闭自动依赖解析（--no-deps），然后逐个显式声明。
            //
            // 原因：jmcomic -> curl-cffi、img2pdf -> pikepdf 这两个原生依赖在 Chaquopy 索引里
            // 没有安卓轮子（curl-cffi: "from versions: none"；pikepdf 索引中不存在），
            // 若开启自动解析，pip 会因找不到它们而直接失败。
            //
            // 【重要】Pillow 的原生库不在它自己的 wheel 里，而是由两个独立伴随包提供
            // （已核对其 wheel 元数据：Requires-Dist: chaquopy-freetype, chaquopy-libjpeg）。
            // 因此必须显式安装它们，否则 PIL/_imaging.so 会报
            //   "dlopen failed: library libjpeg_chaquopy.so not found"
            options("--no-deps")

            // 业务核心
            // jmcomic 必须 >= 2.7.7：签到的 get_daily / daily_checkin 是 2.7.7 才加的
            // （锁 2.7.4 时签到会报 AttributeError）。桌面端跑的正是 2.7.7，
            // 依赖集未变（commonx>=0.6.38 / curl-cffi / pillow / pycryptodome / pyyaml）。
            install("jmcomic==2.7.7")
            install("commonx==0.6.40")     // jmcomic 依赖：HTTP 后端与工具
            install("pillow")              // 图片处理
            install("chaquopy-libjpeg")    // ← Pillow 的原生伴随库（必需）
            install("chaquopy-freetype")   // ← Pillow 的原生伴随库（必需）
            install("pycryptodome")        // 账号加密
            install("pyyaml")              // 配置序列化

            // 网络与 PDF
            install("requests")            // HTTP 后端 requests
            install("charset-normalizer")  // requests 依赖
            install("idna")                // requests 依赖
            install("urllib3")             // requests 依赖
            install("certifi")             // requests 依赖
            install("img2pdf")             // 图片合成 PDF
            install("pypdf")               // 替代 pikepdf：元数据 + 取页图
        }
    }
}

// 【产物命名】把 splits 产出的 APK 改成「架构后缀」形式，便于发布时一眼区分：
//   单架构（带 ABI 过滤）→ app-<buildType>-<abi>.apk
//   双架构 universal（无 ABI 过滤）→ app-<buildType>.apk（无后缀）
androidComponents {
    onVariants { variant ->
        variant.outputs.forEach { output ->
            val abi = output.filters.firstOrNull {
                it.filterType == FilterConfiguration.FilterType.ABI
            }?.identifier
            output.outputFileName.set(
                if (abi == null) "app-${variant.name}.apk"
                else "app-${variant.name}-$abi.apk"
            )
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    debugImplementation("androidx.compose.ui:ui-tooling")
}