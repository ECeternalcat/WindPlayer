# AI 字幕双语对照功能 — 执行计划

## 1. 背景与目标

当前 AI 翻译管线（阶段六十三）只输出**翻译后**的单语 SRT。用户反馈需要：

1. **保留原文字幕** — 翻译时同时生成原文 SRT，供语言学习对照
2. **双语同时显示** — 译文 + 原文同时可见
3. **显示位置可选** — 有些用户喜欢「译文在下、原文在上」（mpv 次字幕轨原生效果），有些喜欢「两行都在底部堆叠」
4. **播放器入口集中** — 把当前「生成字幕」按钮改为「AI 字幕」面板，集成生成 / 管理 / 轨道切换
5. **字幕文件管理** — 在播放器和设置页都能查看/删除已生成的 SRT

## 2. 用户场景

### 场景 A：看外语视频学语言
- 用户选「生成翻译字幕」→ 得到译文（底部）+ 原文（顶部），逐句对照
- 可切换「底部双语堆叠」模式，两行都在底部

### 场景 B：只需要翻译
- 用户选「生成翻译字幕」→ 设置里选「仅译文」→ 只显示翻译

### 场景 C：只需要原文转录
- 用户选「生成原文字幕」→ 只输出 ASR 转录结果，不调 LLM

### 场景 D：管理字幕
- 在 AI 字幕面板看到当前视频的外挂字幕轨
- 切换主/次轨
- 删除不需要的外挂 SRT
- 在设置页批量清理所有已生成字幕（已在阶段六十九实现）

## 3. 三种显示模式

| 模式 | 实现方式 | 效果 | 适用场景 |
|------|----------|------|----------|
| **仅译文** | 单条 SRT，设为 `sid` | 底部一行译文 | 只需翻译 |
| **双语-上下分离** | 两条 SRT，译文设为 `sid`，原文设为 `secondary-sid` | 译文底部 + 原文顶部 | 屏幕大、想清晰区分 |
| **双语-底部堆叠** | 合并 SRT（每条两行：译文\n原文），设为 `sid` | 底部两行堆叠 | 屏幕小、习惯底部看 |

### mpv 次字幕轨（上下分离模式）

mpv 原生支持：
```
setProperty("sid", translatedTrackId)           // 主字幕（底部）
setProperty("secondary-sid", sourceTrackId)     // 次字幕（顶部）
setProperty("secondary-sub-visibility", "yes")  // 显示次字幕
```

次字幕默认渲染在画面顶部，样式由 `secondary-sub-ass-style` 控制。

### 合并 SRT（底部堆叠模式）

生成一条特殊 SRT，每个 cue 包含两行文本：
```
1
00:00:01,000 --> 00:00:04,000
你好世界。
Hello world.

2
00:00:04,000 --> 00:00:08,000
这是测试。
This is a test.
```

只有一条字幕轨，mpv 渲染为底部两行堆叠。

## 4. 数据模型变更

### 4.1 SubtitleMountRequest（新增）

```kotlin
// TranslateService.kt
data class SubtitleMountRequest(
    val primaryPath: String,          // 主字幕文件路径
    val secondaryPath: String? = null, // 次字幕路径（原文），null = 不启用次轨
    val displayMode: DisplayMode = DisplayMode.TRANSLATED_ONLY
)

enum class DisplayMode {
    TRANSLATED_ONLY,    // 仅译文
    DUAL_SEPARATED,     // 双语-上下分离（主+次轨）
    DUAL_STACKED        // 双语-底部堆叠（合并 SRT）
}
```

替换当前的 `pendingSubtitleMount: MutableStateFlow<String?>`。

### 4.2 PlayerSettings 扩展

```kotlin
// commonMain PlayerSettings.kt 新增
val subtitleDisplayMode: SubtitleDisplayMode = SubtitleDisplayMode.DUAL_SEPARATED

enum class SubtitleDisplayMode {
    TRANSLATED_ONLY,    // 仅译文
    DUAL_SEPARATED,     // 译文底部 + 原文顶部
    DUAL_STACKED        // 两行底部堆叠
}
```

### 4.3 TranslationManager 改造

翻译模式（`doTranslate=true`）输出三个文件：

| 文件 | 内容 | 用途 |
|------|------|------|
| `wp_xx.srt` | 译文 | TRANSLATED_ONLY / DUAL_SEPARATED 的主轨 |
| `wp_xx_source.srt` | 原文 | DUAL_SEPARATED 的次轨 |
| `wp_xx_dual.srt` | 译文+原文堆叠 | DUAL_STACKED 的主轨 |

仅转录模式（`doTranslate=false`）只输出 `wp_xx.srt`（原文）。

## 5. 实施步骤

### Step 1：翻译管线输出双文件（核心基础）

**改动文件**：
- `TranslationManager.kt` — writeSrt 改造为输出多文件
- `SubtitleSegment.kt` — 新增 `toDualSrtBlock()` 合并原文+译文为两行
- `SubtitleMergeEngine.kt` — 新增 `toDualSrtContent()` 生成堆叠 SRT
- `TranslateService.kt` — `pendingSubtitleMount` 改为 `SubtitleMountRequest`

