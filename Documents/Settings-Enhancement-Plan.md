# WindPlayer 设置增强计划

## 目标

将当前简单的平铺式设置页（4 分区 / 7 字段）升级为**分类导航式**设置中心，
对标 mpv-android / PotPlayer / VLC 的设置体验，同时保持 Mastercard 设计语言。

---

## 一、UI 架构：分类导航

### 当前问题
所有设置项堆在一个可滚动列表里，项目增多后体验差。

### 新设计
桌面端用**左侧分类列表 + 右侧内容面板**；安卓端用**列表入口 → 子页面**。

```
桌面端:
┌─────────────┬──────────────────────────┐
│  Settings   │  字幕设置                 │
│ ─────────── │                           │
│ ▸ 播放      │  字号        ━━━●━━ 55   │
│ ▸ 视频      │  描边        ━━●━━━━ 3   │
│ ▸ 音频      │  字幕颜色    ● White     │
│ ▸ 字幕  ◄   │  背景透明度  ━━━━━●━ 0   │
│ ▸ 网络      │  字体族      Sans-serif  │
│ ▸ 截图      │  对齐位置    底部居中     │
│ ▸ 外观      │                           │
│ ▸ 语言      │  ── 恢复字幕默认 ──       │
│ ▸ 高级      │                           │
└─────────────┴──────────────────────────┘

安卓端:
┌─────────────────────────┐
│ ← Settings              │
├─────────────────────────┤
│ ▶ 播放                  │
│ ▶ 视频                  │
│ ▶ 音频                  │
│ ▶ 字幕                  │
│ ▶ 网络                  │
│ ▶ 截图                  │
│ ▶ 外观与语言            │
│ ▶ 高级                  │
└─────────────────────────┘
点击 → 展开该分类的详细设置
```

### 分类定义

| 分类 | 桌面图标 | 包含项目 |
|------|----------|----------|
| **播放** | `play` | 默认音量、硬件解码、自动播放下一集、默认播放速度、断点续播、Seek步长 |
| **视频** | `video` | GPU API、去隔行、默认画面比例 |
| **音频** | `speaker-high` | 声道布局、音调修正 |
| **字幕** | `subtitles` | 字号、描边、字幕颜色、背景透明度、字体族、对齐位置 |
| **网络** | `globe` | 缓存大小、User-Agent |
| **截图** | `camera` | 截图格式、JPEG质量、截图内容（含/不含字幕） |
| **外观** | `palette` | 主题模式（浅色/深色/跟随系统） |
| **语言** | `translate` | 界面语言选择 |
| **高级** | `gear` | mpv 配置目录打开、日志级别、恢复全部默认 |

---

## 二、新增字段

### PlayerSettings 扩展

```kotlin
data class PlayerSettings(
    // --- 现有 ---
    val defaultVolume: Int = 100,
    val hwdecAuto: Boolean = true,
    val subFontSize: Int = 55,
    val subBorderSize: Int = 3,
    val autoPlayNext: Boolean = true,
    val language: String = "en",
    val themeMode: ThemeMode = ThemeMode.SYSTEM,

    // --- 播放 ---
    val defaultSpeed: Double = 1.0,         // mpv: 初始 speed 属性
    val resumePlayback: Boolean = true,     // 全局断点续播开关
    val seekStepShort: Int = 5,             // 方向键短跳秒数
    val seekStepLong: Int = 30,             // Shift+方向键长跳秒数

    // --- 视频 ---
    val gpuApi: String = "auto",            // mpv: gpu-api (auto/opengl/vulkan/d3d11)
    val deinterlace: Boolean = false,       // mpv: deinterlace (auto/yes/no)
    val videoAspect: String = "auto",       // mpv: video-aspect-override ("-1" = auto)

    // --- 音频 ---
    val audioChannels: String = "auto",     // mpv: audio-channels (auto/stereo/5.1/7.1)
    val pitchCorrection: Boolean = true,    // mpv: audio-pitch-correction

    // --- 字幕 ---
    val subColor: String = "#FFFFFF",       // mpv: sub-color
    val subBackColor: String = "#00000000", // mpv: sub-back-color (ARGB, 默认全透明)
    val subFontFamily: String = "sans-serif", // mpv: sub-font
    val subAlignY: String = "bottom",       // mpv: sub-align-y (top/center/bottom)

    // --- 网络 ---
    val cacheSize: Int = 150,               // mpv: demuxer-max-bytes (MB)
    val userAgent: String = "",             // mpv: user-agent (空 = mpv 默认)

    // --- 截图 ---
    val screenshotFormat: String = "png",   // mpv: screenshot-format (png/jpg/jpeg)
    val screenshotJpegQuality: Int = 90,    // mpv: screenshot-jpeg-quality (0-100)
    val screenshotSubtitles: Boolean = true // mpv: screenshot flag "subtitles" vs "video"
)
```

### 向后兼容
所有新字段有默认值，现有 `settings.properties` / `SharedPreferences` 加载时缺失的字段自动用默认值。
`DesktopPersistence.loadSettings` 和 `SettingsHelper.load` 只需加 `?: defaultValue`。

