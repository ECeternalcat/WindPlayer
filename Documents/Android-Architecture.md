# WindPlayer 安卓适配架构

## 核心原则

**桌面 UI 和安卓 UI 完全独立实现**，共享数据层和业务逻辑。

当前 `ui-compose/src/commonMain` 中的 UI 代码（PlayerScreen、FileBrowserScreen 等）是为桌面端设计的，包含桌面特定的交互模式（键盘快捷键、鼠标手势、LayoutManager Canvas/ComposePanel 分割）。这些代码将迁移到 `desktopMain`，`commonMain` 仅保留真正共享的代码。

---

## 1. 模块重构

### 1.1 当前结构

```
core-mpv/   commonMain(expect MpvPlayer) + desktopMain(JNA)
core-vfs/   commonMain(数据模型) + desktopMain(SFTP/WebDAV/FTP/Local)
ui-compose/ commonMain(全部UI) + desktopMain(图标 actual)
app-desktop/ Main.kt
```

### 1.2 目标结构

```
core-mpv/
  src/commonMain/          expect MpvPlayer + MpvEvent（不变）
  src/desktopMain/         JNA 绑定（不变）
  src/androidMain/         JNI 绑定 + mpv render API（新增）

core-vfs/
  src/commonMain/          数据模型 + VfsClient 接口 + TrackMatcher（不变）
  src/desktopMain/         SFTP/WebDAV/FTP/Local + VfsManager + StreamProxy（不变）
  src/androidMain/         SAF 文件访问 + 网络协议复用（新增）

ui-compose/
  src/commonMain/          I18n + PhosphorIcons 常量 + PlayerSettings（仅共享代码）
  src/desktopMain/         桌面 UI 全部迁移至此（PlayerScreen 等）
  src/androidMain/         安卓移动端 UI（全新实现）

app-desktop/               Main.kt（不变）
app-android/               MainActivity + Activity 生命周期（新增）
```

### 1.3 迁移清单：commonMain → desktopMain

以下文件从 `ui-compose/src/commonMain/` 移至 `ui-compose/src/desktopMain/`：

| 文件 | 原因 |
|------|------|
| App.kt | 桌面屏幕导航（onScreenChange 回调到 LayoutManager） |
| PlayerScreen.kt | 桌面播放控制（LayoutManager 交互、键盘依赖） |
| FileBrowserScreen.kt | 桌面文件浏览器（侧边栏布局、鼠标交互） |
| SettingsScreen.kt | 桌面设置界面（可复用但布局为桌面优化） |
| TrackSelectionSheet.kt | 桌面轨道选择（内联展开依赖 LayoutManager） |
| AddServerDialog.kt | 服务器配置对话框（桌面 Dialog 模式） |

以下文件保留在 `commonMain`：

| 文件 | 原因 |
|------|------|
| I18n.kt | 纯 Kotlin，语言无关 |
| Icons.kt (expect) | PhosphorIcons 常量 + expect iconPainter() |
| PlayerSettings.kt | 数据模型，平台无关 |

---

## 2. Gradle 配置

### 2.1 新增版本

```toml
# gradle/libs.versions.toml
agp = "8.7.0"                    # Android Gradle Plugin
androidx-activity-compose = "1.9.3"
androidx-lifecycle = "2.8.7"
androidx-core-ktx = "1.15.0"
```

### 2.2 新增插件

```toml
[plugins]
android-application = { id = "com.android.application", version.ref = "agp" }
android-library = { id = "com.android.library", version.ref = "agp" }
kotlin-android = { id = "org.jetbrains.kotlin.android", version.ref = "kotlin" }
```

### 2.3 settings.gradle.kts

```kotlin
include(":core-mpv")
include(":core-vfs")
include(":ui-compose")
include(":app-desktop")
include(":app-android")  // 新增
```

### 2.4 KMP 模块 Android Target

每个 KMP 模块（core-mpv, core-vfs, ui-compose）需要添加 Android target：

```kotlin
// core-mpv/build.gradle.kts
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)  // 新增
}

kotlin {
    jvm("desktop")
    androidTarget()  // 新增

    sourceSets {
        val desktopMain by getting { ... }
        val androidMain by getting {
            dependencies {
                // JNI 绑定相关
            }
        }
    }
}
```

### 2.5 app-android 模块

