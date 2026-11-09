# ── Xposed 框架按类名/清单文件找入口，这几个类不能被改名 ──────────────────────
#
# 1) HookEntry：META-INF/xposed/java_init.list 里写的是全限定类名，框架 Class.forName 加载。
# 2) MaskProvider：AndroidManifest 里 android:name=".data.MaskProvider"（manifest 里的类名
#    会被 R8 自动保留，这里显式写一遍是防止以后改成动态注册）。
# 3) XposedProvider：libxposed 官方通道，LSPosed 在**安装的 APK 里**扫描这个名字来决定
#    "这个包是不是模块"。它虽然也写在 manifest 里，但 LSPosed 是拿 APK 去扫的，
#    靠 manifest 保留不够保险 —— 必须按原类名留在 dex 里。
-keep class com.frederic22.hyperextreme.hook.HookEntry { *; }
-keep class com.frederic22.hyperextreme.data.MaskProvider { *; }
-keep class io.github.libxposed.service.XposedProvider { *; }

# 4) SpatialWallpaperService：系统壁纸服务（2026-10-30）。
#    它由 WallpaperManagerService 按 manifest 里的 ComponentName 实例化 ——
#    虽然 manifest 引用会让 R8 自动保留，但这条链**一旦断了没有任何编译期信号**
#    （现象只是"系统壁纸选择器里看不到这个壁纸"），所以显式写死。
-keep class com.frederic22.hyperextreme.wallpaper.SpatialWallpaperService { *; }

# ── libxposed API 整个包保持原样 ──────────────────────────────────────────────
#
# libxposed-api-102.jar 是 compileOnly，不进 APK；运行时由框架在**我们的 classloader** 里
# 提供同名的类，两边必须能对上。框架侧还会反射调用 XposedModule 的 onModuleLoaded /
# onPackageReady 等回调，所以类名、方法名、继承关系都不能动。
-keep class io.github.libxposed.api.** { *; }
-keep interface io.github.libxposed.api.** { *; }
-keep class io.github.libxposed.service.** { *; }

# ── 枚举 ─────────────────────────────────────────────────────────────────────
#
# MaskRole 全工程都是 MaskRole.SUBJECT 这种编译期常量用法（值被内联），没有 valueOf 反射，
# R8 的枚举优化不会把 constants 剪掉。这里 keep 是为了稳 —— 枚举一旦被 R8 当作
# "不可达" 处理，`MaskRole.entries` 会变空，界面直接没有角色可选。
-keep class com.frederic22.hyperextreme.data.MaskRole { *; }
-keepclassmembers enum com.frederic22.hyperextreme.** { *; }

# ── HookRuntime 的静态状态 ────────────────────────────────────────────────────
#
# appContext() 等是靠静态字段存 Context 的，hook 侧和界面侧可能在不同的 classloader
# 里各自持有一份。R8 若把它当作 "只写不读" 剪掉，激活检测会失灵。
-keep class com.frederic22.hyperextreme.hook.HookRuntime { *; }

# ── ONNX Runtime（空间壁纸深度推理）────────────────────────────────────────────
#
# ⚠️ **必须整包 keep，一个类都不能改名**（2026-10-22 因缺这条踩过 SIGABRT）：
# ORT 的 native 层用 `GetMethodID` **按类名字符串**反射 Java 类，
# R8 一旦把 `ai.onnxruntime.TensorInfo` 之类改成 `a.a.a`，native 找不到类就直接 abort ——
# 症状不是 Java 异常，logcat 里只有 `Fatal signal 6`，真要看得去 `/data/tombstones/`
# 找 Abort message（`java_class == null in call to GetMethodID`）。
-keep class ai.onnxruntime.** { *; }
-keep interface ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**

# ── 排查用：保留行号，让崩溃栈能对上源码 ──────────────────────────────────────
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ── 空间壁纸底图填充（libspatial_fill.so）──────────────────────────────────────
#
# JNI 是按 「类名_方法名」 动态查找符号的：R8 一旦把 SpatialInpaint 改名成 a.a.a，
# libspatial_fill.so 里写死的 Java_com_frederic22_hyperextreme_spatial_SpatialInpaint_nativeFill
# 就再也对不上，运行期直接 UnsatisfiedLinkError（不是编译期问题，装机才会发现）。
-keep class com.frederic22.hyperextreme.spatial.SpatialInpaint { *; }
-keepclasseswithmembernames class com.frederic22.hyperextreme.spatial.** {
    native <methods>;
}

# ── 光栅壁纸的 AICR 客户端（2026-11-03）────────────────────────────────────────
#
# Root 兜底路径是 `su -c "CLASSPATH=<base.apk> app_process ... RootSegmentMain"` ——
# 入口类是按**全限定类名字符串**找的，R8 改一次名这条路就静默失效（现象只是"降级也失败"）。
# AicrVision 一并 keep：它被 RootSegmentMain 的静态入口间接引用，R8 有可能把它内联掉。
-keep class com.frederic22.hyperextreme.aicr.RootSegmentMain { *; }
-keep class com.frederic22.hyperextreme.aicr.AicrVision { *; }