---

## 三、mpv 属性映射

### 启动时应用（windowOpened / MpvRenderView.surfaceCreated）
通过 `player.setOption(key, value)` 在 `initialize()` 之前设置：

| 设置项 | mpv 选项 | 示例值 |
|--------|----------|--------|
| 默认音量 | `volume` | `100` |
| 硬件解码 | `hwdec` | `auto-safe` / `no` |
| GPU API | `gpu-api` | `auto` |
| 默认速度 | `speed` (setOption) | `1.00` |
| 缓存 | `demuxer-max-bytes` | `150MiB` |
| User-Agent | `user-agent` | (空则不设) |
| 截图格式 | `screenshot-format` | `png` |
| 截图质量 | `screenshot-jpeg-quality` | `90` |

### 运行时应用（applyMpvSettings）
通过 `player.setProperty(key, value)` 即时生效：

| 设置项 | mpv 属性 |
|--------|----------|
| 字幕字号 | `sub-font-size` |
| 字幕描边 | `sub-border-size` |
| 字幕颜色 | `sub-color` |
| 字幕背景色 | `sub-back-color` |
| 字幕字体 | `sub-font` |
| 字幕对齐 | `sub-align-y` |
| 去隔行 | `deinterlace` |
| 画面比例 | `video-aspect-override` |
| 声道布局 | `audio-channels` |
| 音调修正 | `audio-pitch-correction` |

### Android 额外映射
Android 端 `MpvRenderView.surfaceCreated` 已有 `setOption` 序列，只需扩展。
截图内容标志 (`screenshotSubtitles`) 影响 `screenshot-to-file` 命令的 flags 参数。

---

## 四、UI 组件设计

### 颜色选择器
字幕颜色 / 背景色用预设色板 + Hex 输入：
```
预设: ● White  ● Yellow  ● Cyan  ● Black  ● Custom...
Hex:  #FFFFFF  [____]
```

### 枚举选择器
GPU API / 声道布局 / 字体族等用下拉菜单或分段选择器。

### 滑块
与现有 `SettingSliderRow` 相同，支持实时预览。

### 分类导航
桌面端：
- 左侧 `LazyColumn` 列出分类项（图标 + 名称 + 描述）
- 右侧根据 `selectedCategory` 显示对应设置组
- `remember { mutableStateOf(SettingsCategory.PLAYBACK) }` 管理当前分类

安卓端：
- 首页列表点击 → `screen = "settings_playback"` 等子页面
- 或用一个 `selectedCategory` state + `AnimatedContent` 切换

---

## 五、持久化扩展

### DesktopPersistence.kt (`loadSettings` / `saveSettings`)
```kotlin
// loadSettings 新增：
defaultSpeed = props.getProperty("defaultSpeed")?.toDoubleOrNull() ?: 1.0,
resumePlayback = props.getProperty("resumePlayback")?.toBooleanStrictOrNull() ?: true,
seekStepShort = props.getProperty("seekStepShort")?.toIntOrNull() ?: 5,
// ... 其他字段同理

// saveSettings 新增：
props.setProperty("defaultSpeed", settings.defaultSpeed.toString())
props.setProperty("resumePlayback", settings.resumePlayback.toString())
// ...
```

### SettingsHelper.kt (Android)
同理，新增 `putXxx/getXxx` 对。

---

## 六、i18n 键新增

需要新增约 40 个 i18n 键（中英双语），覆盖：
- 分类名称：`cat_playback` / `cat_video` / `cat_audio` / `cat_subtitle` / `cat_network` / `cat_screenshot` / `cat_appearance` / `cat_language` / `cat_advanced`
- 新设置项标签：`default_speed` / `resume_playback` / `seek_step_short` / `seek_step_long` / `gpu_api` / `deinterlace` / `video_aspect` / `audio_channels` / `pitch_correction` / `sub_color` / `sub_back_opacity` / `sub_font` / `sub_align` / `cache_size` / `user_agent` / `screenshot_format` / `screenshot_quality` / `screenshot_subtitles`
- 枚举值：`gpu_auto` / `gpu_opengl` / `gpu_vulkan` / `gpu_d3d11` / `align_bottom` / `align_center` / `align_top` / ...

---

## 七、实施路线

### 阶段一（本轮）：高优先级 — 字幕 + 播放 + 截图
1. 扩展 `PlayerSettings`（+15 字段）
2. 扩展持久化层
3. 重构 SettingsScreen 为分类导航
4. 实现 3 个核心分类的设置项
5. 扩展 `applyMpvSettings`
6. i18n 键值
7. 安卓端同步

### 阶段二（后续）：中优先级 — 视频 + 音频 + 网络
- GPU API / 去隔行 / 画面比例
- 声道布局 / 音调修正
- 缓存 / User-Agent

### 阶段三（后续）：高级
- mpv 配置目录编辑
- 日志级别
- 自定义快捷键编辑
- Shader 选择