**验证点**：
- 翻译完成后 logcat 显示 3 个 SRT 路径
- 仅转录完成后显示 1 个 SRT 路径

### Step 2：双轨挂载逻辑

**改动文件**：
- `MobilePlayerScreen.kt` — collect `pendingSubtitleMount` 时根据 `displayMode` 挂载

**挂载逻辑**：
```kotlin
when (request.displayMode) {
    TRANSLATED_ONLY -> {
        player.command("sub-add", request.primaryPath, "select")
    }
    DUAL_SEPARATED -> {
        player.command("sub-add", request.primaryPath, "select")     // 译文主轨
        player.command("sub-add", request.secondaryPath!!, "auto")   // 原文次轨
        // 查询最后添加的 sub 轨道 ID → setProperty("secondary-sid", id)
    }
    DUAL_STACKED -> {
        // primaryPath 指向 wp_xx_dual.srt（已合并两行）
        player.command("sub-add", request.primaryPath, "select")
    }
}
```

**次轨 ID 获取**：
```kotlin
// sub-add 后，遍历 track-list 找到最后一个 type=sub + external=true 的轨道 ID
val count = player.getPropertyLong("track-list/count").toInt()
for (i in count - 1 downTo 0) {
    val type = player.getPropertyString("track-list/$i/type")
    val external = player.getPropertyString("track-list/$i/external") == "true"
    if (type == "sub" && external) {
        val id = player.getPropertyString("track-list/$i/id")?.toIntOrNull()
        if (id != null) {
            player.setProperty("secondary-sid", id.toString())
            break
        }
    }
}
```

**验证点**：
- 翻译完成后自动挂载双轨
- 译文在底部、原文在顶部
- 切换 DUAL_STACKED 模式后重新挂载合并 SRT

### Step 3：AI 字幕入口面板

**新建文件**：`AiSubtitleSheet.kt`（ModalBottomSheet）

**替换**：播放器面板中 GLOBE 按钮的 onClick（当前直接启动翻译，改为弹出面板）

**面板内容**：
```
┌─────────────────────────────────┐
│  AI 字幕 / AI Subtitles     ✕   │
├─────────────────────────────────┤
│  🌐 生成翻译字幕（原文+译文）    │  ← doTranslate=true
│  📝 仅生成原文字幕               │  ← doTranslate=false
├─────────────────────────────────┤
│  显示模式                        │
│  ○ 仅译文                        │
│  ● 双语-上下分离                 │  ← RadioGroup
│  ○ 双语-底部堆叠                 │
├─────────────────────────────────┤
│  本视频字幕轨                    │
│  #1 内封中文             主✓     │
│  #3 外挂 wp_xx.srt(译文) 主      │  ← 点击切换主轨
│  #4 外挂 wp_xx_src.srt   次✓    │  ← 点击切换次轨
│  ✕ 删除外挂 #4                  │
└─────────────────────────────────┘
```

**交互**：
- 「生成翻译字幕」→ 检查是否已有任务运行 → 启动 TranslateService（doTranslate=true）
- 「生成原文字幕」→ 同上（doTranslate=false）
- 「显示模式」→ 即时生效（重新挂载字幕轨）
- 轨道列表 → 点击设为主/次轨，长按删除外挂

### Step 4：字幕轨管理

**功能**：
- 查询 `track-list` 过滤 `type=sub` 的轨道
- 每个轨道显示：ID / 语言 / 标题 / 内封 or 外挂
- 点击设为主字幕（`sid`）
- 点击设为次字幕（`secondary-sid`）
- 外挂轨道可删除（`sub-remove <id>` + 删除关联 SRT 文件）

**轨道查询复用**：已有 `queryTracks()` 扩展函数（TrackSelectionSheet.kt），可直接复用或提取到共享位置。

### Step 5：设置页集成

**PlayerSettings 新增**：
- `subtitleDisplayMode: SubtitleDisplayMode`（持久化）
- 设置 → 字幕分类新增「双语显示模式」选择器

**桌面端同步**（后续）：
- DesktopShortcuts / DesktopContextMenu 新增 AI 字幕入口
- 桌面端 TrackSelectionSheet 新增次字幕选择

## 6. mpv 次字幕样式配置

次字幕默认在顶部，用户可能想调整：

| mpv 属性 | 作用 | 默认值 |
|----------|------|--------|
| `secondary-sub-visibility` | 是否显示次字幕 | `yes`（设置了 secondary-sid 后） |
| `secondary-sub-pos` | 次字幕垂直位置（0=顶, 100=底） | `0`（顶部） |
| `secondary-sub-ass-override` | ASS 样式覆盖 | `no` |
| `secondary-sub-ass-scale` | 次字幕缩放 | 与主字幕一致 |
| `secondary-sub-color` | 次字幕文字颜色 | 与主字幕一致 |