```kotlin
// app-android/build.gradle.kts
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "dev.windplayer"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.windplayer"
        minSdk = 24          // Android 7.0+
        targetSdk = 35
    }

    sourceSets["main"].jniLibs.srcDirs("jniLibs")
}

dependencies {
    implementation(project(":ui-compose"))
    implementation(project(":core-mpv"))
    implementation(project(":core-vfs"))

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.core.ktx)
    implementation(compose.ui)
    implementation(compose.material3)
    implementation(compose.foundation)
}
```

---

## 3. mpv 安卓绑定

### 3.1 方案：JNI + Render API

桌面端使用 `wid`（窗口句柄）让 mpv 直接渲染到窗口。安卓不支持这种方式，需要使用 mpv 的 **Render API**：

```
SurfaceView → Surface → EGL Context → mpv_render_context → OpenGL ES 渲染
```

### 3.2 .so 库准备

1. 交叉编译 libmpv for Android（arm64-v8a, armeabi-v7a, x86_64）
2. 或使用预编译库（如 mpv-android 项目的构建产物）
3. 放入 `app-android/src/main/jniLibs/{abi}/libmpv.so`

### 3.3 JNI 绑定层

```kotlin
// core-mpv/src/androidMain/kotlin/dev/windplayer/mpv/MpvNative.kt
object MpvNative {
    init { System.loadLibrary("mpv") }

    external fun create(): Long                          // mpv_create
    external fun initialize(handle: Long): Int           // mpv_initialize
    external fun setOptionString(handle: Long, k: String, v: String): Int
    external fun setOptionInt64(handle: Long, k: String, v: Long): Int
    external fun commandString(handle: Long, cmd: String): Int
    external fun setPropertyString(handle: Long, k: String, v: String): Int
    external fun getPropertyString(handle: Long, k: String): String?
    external fun getPropertyLong(handle: Long, k: String): Long
    external fun getPropertyDouble(handle: Long, k: String): Double
    external fun waitForEvent(handle: Long, timeout: Double): MpvEventData?
    external fun renderContextCreate(handle: Long, glGetProcAddr: Long, apiType: Int): Long
    external fun renderContextRender(ctx: Long, fbo: Int, width: Int, height: Int)
    external fun destroy(handle: Long)
}
```

实际实现可以：
- **方案 A**：C/C++ JNI 桥接层（需 NDK 编译 .so）
- **方案 B**：JavaCPP（类似 JNA 但支持 Android）
- **方案 C**：直接复用 mpv-android 项目的 Java 绑定

推荐 **方案 A**（NDK JNI），性能最好且最灵活。

### 3.4 渲染集成

```kotlin
// app-android: SurfaceView + EGL + mpv render
class MpvSurfaceView(context: Context) : SurfaceView(context) {
    fun onSurfaceAvailable(surface: Surface) {
        // 1. 创建 EGL Context
        // 2. mpv_render_context_create(handle, glGetProcessAddress, OPENGL_ES)
        // 3. 每帧 mpv_render_context_render(ctx, fbo, w, h)
    }
}
```

在 Compose 中通过 `AndroidView` 嵌入：

```kotlin
@Composable
fun AndroidPlayerSurface(player: MpvPlayer, modifier: Modifier) {
    AndroidView(
        factory = { ctx ->
            MpvSurfaceView(ctx).also { sv ->
                sv.holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(h: SurfaceHolder) {
                        player.attachSurface(h.surface)
                    }
                    override fun surfaceDestroyed(h: SurfaceHolder) {
                        player.detachSurface()
                    }
                    ...
                })
            }
        },
        modifier = modifier
    )
}
```

### 3.5 MpvPlayer.actual (androidMain)

```kotlin
// core-mpv/src/androidMain/
actual class MpvPlayer actual constructor() {
    private var handle: Long = 0
    private var renderCtx: Long = 0

    actual fun create() { handle = MpvNative.create() }
    actual fun initialize() { MpvNative.initialize(handle) }
    actual fun setOption(key: String, value: String) { ... }
    actual fun command(vararg args: String) { ... }
    actual fun getPropertyString(name: String): String? { ... }
    actual fun getPropertyDouble(name: String): Double { ... }

    // Android 专有
    fun attachSurface(surface: Surface) {
        // 创建 EGL，初始化 render context
    }
    fun detachSurface() { ... }
}
```

---

## 4. 安卓 UI 架构

### 4.1 屏幕设计

