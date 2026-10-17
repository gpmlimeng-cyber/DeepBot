import java.security.MessageDigest

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// 构建目标 ABI 可由 -Pabi=x86_64 覆盖（CI 双架构矩阵 / 模拟器测试用），默认真机 arm64
val targetAbi = (project.findProperty("abi") as String?) ?: "arm64-v8a"

android {
    namespace = "app.dsh.mobile"
    // CI 上 compileSdk=35；本地 SDK 只有 33/36/36.1 时可临时降/升到此值，
    // compileSdk 仅决定编译期 API 可见性，不影响运行时行为（targetSdk=28 才是生效阈值）。
    compileSdk = 36

    defaultConfig {
        applicationId = "com.deepbot.app"
        minSdk = 26
        // 关键决策：targetSdk 28 —— sideload 分发，豁免 Android 10+ 的 W^X 限制，
        // 允许从 filesDir 直接 execve bionic 二进制（Termux 同款策略）。
        targetSdk = 28
        versionCode = 238
        versionName = "1.3.0"

        ndk {
            abiFilters += listOf(targetAbi)
        }
        // 原生层改为「termux clang 预编译 → jniLibs」：本机既没有 NDK，也没有
        // Android SDK 的 cmake，原 externalNativeBuild 指定的 cmake 3.22.1 无法满足。
        // dsh_pty.c 仅依赖 liblog、无 STL（即 -DANDROID_STL=none 的意图），
        // 预编译产物与原 CMake 定义等价：
        //   clang -target aarch64-linux-android24 -shared -fPIC -O2 \
        //     -Wl,-z,max-page-size=16384 -Wl,-soname,libdshpty.so \
        //     -o app/src/main/jniLibs/arm64-v8a/libdshpty.so \
        //     app/src/main/cpp/dsh_pty.c -L/system/lib64 -llog
        // 16KB 页对齐（CMakeLists 里的同一条决策，保留）：Android 15+ 部分 aarch64
        // 真机内核使用 16384 字节页，LOAD p_align=4096 的 so 在其上 dlopen 会报
        // "program alignment" 失败；产物已用 readelf -l 确认 p_align=0x4000。
    }

    // externalNativeBuild/cmake 已移除（本机无 NDK 与 SDK cmake）。
    // libdshpty.so 由 termux clang 预编译到 app/src/main/jniLibs/<abi>/，
    // 源文件仍保留在 app/src/main/cpp/dsh_pty.c。

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    androidResources {
        // assets/runtime.zip 本身已是压缩包（132,615,068 B）。再让 aapt2 压一遍只会白耗
        // CPU/内存且几乎无收益，还会让 APK 内的文件字节与官方包不一致（无法直接比 sha256）。
        noCompress += "zip"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    lint {
        // targetSdk 28 会触发大量 lint 提示（前台服务类型、通知权限等），
        // 这些是刻意的兼容性决策，不阻断构建。
        abortOnError = false
    }
}





// ── runtime 自动匹配 ABI（v1.2.127）─────────────────────────────────────
// 模拟器（x86_64）与真机（arm64）构建此前要手动替换 assets/runtime.zip（126MB）
// 并手改 MANIFEST —— 实测踩过"忘了换 → Android 14 报 libz.so EM_AARCH64"。
// 现按 -Pabi 自动选源（_runtime_bak/rt-{x86_64-current,arm64-work}.zip），
// 用 .abi 戳记避免重复复制大文件。
val prepareRuntime by tasks.registering {
    val abi = targetAbi
    val projRoot = rootProject.projectDir
    val assetsZip = file("src/main/assets/runtime.zip")
    val manifest = file("src/main/assets/runtime/MANIFEST.json")
    val stamp = file("src/main/assets/runtime/.abi")
    outputs.upToDateWhen { false }
    doLast {
        val last = if (stamp.exists()) stamp.readText().trim() else ""
        if (last == abi) {
            logger.lifecycle("runtime: 已是 " + abi + "，跳过")
            return@doLast
        }
        val src = if (abi == "x86_64") File(projRoot, "_runtime_bak/rt-x86_64-current.zip")
                  else File(projRoot, "_runtime_bak/rt-arm64-work.zip")
        if (!src.exists()) {
            logger.warn("runtime: 源缺失 " + src.absolutePath + "，保持 assets 现状")
            return@doLast
        }
        src.copyTo(assetsZip, overwrite = true)
        val sha = MessageDigest.getInstance("SHA-256").digest(assetsZip.readBytes())
            .joinToString("") { b -> "%02x".format(b) }
        val updated = manifest.readLines().joinToString("\n") { line ->
            when {
                line.contains("\"version\"") -> "  \"version\": \"v1.2.102-" + abi + "\","
                line.contains("\"sha256\"") -> "  \"sha256\": \"" + sha + "\","
                else -> line
            }
        }
        manifest.writeText(updated)
        stamp.writeText(abi)
        logger.lifecycle("runtime: → " + abi)
    }
}
tasks.named("preBuild") { dependsOn(prepareRuntime) }

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    // 扩展中心解包链路：Termux .deb 的 data.tar.xz 解码（纯 Java 实现，~110KB）
    implementation("org.tukaani:xz:1.10")
    // Shizuku 官方 API（m1.25）：bind 服务才能触发授权弹窗与真实 adb-shell 能力
    // aidl 提供 IShizukuService/IRemoteProcess（进程执行），api 提供授权与 binder 封装
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    implementation("dev.rikka.shizuku:aidl:13.1.5")
    // 微软 Edge 神经网络语音合成（v1.2.78）：客户端直连，需 WebSocket 客户端
    implementation("org.java-websocket:Java-WebSocket:1.6.0")
    // 离线语音识别已移除（v1.2.75）：small 模型质量差、大模型 228MB 不划算。
    // 语音通道 = 系统识别 → 云端 API（免费或自建，OpenAI 兼容），见 CloudAsr。
    // 离线 OCR（v1.2.123）：ML Kit 中文文本识别 bundled 版——模型随 APK 分发，
    // 不依赖 Google Play 服务（国内 ROM 可用）。无障碍节点树缺失的场景
    // （WebView 不暴露节点、自绘/Canvas 界面）从截图提取文字作为 dump 兜底。
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")
}