底部堆叠模式下，次字幕也可以放底部（`secondary-sub-pos=95`），但和主字幕可能重叠。**推荐底部堆叠用合并 SRT 方案**（只有一条轨，mpv 自然渲染两行），而非用 secondary-sid 调位置。

## 7. 文件输出目录结构

```
cacheDir/subtitles/
├── wp_a1b2c3d4.srt           # 译文（DUAL_SEPARATED 主轨 / TRANSLATED_ONLY）
├── wp_a1b2c3d4_source.srt    # 原文（DUAL_SEPARATED 次轨）
├── wp_a1b2c3d4_dual.srt      # 译文+原文堆叠（DUAL_STACKED）
├── wp_e5f6g7h8.srt           # 仅转录模式：只有此文件
└── ...
```

文件名 hash 基于 sourceUrl（视频路径），同一视频重跑翻译覆盖旧文件。

## 8. 文件变更清单

### 新增

| 文件 | 说明 |
|------|------|
| `app-android/.../translate/SubtitleManager.kt` | 已在阶段六十九创建（列出/删除 SRT） |
| `app-android/.../AiSubtitleSheet.kt` | AI 字幕面板（ModalBottomSheet） |

### 修改

| 文件 | 改动 |
|------|------|
| `ui-compose/.../translate/SubtitleSegment.kt` | 新增 `toDualSrtBlock()`（两行堆叠） |
| `ui-compose/.../translate/SubtitleMergeEngine.kt` | 新增 `toDualSrtContent()` |
| `app-android/.../translate/TranslationManager.kt` | writeSrt 改为输出 3 文件（译文/原文/堆叠） |
| `app-android/.../translate/TranslateService.kt` | `pendingSubtitleMount` 类型改为 `SubtitleMountRequest` |
| `app-android/.../MobilePlayerScreen.kt` | 双轨挂载逻辑 + GLOBE 按钮改为弹出 AiSubtitleSheet |
| `ui-compose/.../PlayerSettings.kt` | 新增 `subtitleDisplayMode` 字段 |
| `ui-compose/.../I18n.kt` | 新增 ~10 个键 |
| `app-android/.../MobileSettingsScreen.kt` | 字幕分类新增显示模式选择器 |
| `app-android/.../SettingsHelper.kt` | 持久化新字段 |

### 桌面端（后续同步，不在本计划范围）

| 文件 | 改动 |
|------|------|
| `app-desktop/.../DesktopContextMenu.kt` | 右键菜单新增 AI 字幕入口 |
| `ui-compose/.../TrackSelectionSheet.kt` | 新增次字幕选择列 |

## 9. 风险与限制

### 风险

| 风险 | 影响 | 缓解 |
|------|------|------|
| mpv `secondary-sid` 在 libplayer.so 上未测试 | 次字幕可能不显示 | 先在 Step 2 验证基本功能 |
| 合并 SRT 的两行长度差异大 | 长行遮挡画面 | 限制每行最大字符数，超长截断 |
| 三个 SRT 文件增大缓存占用 | 每个 ~50-200KB × 3 | 可配置不生成不需要的模式 |
| 翻译完成 → 挂载时 track-list 查询竞态 | secondary-sid 可能设错轨道 | 用 `FileLoaded` 事件后查 track-list |

### 不做的事

- **不修改 whisper-android.aar** — 不追求 word-level timestamp（需要重新编译 native）
- **不做 ASS 字幕** — SRT 够用，ASS 生成复杂且 mpv 渲染差异大
- **不自动下载字幕** — 仅本地 ASR 生成，不接 OpenSubtitles 等 API
- **桌面端暂不做** — 桌面端需要 Whisper .dll/.so（阶段六十三标注的后续工作）

## 10. 测试计划

| 步骤 | 测试项 | 预期 |
|------|--------|------|
| Step 1 | 翻译完成后检查 cacheDir/subtitles/ | 存在 3 个 SRT（译文/原文/堆叠） |
| Step 1 | 仅转录完成后 | 存在 1 个 SRT（原文） |
| Step 2 | DUAL_SEPARATED 模式挂载 | 译文底部 + 原文顶部 |
| Step 2 | DUAL_STACKED 模式挂载 | 底部两行堆叠 |
| Step 2 | TRANSLATED_ONLY 模式挂载 | 仅底部译文 |
| Step 3 | 面板切换显示模式 | 即时重新挂载 |
| Step 3 | 生成中再次点击生成 | Toast "任务已在运行" |
| Step 4 | 删除外挂字幕轨 | track-list 更新 + SRT 文件删除 |
| Step 4 | 切换主/次轨 | mpv 立即响应 |
| Step 5 | 设置页切换模式 | 持久化 + 下次播放生效 |

---

## 实施顺序

```
Step 1 (管线输出)  →  Step 2 (双轨挂载)  →  Step 3 (面板 UI)  →  Step 4 (轨道管理)  →  Step 5 (设置集成)
     核心              验证 mpv 支持           UX 改善              完整管理              持久化
```

**每步完成后独立编译验证，确保不破坏现有功能。**