| 屏幕 | 布局 | 说明 |
|------|------|------|
| 文件浏览器 | 全屏列表 + 底部导航 | Local / Servers / Recent 三 Tab，无侧边栏 |
| 播放器 | 全屏视频 + 叠加控件 | SurfaceView 满屏，控件浮层 3s 自动隐藏 |
| 设置 | 全屏滚动表单 | 与桌面相同的设置项，移动端布局 |
| 轨道选择 | 底部 Sheet | Modal BottomSheet，Video/Audio/Subtitle Tab |

### 4.2 文件浏览器（移动端）

```
┌─────────────────────────┐
│ TopAppBar               │
│ WindPlayer    [Search]  │
├─────────────────────────┤
│ LazyColumn              │
│  📁 Movies              │
│  📁 TV Shows            │
│  🎬 video1.mkv    ▶    │
│  🎬 video2.mkv    ▶    │
│  ...                    │
│                         │
├─────────────────────────┤
│ NavBottoBar             │
│ [Local] [Servers] [Recent] │
└─────────────────────────┘
```

与桌面端的区别：
- 无侧边栏（屏幕窄，全屏利用）
- 底部导航栏替代侧边栏导航
- 文件夹书签通过菜单访问（非常驻侧边栏）
- 搜索通过 TopAppBar 图标触发（展开式搜索栏）

### 4.3 播放器（移动端）

```
┌─────────────────────────┐
│ ←  文件名.mkv      ⋮    │  ← 顶部控件栏（半透明）
│                         │
│                         │
│                         │
│        ▶ / ⏸            │  ← 中央播放按钮（半透明）
│                         │
│                         │
│                         │
│ 01:23 ════●════ 02:00   │  ← 进度条
│ ⏪  ▶/⏸  ⏩   🔊  📋   │  ← 底部控件栏（半透明）
└─────────────────────────┘
```

与桌面端的区别：
- 控件叠加在视频上（半透明浮层），非底部独立面板
- 更大的触摸目标（按钮 48dp+）
- 手势驱动（触摸滑动），非鼠标/键盘
- 3 秒自动隐藏全部控件和状态栏

### 4.4 Compose 代码结构

```
ui-compose/src/androidMain/kotlin/dev/windplayer/ui/
├── AndroidApp.kt              // NavHost 导航控制器
├── mobile/
│   ├── MobileFileBrowser.kt   // 移动端文件浏览器
│   ├── MobilePlayer.kt        // 移动端播放器（叠加控件）
│   ├── MobileSettings.kt      // 移动端设置
│   ├── MobileTrackSheet.kt    // 底部 Sheet 轨道选择
│   └── PlayerSurface.kt       // AndroidView + SurfaceView 封装
└── gestures/
    ├── TouchGestures.kt       // 触摸手势（滑动/点击/双击）
    └── GestureZones.kt        // 点击区域划分
```

---

## 5. 触摸手势系统

### 5.1 手势定义

| 手势 | 区域 | 功能 |
|------|------|------|
| 单击 | 中央 | 播放/暂停 |
| 单击 | 左半 | 快退 10s |
| 单击 | 右半 | 快进 10s |
| 双击 | 全屏 | 全屏切换（或显示/隐藏控件） |
| 水平滑动 | 全屏 | Seek（滑动距离 = 时间） |
| 垂直滑动 | 左 1/3 | 亮度 |
| 垂直滑动 | 右 2/3 | 音量 |
| 长按 | 全屏 | 锁定/解锁控件 |
| 捏合 | 全屏 | 缩放/画面比例 |

### 5.2 实现方案

```kotlin
// 使用 Compose pointerInput
Modifier.pointerInput(Unit) {
    detectTapGestures(
        onTap = { offset -> /* 区域判断 */ },
        onDoubleTap = { offset -> /* 全屏 */ },
        onLongPress = { offset -> /* 锁定 */ }
    )
}
// 水平/垂直滑动用 detectDragGestures
Modifier.pointerInput(Unit) {
    detectVerticalDragGestures(
        onDragStart = { offset -> /* 记录起始区域 */ },
        onVerticalDrag = { change, dragAmount -> /* 亮度/音量 */ }
    )
}
```

---

## 6. 文件访问

### 6.1 本地文件

| 方式 | 用途 | API |
|------|------|-----|
| SAF (Storage Access Framework) | 用户授权的外部存储 | `ContentResolver` + `DocumentFile` |
| App-specific 目录 | 应用私有文件 | `context.filesDir` / `java.io.File` |
| MediaStore | 媒体库文件 | `MediaStore.Video` 查询 |

推荐：使用 SAF 让用户选择文件夹，缓存 URI 权限，通过 ContentResolver 列出文件。

### 6.2 网络协议

SFTP / WebDAV / FTP 的客户端实现（SSHJ / Ktor / Commons Net）大部分可以在 Android 上复用，只需调整网络和线程配置。

StreamProxy（SFTP HTTP 代理）也可复用 — Android 支持 `java.net` HTTP 服务器。

### 6.3 VfsClient (androidMain)

```kotlin
// core-vfs/src/androidMain/
class SafLocalClient(
    private val contentResolver: ContentResolver,
    private val treeUri: Uri
) : VfsClient {
    override suspend fun listDirectory(path: String): List<FileNode> {
        // 用 DocumentFile.fromTreeUri(treeUri) 遍历
    }
    override suspend fun resolveUrl(path: String): String {
        // 本地文件路径
    }
    ...
}
```

---

## 7. Activity 生命周期

```kotlin
class MainActivity : ComponentActivity() {
    private val player = MpvPlayer()
    private val vfsManager = VfsManager()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        player.create()
        player.setOption("vo", "gpu")  // 或 gpu-next
        player.setOption("hwdec", "auto")
        player.setOption("keep-open", "yes")
        player.initialize()

        setContent {
            WindPlayerTheme {
                AndroidApp(player = player, vfsManager = vfsManager)
            }
        }

        // 保持屏幕常亮（播放时）
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun onPause() {
        super.onPause()
        player.command("set", "pause", "yes")
        // 保存播放进度
    }

    override fun onDestroy() {
        super.onDestroy()
        player.dispose()
    }
}
```

---

## 8. 依赖关系图

```
app-desktop ──→ ui-compose(desktop) ──→ core-mpv(desktop) ──→ JNA
            ──→                        ──→ core-vfs(desktop) ──→ SSHJ/Ktor/FTP
            ──→ app-desktop Main.kt

app-android ──→ ui-compose(android) ──→ core-mpv(android) ──→ JNI(.so)
            ──→                         ──→ core-vfs(android) ──→ SAF/SSHJ/Ktor/FTP
            ──→ app-android MainActivity

共享层:
  ui-compose(common)   ──→ I18n, PlayerSettings, PhosphorIcons
  core-mpv(common)     ──→ expect MpvPlayer
  core-vfs(common)     ──→ expect VfsClient, FileNode, TrackMatcher
```

---

## 9. 实施路线图

### 阶段 A：项目骨架（1-2 天）
- [ ] Gradle 配置：AGP 插件、Android target、app-android 模块
- [ ] commonMain → desktopMain 文件迁移
- [ ] MainActivity 空壳 + 编译通过

### 阶段 B：mpv JNI 绑定（3-5 天）
- [ ] 交叉编译 libmpv.so（arm64-v8a + x86_64）
- [ ] JNI 绑定层（C + Kotlin external fun）
- [ ] SurfaceView + EGL 渲染集成
- [ ] MpvPlayer.android.kt actual 实现

### 阶段 C：文件浏览器 UI（2-3 天）
- [ ] SAF 文件访问集成
- [ ] MobileFileBrowser composable（全屏列表 + 底部导航）
- [ ] 服务器管理（复用 core-vfs 网络协议）

### 阶段 D：播放器 UI（2-3 天）
- [ ] MobilePlayer composable（叠加控件 + 自动隐藏）
- [ ] 触摸手势系统（滑动/点击/双击）
- [ ] 播放控制（进度条/音量/播放暂停）

### 阶段 E：设置 + 轨道 + 打磨（1-2 天）
- [ ] MobileSettings（复用 PlayerSettings + I18n）
- [ ] MobileTrackSheet（BottomSheet）
- [ ] Activity 生命周期集成
- [ ] 横竖屏适配

---

## 10. 关键风险

| 风险 | 影响 | 缓解 |
|------|------|------|
| libmpv.so 交叉编译复杂 | 阻塞 JNI 绑定 | 使用 mpv-android 预编译库或 Docker 构建环境 |
| EGL/OpenGL ES 渲染调试困难 | 视频黑屏 | 先在独立 Android 原生项目验证，再集成到 CMP |
| SAF 性能问题 | 文件列表加载慢 | 缓存 URI 权限，异步加载，分页 |
| CMP Android 兼容性 | 编译/运行错误 | 保持 CMP 1.9.0 + Kotlin 2.3.10，充分测试 |
| 内存限制（移动端） | 崩溃 | 降低缓存大小，及时释放资源 |
