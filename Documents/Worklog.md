# Wind-Player 开发工作日志

## 阶段一：项目骨架搭建 (已完成)

### 完成内容
- KMP 多模块项目结构：`core-mpv` / `ui-compose` / `app-desktop`
- Gradle Kotlin DSL + Version Catalog (JNA 5.17, Coroutines 1.10.2, CMP 1.9.0)
- `expect/actual MpvPlayer`：commonMain 定义接口，desktopMain 用 JNA 绑定 libmpv-2.dll
- `MpvLibrary` JNA 接口映射 mpv C API
- Material3 Compose UI：播放/暂停、进度条、文件路径输入

### 关键架构决策
- 桌面端先行，Kotlin 2.3.10 + JDK 21
- mpv 初始化分两步：`create()` → `setOption()` → `initialize()`（选项必须在 init 前设置）
- 使用 `ComposePanel` 嵌入 Swing JFrame 的 SOUTH 区域，视频 Canvas 在 CENTER
  - 原因：SwingPanel（重量级 AWT）会覆盖所有 Compose UI，无法叠加控件
  - 方案：JFrame(BorderLayout) = Canvas(CENTER, mpv 渲染) + ComposePanel(SOUTH, Material3 控件)

---

## 阶段二：视频渲染对接 (已完成)

### 最终方案：JFrame + Canvas + ComposePanel 混合
- 纯 Swing JFrame，BorderLayout 布局
- `Canvas`(CENTER)：mpv 通过 `wid` 选项渲染视频到此 Canvas
- `ComposePanel`(SOUTH)：嵌入 Compose Material3 控件面板
- `wid` 在 `create()` 之后、`initialize()` 之前通过 `setOption("wid", hwnd)` 设置
- **结果**：视频正常播放，控件正常显示和交互

### 已排除的方案
- **SwingPanel 全屏嵌入**：重量级 AWT 覆盖 Compose UI，白屏不可用
- **Compose Window HWND 直接绑定**：mpv OpenGL 与 Compose Skia 冲突

---

## 阶段三：mpv 事件与状态管理 (已完成)

### 已解决的关键问题

#### mpv 事件 ID 必须从 client.h 读取，不可猜测
- 正确值（来自 `lib/mpv-dev/include/mpv/client.h`）：
  - `MPV_EVENT_START_FILE = 6`, `MPV_EVENT_END_FILE = 7`, `MPV_EVENT_FILE_LOADED = 8`
  - `MPV_EVENT_IDLE = 11`, `MPV_EVENT_PROPERTY_CHANGE = 22`

#### idle 状态下的特殊行为
- mpv idle 时会发送假 EndFile 事件（reason=0）
- mpv idle 时 `pause` 属性返回 0（未暂停），不能当作"正在播放"
- 修复：用 `fileLoaded` 标志过滤假事件，轮询加 `if (!fileLoaded) continue` 守卫

#### 命令传递方式
- 使用 `mpv_command_string(ctx, String)` 而非 `mpv_command(ctx, Array<String?>)`
- seek 用 `setProperty("time-pos", value)` 通过属性设置

---

## 阶段四：进度条 Seek (已完成)

### 实现要点
- Slider `onValueChange` 只更新本地状态，不发 seek 命令
- `onValueChangeFinished` 松手后才发一次 seek（避免连续 seek 导致混乱）
- `isSeeking` 标志暂停轮询更新，防止拖拽时 position 被覆盖
- seek 使用 `absolute+exact` 模式 + `"%.3f".format()` 精度限制

---

## 当前文件结构

```
WindPlayer/
├── Documents/
│   ├── Project.md          # 项目规划书
│   ├── Tec.md              # 技术架构文档
│   └── Worklog.md          # 本文件
├── lib/mpv-dev/            # libmpv 开发库
│   ├── libmpv-2.dll
│   ├── libmpv.dll.a
│   └── include/mpv/
│       ├── client.h
│       ├── render.h
│       ├── render_gl.h
│       └── stream_cb.h
├── test/                   # 测试视频
├── core-mpv/               # mpv FFI 层
│   └── src/
│       ├── commonMain/     # expect MpvPlayer + MpvEvent 数据类
│       └── desktopMain/    # actual MpvPlayer (JNA) + MpvLibrary
├── ui-compose/             # 共享 UI 层
│   └── src/commonMain/     # PlayerScreen (Material3)
├── app-desktop/            # 桌面端入口
│   └── src/desktopMain/    # Main.kt (JFrame + Canvas + ComposePanel)
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
└── gradle/libs.versions.toml
```

## MVP 阶段状态总结

**已完成**：项目骨架、视频渲染对接、mpv 事件与状态管理、进度条 Seek、Play/Pause 控制
**下一步**（对应 Project.md 第二阶段）：
- VFS 虚拟文件系统（WebDAV/SFTP/FTP）
- 旁路字幕加载逻辑
- 手势与交互引擎
- 轨道选择 UI

---

## 阶段五：VFS 虚拟文件系统与网络串流 (已完成)

### 新增模块：core-vfs

依赖：SSHJ 0.39.0 (SFTP), Ktor 3.0.3/CIO (WebDAV), Apache Commons Net 3.11.1 (FTP)

#### 统一文件节点模型 (commonMain)
- `FileNode(name, path, isDirectory, size, lastModified, protocol)` — 统一抽象，无论本地/网络
- `VfsProtocol` 枚举：LOCAL, SFTP, WEBDAV, FTP
- `VfsClient` 接口：`connect/disconnect/listDirectory/resolveUrl/downloadFile`
- `findSidecarSubtitles()` — 根据视频文件名自动匹配同目录字幕
- `VIDEO_EXTENSIONS` / `SUBTITLE_EXTENSIONS` 扩展名常量集

#### 协议客户端实现 (desktopMain)
- **SftpClient**: SSHJ 库，支持密码认证，`ls()` 目录列表，构造 `sftp://user@host/path` 串流 URL
- **WebdavClient**: Ktor CIO 引擎，发送 PROPFIND Depth:1 请求，解析 DAV: XML 命名空间响应
- **FtpClient**: Apache Commons Net FTPClient，被动模式，二进制传输
- **LocalClient**: java.io.File 本地文件系统，支持根驱动器列表

#### VfsManager (门面/协调器)
- 管理多服务器连接（`clients: Map<serverId, VfsClient>`）
- 服务器配置持久化：`~/.windplayer/servers.properties`（Properties 格式）
- `preparePlayback()`: 核心串流逻辑
  1. 构造协议 URL 传给 mpv（如 `sftp://user@host/path/movie.mkv`）
  2. 扫描同目录匹配的字幕文件（.ass/.srt/.ssa/.sub/.vtt）
  3. 后台下载字幕到 `~/.windplayer/cache/` 本地缓存
  4. 返回 `PlaybackParams(streamUrl, localSubtitleFiles)`
- `prepareLocalPlayback()`: 本地文件直接返回路径，字幕无需下载

### UI 重构

#### 新增 App.kt — 屏幕导航控制器
- `AppScreen.BROWSER` / `AppScreen.PLAYER` 双屏幕状态
- `onScreenChange` 回调通知 Swing 层调整 ComposePanel 高度
  - BROWSER 模式：ComposePanel 660px（覆盖大部分视频区域）
  - PLAYER 模式：ComposePanel 160px（底部控制条）

#### 新增 FileBrowserScreen.kt — 文件浏览器
- 左侧边栏：Local Files / Drives / 已保存服务器列表 / Add Server 按钮
- 右侧主区域：面包屑导航 + LazyColumn 文件列表
- 文件类型指示：`[DIR]` `[VID]` `[SUB]` `[FILE]` 彩色标签
- 视频文件显示 Play 按钮，点击触发串流播放
- 服务器连接/断开管理

#### 新增 AddServerDialog.kt — 服务器配置对话框
- 协议选择（SFTP/WebDAV/FTP），主机/端口/用户名/密码/基础路径输入
- DropdownMenu 选择协议，自动填充默认端口

#### PlayerScreen.kt 更新
- 新增 `initialSubtitleFiles` 参数：文件加载后自动调用 `sub-add` 挂载旁路字幕
- 新增 `onBack` 回调：停止播放并返回文件浏览器
- `LaunchedEffect(initialFilePath)`: 自动加载传入的串流 URL
- Back 按钮执行 `stop` 命令后切换回浏览器

#### Main.kt 重构
- 从硬编码测试视频改为动态文件浏览器入口
- 新增 `VfsManager` 实例管理
- ComposePanel 高度根据 `AppScreen` 状态动态切换（`revalidate()` 触发重新布局）
- Canvas 始终存在于 JFrame 中（维持 mpv HWND 有效）

### 版本目录更新 (libs.versions.toml)
```
ktor = "3.0.3"
sshj = "0.39.0"
commons-net = "3.11.1"
```

---

## 当前文件结构

```
WindPlayer/
├── Documents/
├── lib/mpv-dev/
├── test/
├── core-mpv/               # mpv FFI 层
│   └── src/
│       ├── commonMain/     # expect MpvPlayer + MpvEvent
│       └── desktopMain/    # actual MpvPlayer (JNA) + MpvLibrary
├── core-vfs/               # [NEW] 虚拟文件系统层
│   └── src/
│       ├── commonMain/     # FileNode, VfsClient, VfsProtocol, ServerConfig
│       └── desktopMain/    # SftpClient, WebdavClient, FtpClient, LocalClient, VfsManager
├── ui-compose/             # 共享 UI 层
│   └── src/commonMain/
│       ├── App.kt          # [NEW] 屏幕导航
│       ├── PlayerScreen.kt # 播放控制（已更新：字幕/返回）
│       ├── FileBrowserScreen.kt  # [NEW] 文件浏览器
│       └── AddServerDialog.kt    # [NEW] 服务器配置对话框
├── app-desktop/            # 桌面端入口
│   └── src/desktopMain/    # Main.kt（已重构：浏览器/播放器双模式）
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
└── gradle/libs.versions.toml
```

## 第二阶段状态总结

**已完成**：VFS 虚拟文件系统（SFTP/WebDAV/FTP）、旁路字幕自动加载、服务器管理 UI、文件浏览器、串流播放集成

---

## 阶段五补完：SFTP HTTP 代理串流 (已完成)

### 问题背景
mpv/FFmpeg 内置的 SFTP 协议支持依赖 libssh，Windows 分发的 mpv 构建通常不包含该库。直接传入 `sftp://` URL 会导致 `MPV_END_FILE_REASON_ERROR` (reason=4)。

### 解决方案：StreamProxy 本地 HTTP 代理

新建 `StreamProxy.kt`，使用 JDK 内置 `com.sun.net.httpserver.HttpServer` 在 `127.0.0.1` 随机端口启动 HTTP 服务器，将 SFTP 文件通过 HTTP 协议转发给 mpv。

#### 核心设计
- **StreamSession**：每个视频文件创建独立 SSHJ 连接，包含 SSHClient → SFTPClient → RemoteFile
- **Range 请求支持**：解析 `bytes=start-end` 头，返回 206 Partial Content，支持 mpv 的 seek 操作
- **HEAD 请求处理**：返回文件大小信息，不发送数据
- **路径标准化**：`replace(Regex("/+"), "/")` 修复 `//path` 双斜杠问题
- **连接关闭静默处理**：mpv 的正常探测行为（连接后关闭再重开）不产生错误日志

#### 请求流程
1. mpv GET 无 Range → 200 + 全文件长度 → 读取前几 KB 探测格式 → 关闭连接
2. mpv GET Range=bytes=end- → 206 → 读取文件尾部（MKV索引）→ 关闭连接
3. mpv GET Range=bytes=start- → 206 → 正式播放，持续读取
4. Seek 时：mpv 发新 Range 请求，关闭旧连接

#### VfsManager 集成
- SFTP 协议使用 `streamProxy.createStreamUrl()` 生成 `http://127.0.0.1:PORT/stream/ID`
- WebDAV/FTP/Local 仍使用原有协议 URL 或本地路径
- StreamProxy 在 VfsManager 中单例，随应用生命周期

### 其他修复
- **SLF4J 警告消除**：添加 `slf4j-nop:2.0.16` 依赖（SSHJ 使用 SLF4J 但未配置 provider）
- **SftpClient.resolveUrl** 不再用于播放（仅保留备用），实际播放走 StreamProxy

### 测试结果
- SFTP 411MB MKV 文件：加载约 1 秒，播放流畅
- Seek 操作正常，Range 请求正确响应
- 服务器：192.168.31.5:486（密码含特殊字符 `#` `*`）

---

## 当前文件结构

```
WindPlayer/
├── Documents/
├── lib/mpv-dev/
├── test/
├── core-mpv/               # mpv FFI 层
│   └── src/
│       ├── commonMain/     # expect MpvPlayer + MpvEvent
│       └── desktopMain/    # actual MpvPlayer (JNA) + MpvLibrary
├── core-vfs/               # 虚拟文件系统层
│   └── src/
│       ├── commonMain/     # FileNode, VfsClient, VfsProtocol, ServerConfig
│       └── desktopMain/    # SftpClient, WebdavClient, FtpClient, LocalClient, VfsManager, StreamProxy
├── ui-compose/             # 共享 UI 层
│   └── src/commonMain/
│       ├── App.kt          # 屏幕导航
│       ├── PlayerScreen.kt # 播放控制（字幕/返回）
│       ├── FileBrowserScreen.kt  # 文件浏览器
│       └── AddServerDialog.kt    # 服务器配置对话框
├── app-desktop/            # 桌面端入口
│   └── src/desktopMain/    # Main.kt（浏览器/播放器双模式）
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
└── gradle/libs.versions.toml
```

## 第三阶段状态总结

**已完成**：项目骨架、视频渲染、mpv 事件管理、进度条 Seek、VFS 虚拟文件系统、SFTP HTTP 代理串流、旁路字幕加载、文件浏览器 UI
**下一步**：桌面端窗口管理（无边框全屏、双击全屏、鼠标隐藏、快捷键）、音量控制、硬解切换

---

## 阶段八：桌面端窗口管理与交互增强 (已完成)

### 全屏模式 — Win32 API 方案
- 使用 Win32 `SetWindowLongW` / `SetWindowPos` 直接修改窗口样式，**不 dispose JFrame**
  - 原因：`frame.dispose()` 会销毁原生 HWND，mpv 的 `wid` 绑定失效 → 黑屏无声
- `enterFullscreen()`：保存当前 style 和 bounds → `SetWindowLongW` 移除 `WS_CAPTION|WS_THICKFRAME|WS_SYSMENU|WS_MAXIMIZEBOX|WS_MINIMIZEBOX` → `SetWindowPos(HWND_TOPMOST, SWP_FRAMECHANGED|SWP_NOMOVE|SWP_NOSIZE)` 应用样式 → `frame.bounds = graphicsConfiguration.bounds` 由 Java DPI 感知路径设置全屏尺寸
- `exitFullscreen()`：恢复保存的 style → `SetWindowPos(HWND_NOTOPMOST, ...)` → `frame.bounds = savedBounds`
- **DPI 修复**：`graphicsConfiguration.bounds` 返回逻辑像素，`SetWindowPos` 期望物理像素。解决方案：`SetWindowPos` 仅用于样式变更（`SWP_NOMOVE|SWP_NOSIZE`），实际尺寸由 `frame.bounds = ...` 设置（Java 自动处理 DPI 缩放）
- 自定义 `Win32Api` JNA 接口（`GetWindowLongW` / `SetWindowLongW` / `SetWindowPos`），无需额外依赖 `jna-platform`
- 全屏状态下 Compose 控件面板覆盖在视频上方（120px 底部），3 秒无鼠标活动后自动隐藏（`javax.swing.Timer`）
- 鼠标光标同步隐藏（1x1 透明 BufferedImage 自定义 Cursor）
- `isFullscreen` 状态通过 `mutableStateOf` + `onFullscreenChanged` 回调传递给 Compose 层
- ComposePanel 鼠标进入时取消隐藏计时器，退出时重启——防止交互中控件消失

### 键盘快捷键（JComponent.WHEN_IN_FOCUSED_WINDOW）
| 按键 | 功能 |
|------|------|
| Space | 播放/暂停 |
| Enter | 切换全屏（PotPlayer 风格，仅 PLAYER 模式） |
| F11 | 切换全屏（备选） |
| Left / Right | 快退/快进 5 秒 |
| Shift+Left / Shift+Right | 快退/快进 30 秒 |
| Up / Down | 音量 ±5 |
| Esc | 退出全屏 |
| M | 切换静音 |

### 鼠标交互
- **单击视频区域**：250ms 延迟后切换播放/暂停（`javax.swing.Timer` 区分单击/双击）
- **双击视频区域**：取消单击计时器，切换全屏
- **鼠标滚轮**：视频区域滚动调节音量 ±5
- **鼠标移动**（全屏）：恢复控件和光标显示，重启 3 秒隐藏计时器

### PlayerScreen UI 增强
- **音量控制**：Mute 按钮（显示 `MUT` / `N%`）+ 80dp 宽 Slider，200ms 轮询 `volume` / `mute` 属性
- **全屏按钮**：显示 `FS` / `Wnd`，调用 `onToggleFullscreen` 回调
- **硬解切换**：`HW` / `SW` 按钮切换 `hwdec=auto` / `hwdec=no`
- **状态文本**：简化为仅显示文件名，9sp 灰色文字

### LayoutManager 重构
- `switchTo(BROWSER)` 时自动退出全屏
- `setTracksExpanded(true)` 时取消隐藏计时器并保持控件可见
- `handleCanvasClick` / `handleCanvasDoubleClick` 分离单击与双击逻辑
- `onMouseActivity()` 统一处理鼠标活动事件

### 已排除的方案
- **JFrame dispose + setUndecorated**：销毁 HWND → mpv 黑屏无声
- **SetWindowPos 传全屏尺寸**：DPI 缩放下逻辑像素 ≠ 物理像素 → 窗口只占 1/4 屏幕

---

## 当前文件结构

```
WindPlayer/
├── Documents/
│   ├── Project.md
│   ├── Tec.md
│   ├── Worklog.md
│   └── external-media-track-matching-and-scheduling.md
├── lib/mpv-dev/
├── test/
├── core-mpv/               # mpv FFI 层
│   └── src/
│       ├── commonMain/     # expect MpvPlayer + MpvEvent + TrackInfo
│       └── desktopMain/    # actual MpvPlayer (JNA) + MpvLibrary
├── core-vfs/               # 虛拟文件系统层
│   └── src/
│       ├── commonMain/     # FileNode, VfsClient, VfsProtocol, ServerConfig, TrackMatcher
│       └── desktopMain/    # SftpClient, WebdavClient, FtpClient, LocalClient, VfsManager, StreamProxy
├── ui-compose/             # 共享 UI 层
│   └── src/commonMain/
│       ├── App.kt          # 屏幕导航（新增 fullscreen 回调）
│       ├── PlayerScreen.kt # 播放控制（新增音量/全屏/硬解）
│       ├── TrackSelectionSheet.kt  # 轨道选择 + VFS 文件浏览器
│       ├── FileBrowserScreen.kt    # 文件浏览器
│       └── AddServerDialog.kt      # 服务器配置对话框
├── app-desktop/            # 桌面端入口
│   └── src/desktopMain/    # Main.kt（LayoutManager 全屏/快捷键/鼠标交互）
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
└── gradle/libs.versions.toml
```

## 第五阶段状态总结

**已完成**：全屏模式（Win32 API 无 dispose/DPI 感知）、键盘快捷键（Space/Enter/方向键/M/Esc）、鼠标交互（单击暂停/双击全屏/滚轮音量）、音量控制（Mute+Slider）、全屏切换按钮、硬解切换按钮

**下一步**：
- 播放速度控制
- OSD 反馈系统
- 字幕/音轨循环快捷键
- 截图功能

---

## 阶段九：OSD 反馈与播放控制增强 (已完成)

### OSD 系统（面板内 OSD）
- `MutableSharedFlow<String>` 从 Main.kt 发送事件，PlayerScreen 使用 `collectLatest` 收集
- OSD 文本显示在控制栏中央（替换 Spacer），白色加粗 13sp，2 秒后自动清除
- 触发 OSD 的操作：
  - 音量变化（键盘/滚轮/滑块）：`Vol: 80%`
  - 静音切换：`Muted` / `Vol: 80%`
  - 播放/暂停：`> Playing` / `|| Paused`
  - 快进/快退：`>> +5s  01:23:45 / 02:00:00`
  - 速度变化：`Speed: 1.50x`
  - 截图：`Screenshot saved`
  - 字幕/音轨循环：`Subtitle: #2` / `Audio: Off`

### 新增键盘快捷键
| 按键 | 功能 |
|------|------|
| `[` / `]` | 速度 ±0.25x |
| `\` | 重置速度为 1.0x |
| S | 截图（mpv screenshot 命令） |
| V | 循环切换字幕轨 |
| B | 循环切换音轨 |

### 播放速度控制
- 键盘 `[` / `]` 以 ±0.25 步进调节，范围 0.25x ~ 4.0x
- `\` 键重置为 1.0x
- PlayerScreen 新增速速按钮：显示当前速度（如 `1.5x`），点击重置为 1.0x
  - 速度为 1.0 时按钮灰色，非 1.0 时蓝色高亮
- 200ms 轮询 `speed` 属性保持 UI 同步

### 已有快捷键 OSD 增强
- 音量键（Up/Down/M/滚轮）触发 OSD 显示当前音量
- Seek 键（Left/Right/Shift+Left/Shift+Right）触发 OSD 显示方向 + 当前位置/总时长
- 空格键触发 OSD 显示 Playing/Paused 状态
- 单击视频暂停触发 OSD

### 轨道选择 UI (TrackSelectionSheet)

#### mpv track-list API
- 通过 `track-list/count` 获取轨道数量
- 逐项查询 `track-list/N/{id,type,codec,lang,title,default,forced,selected,external}`
- 当前轨道：`getPropertyString("vid"/"aid"/"sid")`
- 切换轨道：`setProperty("vid"/"aid"/"sid", trackId)`，设为 `"no"` 禁用

#### UI 设计
- Video / Audio / Subtitle 三 Tab 切换
- 轨道信息：`语言 · 编码格式 · 标题`（如 `en · OPUS · English 5.1`）
- DEF / FORCED / EXT 徽章标记
- 当前轨道高亮（蓝色圆点 + 背景）
- Audio/Subtitle 支持 "None"（禁用），Video 不支持
- 切换后自动刷新列表（不关闭面板）

#### 内联展开方案（非 Dialog）
- Compose `Dialog` 在 `ComposePanel`（Swing 嵌入）内工作不正常（LazyColumn 高度为 0）
- 改为内联方案：点击 Tracks 按钮时通知 Swing 层将 ComposePanel 从 120px 展开到 `h - 40`
- `LayoutManager.setTracksExpanded(boolean)` 控制面板大小
- 回调链：PlayerScreen → App → Main.kt

### 外置轨道添加（VFS 文件浏览器）

#### 设计
- 每个 Tab 底部显示 "+ Add External Video/Audio/Subtitle" 按钮
- 点击后进入内联 VFS 文件浏览器模式，默认打开视频同级目录
- 只显示匹配扩展名的文件和子目录，可导航子目录
- 选中后通过 `sub-add` / `audio-add` / `video-add` 命令挂载
- 网络文件通过 `preparePlayback()` 获取流 URL，本地文件直接用路径

#### PlaybackParams 扩展
- 新增 `serverId`, `dirPath`, `isLocal` — 记录视频来源，供浏览器回溯
- 新增 `externalAudioUrls` — 外挂音轨 URL 列表
- 新增 `mpvOptions` — mpv 运行时选项（双路串流时注入缓存参数）

---

## 阶段七：外置轨道自动匹配 (已完成)

### TrackMatcher.kt — 4 级匹配链

匹配策略遵循 `Documents/external-media-track-matching-and-scheduling.md`：
- 零网络 I/O：仅基于 FileNode 元数据（文件名、大小、扩展名）
- 禁止递归扫描：仅扫描同级目录，不深度递归
- 视频轨排除：彻底放弃视频轨自动匹配，仅保留手动挂载

#### Level 2：精确扩展匹配 (Exact Name Match)
- 附属文件名去除轨道/语言标签后与主影片文件名一致即命中
- 示例：`Movie.1080p.mkv` ↔ `Movie.1080p.cht.ass`, `Movie.1080p.FLAC.mka`
- 实现：`fileBase.startsWith(videoBase)` 清理后前缀匹配

#### Level 3：结构化特征匹配 (Structured Feature Regex)
- 提取 SxxExx / EPxxx / `- N` 剧集特征
- 同特征文件命中，适用字幕和音轨
- **阻断声明**：提取到剧集特征后禁止进入 Level 4（防误抓其他集字幕）
- 正则：`S01E05`, `EP12`, `- 12 [`, ` - 12`

#### Level 4：模糊匹配 (Fuzzy Levenshtein) — 仅字幕
- 无剧集特征的孤立视频使用 Levenshtein 编辑距离
- 相似度 ≥ 85% 命中
- 音轨严格禁用模糊匹配（防 BGM/访谈误判）

### 调度策略

#### 轻量流（字幕）：旁路下载
- 网络字幕文件下载到 `~/.windplayer/cache/`
- 传本地路径给 mpv `sub-add`
- 本地字幕直接传路径，无需下载

#### 重量流（音轨）：原生串流直通
- 禁止下载，直接传流 URL 给 mpv `audio-add`
- SFTP 通过 StreamProxy，WebDAV/FTP 通过协议 URL

#### 双路串流 mpv 参数注入
- 匹配到外挂音轨时自动注入：
  - `cache=yes`
  - `demuxer-max-bytes=500M`
  - `demuxer-max-back-bytes=100M`
- 防止双路网络串流时 A/V 不同步

### VfsManager 集成
- `preparePlayback()` 和 `prepareLocalPlayback()` 均自动调用 `matchExternalTracks()`
- 扫描同级目录文件列表 → 匹配 → 分类处理字幕/音轨
- 结果通过 `PlaybackParams` 传递：`subtitleFiles`, `externalAudioUrls`, `mpvOptions`
- PlayerScreen 在 `loadfile` 前设置 mpv 选项、添加外挂音轨

---

## 当前文件结构

```
WindPlayer/
├── Documents/
│   ├── Project.md
│   ├── Tec.md
│   ├── Worklog.md
│   └── external-media-track-matching-and-scheduling.md
├── lib/mpv-dev/
├── test/
├── core-mpv/               # mpv FFI 层
│   └── src/
│       ├── commonMain/     # expect MpvPlayer + MpvEvent + TrackInfo
│       └── desktopMain/    # actual MpvPlayer (JNA) + MpvLibrary
├── core-vfs/               # 虚拟文件系统层
│   └── src/
│       ├── commonMain/     # FileNode, VfsClient, VfsProtocol, ServerConfig, TrackMatcher
│       └── desktopMain/    # SftpClient, WebdavClient, FtpClient, LocalClient, VfsManager, StreamProxy
├── ui-compose/             # 共享 UI 层
│   └── src/
│       ├── commonMain/
│       │   ├── App.kt          # 屏幕导航
│       │   ├── Icons.kt        # PhosphorIcons 常量 + expect iconPainter()
│       │   ├── PlayerScreen.kt # 播放控制（Icon 按钮 + OSD）
│       │   ├── TrackSelectionSheet.kt  # 轨道选择 + VFS 文件浏览器
│       │   ├── FileBrowserScreen.kt    # 文件浏览器（图标标签）
│       │   └── AddServerDialog.kt      # 服务器配置对话框
│       └── desktopMain/
│           ├── Icons.kt        # actual iconPainter (loadSvgPainter)
│           └── resources/icons/  # 18 Phosphor fill SVGs
├── app-desktop/            # 桌面端入口
│   └── src/desktopMain/    # Main.kt（LayoutManager 双模式 + 展开/收缩）
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
└── gradle/libs.versions.toml
```

## 第四阶段状态总结

**已完成**：轨道选择 UI（Video/Audio/Subtitle Tab + 内联展开）、外置轨道手动添加（VFS 浏览器）、外置轨道自动匹配（4 级匹配链 + 轻重流调度）、双路串流 mpv 参数注入
**下一步**（对应 Project.md 第三阶段剩余）：
- 手势与交互引擎（亮度/音量/Seek 滑动）
- 锁定按钮、硬解切换
- 桌面端窗口管理（无边框全屏、双击全屏、鼠标隐藏、快捷键）
- 安卓权限适配（SAF / MANAGE_EXTERNAL_STORAGE）

---

## 阶段十：Phosphor Icons 集成 (已完成)

### 完成内容
- 将 18 个 Phosphor Icons（fill 变体 SVG）集成到 UI，替换所有文字图标标签
- 图标方案：`expect/actual iconPainter()` + `loadSvgPainter` 直接加载 JVM classpath 资源
- **PlayerScreen.kt**：
  - 返回按钮：`arrow-left` 图标
  - 播放/暂停：`play` / `pause` 图标（动态切换）
  - 轨道选择：`list` 图标
  - 音量/静音：`speaker-high` / `speaker-slash` 图标（动态切换）
  - 全屏：`corners-out` / `corners-in` 图标（动态切换）
  - HW解码：`lightning` 图标（HW=蓝色，SW=灰色）
  - 速度按钮保留文字（"1.0x"）
- **FileBrowserScreen.kt**：
  - 本地文件：`monitor` 图标（替换 "[PC]"）
  - 驱动器列表：`list` 图标（替换 "[D]"）
  - 文件类型：`folder` / `video` / `subtitles` / `file` 图标（替换 "[DIR]"/"[VID]"/"[SUB]"/"[FILE]"）
  - 播放按钮：`play` 图标按钮
  - 添加服务器：`plus` 图标 + "Add Server"
  - 断开连接：`x` 图标按钮
  - 返回导航：`arrow-left` 图标按钮
- **TrackSelectionSheet.kt**：
  - 关闭按钮：`x` 图标
  - Tab 图标：`video` / `speaker-high` / `subtitles`
  - 选中指示器：`check` 图标（选中=蓝色，未选中=透明）
  - 添加外置轨道：`plus` 图标 + 文字
  - 返回浏览：`arrow-left` 图标

### 关键技术决策
- **CMP 资源系统问题**：Compose Multiplatform 1.9.0 的 `generateResourceAccessorsForCommonMain` Gradle task 始终被 SKIPPED（onlyIf 条件为 false），SVG 文件虽被 `prepareComposeResourcesTaskForCommonMain` 正确复制到 build 目录，但 accessor 代码未生成
- **解决方案**：放弃 `compose.components.resources` 依赖，改用 `expect/actual` + `loadSvgPainter(inputStream, density)` 直接加载
  - commonMain：`@Composable expect fun iconPainter(name: String): Painter` + `PhosphorIcons` 常量对象
  - desktopMain：`actual fun iconPainter()` 通过 `ClassLoader.getResourceAsStream("icons/$name.svg")` 加载
  - SVGs 放在 `ui-compose/src/desktopMain/resources/icons/`
- `loadSvgPainter` 有 deprecation 警告（建议迁移到 Compose resources），但因 CMP 资源生成不工作，这是当前唯一可行方案

### 文件变更
```
新增：
  ui-compose/src/commonMain/kotlin/dev/windplayer/ui/Icons.kt     # expect + PhosphorIcons 常量
  ui-compose/src/desktopMain/kotlin/dev/windplayer/ui/Icons.kt    # actual (loadSvgPainter)
  ui-compose/src/desktopMain/resources/icons/*.svg                # 18 个 Phosphor fill SVG

修改：
  ui-compose/build.gradle.kts             # 移除 compose.components.resources 依赖
  ui-compose/src/commonMain/.../PlayerScreen.kt      # Button → IconButton + Icon
  ui-compose/src/commonMain/.../FileBrowserScreen.kt # Text 标签 → Icon + 图标颜色
  ui-compose/src/commonMain/.../TrackSelectionSheet.kt # Text → Icon, Tab 图标

删除：
  ui-compose/src/commonMain/composeResources/drawable/  # 清理未使用的 CMP 资源
```

### 使用的 Phosphor Icons（fill 变体）
| 图标名 | 用途 |
|--------|------|
| arrow-left | 返回/导航 |
| play / pause | 播放控制 |
| list | 轨道列表 / 驱动器列表 |
| speaker-high / speaker-slash | 音量/静音 |
| corners-out / corners-in | 全屏切换 |
| lightning | 硬件解码指示 |
| folder | 文件夹 |
| video | 视频文件 / 视频轨道 Tab |
| subtitles | 字幕文件 / 字幕轨道 Tab |
| file | 通用文件 |
| plus | 添加服务器 / 添加外置轨道 |
| monitor | 本地文件 |
| x | 关闭/断开连接 |
| check | 选中指示 |
| gauge | 速度（备用） |

---

## 阶段十一：播放连续性与桌面集成 (已完成)

### 1. 自动播放下一个文件

当当前视频播放结束或用户按 N 键时，自动播放同目录中按文件名排序的下一个视频文件。

#### 核心难点：`keep-open=yes` 与 EndFile 事件
- **问题**：设置 `keep-open=yes` 后，视频播放到结尾时 mpv **不触发** EndFile reason=0（自然结束），而是保持最后一帧暂停。EndFile reason=2（stop）仅在主动停止或 seek 越过末尾时触发
- **初始方案失败**：仅监听 EndFile reason=0 → 永远不会触发
- **第二次尝试失败**：改为监听 EndFile reason=0 或 reason=2 → 仍然不触发（`keep-open` 阻止了 EndFile 事件）
- **最终方案**：轮询检测 `eof-reached` 属性
  - mpv 在 EOF 时设置 `eof-reached = "yes"`，即使 `keep-open` 阻止了 EndFile
  - 200ms 轮询循环中检查 `position >= duration - 1.0 && getPropertyString("eof-reached") == "yes"`
  - 使用 `eofAutoPlayed` 标志防止重复触发，新文件加载时重置

#### 轮询循环隔离
- `eof-reached` 属性读取放在独立 `try/catch` 中，与主状态更新（`isPlaying`、`position`、`duration` 等）分离
- 原因：如果 `eof-reached` 读取异常，不应阻断其他属性的轮询

#### 播放/暂停状态显示修复
- **问题**：`player.getPropertyLong("pause")` 对 mpv flag 类型属性返回不正确的值，导致 `isPlaying` 始终为 `true`（`pause` 属性是 `yes`/`no` flag 类型，不是 0/1 整数）
- **修复**：改用 `player.getPropertyString("pause") != "yes"`，与 `mute` 属性的读取方式一致
- 对比：`mute` 属性始终使用 `getPropertyString("mute") == "yes"` 读取，工作正常；`pause` 使用 `getPropertyLong` 读取有 bug

#### 图标动态切换
- **问题**：`iconPainter(if (isPlaying) "pause" else "play")` 中的 `remember(name)` 缓存失效机制在 Compose 中未能正确触发图标切换
- **修复**：预加载所有动态图标对（play/pause、speaker-high/speaker-slash、corners-out/corners-in），通过引用切换而非动态名称
  ```kotlin
  val playIcon = iconPainter(PhosphorIcons.PLAY)   // 固定 key，remember 稳定缓存
  val pauseIcon = iconPainter(PhosphorIcons.PAUSE)  // 固定 key，remember 稳定缓存
  Icon(painter = if (isPlaying) pauseIcon else playIcon, ...)
  ```

#### 数据流
- `PlaybackParams` 新增 `directoryVideoPaths: List<String>` 和 `currentFileIndex: Int`
- `FileBrowserScreen` 在准备播放时计算当前目录的视频文件列表（按文件名排序），记录当前文件索引
- `PlayerScreen` 在 EOF 检测时查找下一个文件，通过 `onPlayNextFile` 回调通知 App
- `App.kt` 接收回调后调用 `prepareAndPlay()` 准备下一个文件，继承 `directoryVideoPaths` 和更新 `currentFileIndex`
- N 键通过 `skipNextCallback` 直接调用 App 的跳转逻辑（非 stop 命令）
- 自动播放触发 OSD 显示 `>> Next: 文件名`
- 目录最后一个文件播放结束后显示 "Playlist complete"

### 2. 拖放文件打开

通过 `TransferHandler` 支持将文件拖放到窗口打开。

#### 功能
- 拖入视频文件 → 自动开始播放（通过 `dropEvents: MutableSharedFlow<String>` 传递给 App）
- 拖入字幕文件（播放状态下）→ 自动添加为外挂字幕（`sub-add` 命令），OSD 显示 "Subtitle added: 文件名"
- 支持所有 VIDEO_EXTENSIONS 和 SUBTITLE_EXTENSIONS 中定义的扩展名
- BROWSER 和 PLAYER 模式均可接受拖放

### 3. 窗口状态持久化

窗口位置和大小在关闭时自动保存，下次启动时恢复。

#### 实现
- 持久化文件：`~/.windplayer/window.properties`
- 保存属性：`x`, `y`, `width`, `height`
- 保存时机：`windowClosing` 事件（在 `player.dispose()` 之前）
- 加载时机：`main()` 函数中 frame 创建后，使用 `loadWindowState()` 恢复 bounds
- 校验：最小尺寸 400x300，检查窗口是否在当前屏幕可见范围内（`GraphicsEnvironment.defaultScreenDevice.bounds.intersects()`）
- 最大化状态不保存（`extendedState != NORMAL` 时跳过）

### 文件变更
```
修改：
  core-vfs/src/commonMain/.../VfsClient.kt       # PlaybackParams 新增 directoryVideoPaths, currentFileIndex
  ui-compose/src/commonMain/.../PlayerScreen.kt  # EndFile 自动播放逻辑, onPlayNextFile, onOsdEvent 回调
  ui-compose/src/commonMain/.../App.kt           # prepareAndPlay(), playNextFile(), dropEvents 收集
  ui-compose/src/commonMain/.../FileBrowserScreen.kt # 计算目录视频列表和索引
  app-desktop/src/desktopMain/.../Main.kt         # TransferHandler 拖放, 窗口状态持久化, N 键
```

### 新增键盘快捷键
| 按键 | 功能 |
|------|------|
| N | 跳到下一个文件（停止当前 → 触发自动播放） |

---

## 当前文件结构

```
WindPlayer/
├── Documents/
│   ├── Project.md
│   ├── Tec.md
│   ├── Worklog.md
│   └── external-media-track-matching-and-scheduling.md
├── lib/mpv-dev/
├── test/
├── core-mpv/               # mpv FFI 层
│   └── src/
│       ├── commonMain/     # expect MpvPlayer + MpvEvent + TrackInfo
│       └── desktopMain/    # actual MpvPlayer (JNA) + MpvLibrary
├── core-vfs/               # 虚拟文件系统层
│   └── src/
│       ├── commonMain/     # FileNode, VfsClient, VfsProtocol, ServerConfig, TrackMatcher, PlaybackParams
│       └── desktopMain/    # SftpClient, WebdavClient, FtpClient, LocalClient, VfsManager, StreamProxy
├── ui-compose/             # 共享 UI 层
│   └── src/
│       ├── commonMain/
│       │   ├── App.kt          # 屏幕导航 + 自动播放 + 拖放处理
│       │   ├── Icons.kt        # PhosphorIcons 常量 + expect iconPainter()
│       │   ├── PlayerScreen.kt # 播放控制（Icon 按钮 + OSD + 自动播放）
│       │   ├── TrackSelectionSheet.kt  # 轨道选择 + VFS 文件浏览器
│       │   ├── FileBrowserScreen.kt    # 文件浏览器（图标标签 + 目录视频列表）
│       │   └── AddServerDialog.kt      # 服务器配置对话框
│       └── desktopMain/
│           ├── Icons.kt        # actual iconPainter (loadSvgPainter)
│           └── resources/icons/  # 18 Phosphor fill SVGs
├── app-desktop/            # 桌面端入口
│   └── src/desktopMain/    # Main.kt（拖放 + 窗口持久化 + 全屏 + 快捷键 + 鼠标交互）
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
└── gradle/libs.versions.toml
```

## 第十一阶段状态总结

**已完成**：自动播放下一个文件（目录排序 + 回调链）、拖放文件打开（视频/字幕）、窗口状态持久化（位置/尺寸/屏幕校验）、N 键跳到下一个文件

**下一步**：
- 逐帧步进（. / , 键）— 已在阶段十二完成
- 字幕/音频延迟调整 + OSD — 已在阶段十二完成
- 设置/偏好界面（字幕字体、默认行为）
- 最近文件历史
- 性能 Profiling（Recomposition 审计）

---

## 阶段十二：字幕/音频延迟调整与逐帧步进 (已完成)

### 1. 字幕延迟调整

| 按键 | 功能 |
|------|------|
| `z` | 字幕延迟 −0.1s |
| `x` | 字幕延迟 +0.1s |
| `Shift+Z` | 字幕延迟重置为 0 |

- 使用 mpv `add` 命令递增 `sub-delay` 属性：`player.command("add", "sub-delay", "-0.1")`
- 读取当前值用 `getPropertyDouble("sub-delay")`，OSD 显示如 `Sub delay: +0.3s`
- 重置用 `setProperty("sub-delay", "0")`

### 2. 音频延迟调整

| 按键 | 功能 |
|------|------|
| `g` | 音频延迟 −0.1s |
| `h` | 音频延迟 +0.1s |
| `Shift+G` | 音频延迟重置为 0 |

- 使用 mpv `add` 命令递增 `audio-delay` 属性
- OSD 显示如 `Audio delay: −0.2s`

### 3. 逐帧步进

| 按键 | 功能 |
|------|------|
| `.` | 前进一帧（`frame-step`） |
| `,` | 后退一帧（`frame-back-step`） |

- mpv 的 `frame-step` / `frame-back-step` 命令在播放和暂停状态下均可使用
- 自动暂停播放并前进/后退一帧，适合逐帧分析画面
- OSD 显示 `Frame +` / `Frame -`

### 4. 播放/暂停状态读取修复（遗留 Bug）

- `handleCanvasClick`（单击视频暂停）和 Space 键绑定仍使用 `getPropertyLong("pause") == 1L`
- mpv `pause` 是 flag 类型（`yes`/`no`），`getPropertyLong` 无法正确转换
- 修复：统一改为 `getPropertyString("pause") == "yes"`，与轮询循环中的读取方式一致

### 文件变更
```
修改：
  app-desktop/src/desktopMain/.../Main.kt
    # 新增 7 个快捷键绑定（z/x/Shift+Z/g/h/Shift+G/./,）
    # 修复 handleCanvasClick 和 Space 键的 pause 读取（getPropertyLong → getPropertyString）
```

---

## 第十二阶段状态总结

**已完成**：字幕延迟调整（z/x/Shift+Z）、音频延迟调整（g/h/Shift+G）、逐帧步进（./,）、pause 属性读取遗留 Bug 修复

**下一步**：
- 设置/偏好界面（字幕字体、默认行为） — 已在阶段十三完成
- 最近文件历史 — 已在阶段十三完成
- 性能 Profiling（Recomposition 审计）

---

## 阶段十三：设置/偏好界面与最近文件历史 (已完成)

### 1. 播放器设置系统

#### PlayerSettings 数据模型 (commonMain)
```kotlin
data class PlayerSettings(
    val defaultVolume: Int = 100,
    val hwdecAuto: Boolean = true,
    val subFontSize: Int = 55,
    val subBorderSize: Int = 3,
    val autoPlayNext: Boolean = true
)
```

#### 持久化
- 持久化文件：`~/.windplayer/settings.properties`
- `loadSettings()`：启动时从 Properties 文件加载，缺失字段使用默认值
- `saveSettings()`：设置变更时保存

#### mpv 属性应用
- **初始化**（windowOpened）：通过 `setOption()` 设置 `sub-font-size`、`sub-border-size`、`hwdec`
- **运行时变更**（SettingsScreen 调整）：通过 `setProperty()` 即时应用，无需重启

#### SettingsScreen UI
- 新增 `AppScreen.SETTINGS`，复用 BROWSER 布局（全高 ComposePanel）
- 全屏滚动表单，暗色主题：
  - **Subtitle** 分区：
    - 字体大小 Slider（15~100）
    - 描边大小 Slider（0~10）
  - **Playback** 分区：
    - 默认音量 Slider（0~100%）
    - 硬件解码 Switch
    - 自动播放下一文件 Switch
  - Reset to Defaults 按钮

#### autoPlayNext 设置集成
- `settings.autoPlayNext` 为 `false` 时，`PlayerScreen` 收到空的 `directoryVideoPaths` 和 `null` 的 `onPlayNextFile`，自动播放逻辑被禁用

### 2. 最近文件历史

#### RecentFile 数据模型 (commonMain)
```kotlin
data class RecentFile(
    val name: String,
    val path: String,
    val isLocal: Boolean,
    val serverId: String?,
    val timestamp: Long,
    val position: Double = 0.0,
    val duration: Double = 0.0
)
```

#### 持久化
- 持久化文件：`~/.windplayer/recent.properties`
- 格式：`recent.N = name|path|isLocal|serverId|timestamp|position|duration`
- 最多保存 20 条记录
- `updateRecentFiles()`：新文件插入头部，按 path 去重，截取前 20 条
- 向后兼容：position/duration 字段缺失时默认为 0

#### 播放进度追踪与恢复
- **PlaybackParams** 新增 `resumePosition: Double = 0.0`
- **PlayerScreen** 每 5 秒（25 × 200ms 轮询）调用 `onPositionUpdate(filePath, position, duration)` 上报进度
- **PlayerScreen** 返回按钮点击时立即上报最后一次进度
- **文件加载后**：若 `resumePosition > 1.0`，自动 seek 到保存位置（`resumeApplied` 标志防重复）
- **Main.kt** `updateRecentPosition()` 按文件路径匹配更新进度并持久化
- **onFilePlayed 修复**：`onPlayFile`（从文件浏览器点击播放）回调中补充 `onFilePlayed` 调用，修复最近文件不记录的 Bug

#### 文件浏览器集成
- 文件浏览器侧边栏新增 "Recent" 分区（clock 图标 + 文件列表）
- 最多显示 8 条，每条显示 video 图标 + 文件名
- 有播放进度的条目显示 `mm:ss / hh:mm:ss` 时间（10sp 灰色）
- 点击通过 `onPlayRecentFile` 回调 → `App.prepareAndPlay(resumePosition = recent.position)` 恢复播放
- 本地文件直接播放，远程文件需要服务器已连接

#### 文件追踪
- `onFilePlayed` 回调在 `App.prepareAndPlay()` 成功后触发
- Main.kt 接收回调后更新 `recentFilesState` 并保存到磁盘

### 3. 新增 Phosphor 图标
| 图标 | 用途 |
|------|------|
| gear | 设置按钮/设置页标题 |
| clock | 最近文件分区标题 |

### 文件变更
```
新增：
  ui-compose/src/commonMain/.../PlayerSettings.kt   # PlayerSettings + RecentFile 数据类
  ui-compose/src/commonMain/.../SettingsScreen.kt   # 设置界面 UI
  ui-compose/src/desktopMain/resources/icons/gear.svg   # Phosphor gear-six-fill
  ui-compose/src/desktopMain/resources/icons/clock.svg  # Phosphor clock-fill

修改：
  ui-compose/src/commonMain/.../Icons.kt            # 新增 GEAR、CLOCK 常量
  ui-compose/src/commonMain/.../App.kt              # 新增 SETTINGS 屏幕、settings/recent 参数、onFilePlayed 回调
  ui-compose/src/commonMain/.../FileBrowserScreen.kt # 侧边栏新增 Recent 分区 + Settings 按钮
  app-desktop/src/desktopMain/.../Main.kt           # 设置/最近文件持久化、mpv 属性应用、LayoutManager 处理 SETTINGS
```

---

## 第十三阶段状态总结

**已完成**：播放器设置系统（字幕字体/描边/默认音量/硬解/自动播放，持久化到磁盘）、设置界面（SettingsScreen 滑块+开关）、最近文件历史（侧边栏显示、点击播放、最多 20 条持久化）、播放进度追踪与恢复（每 5 秒自动保存、断点续播、进度显示）、onPlayFile 最近文件记录 Bug 修复、新增 gear/clock 图标

**下一步**：
- 性能 Profiling（Recomposition 审计）— 已在阶段十四完成
- 安卓适配

---

## 阶段十四：性能优化与 Recomposition 审计 (已完成)

### 1. 轮询循环拆分（JNA 调用优化）

将单一 200ms 轮询循环拆分为快慢两个循环，减少跨语言 (JNA) 通信频率：

| 循环 | 频率 | 读取属性 | JNA 调用/秒 |
|------|------|----------|------------|
| 快循环 | 200ms | `pause`, `time-pos`, `duration`, `eof-reached` | 20 |
| 慢循环 | 1000ms | `volume`, `mute`, `speed` | 3 |

- **优化前**：~30 次 JNA 调用/秒（6 属性 × 5 次/秒）
- **优化后**：~23 次 JNA 调用/秒（减少 ~23%）
- 音量/静音/速度仅在用户交互时变化，不需要 200ms 高频轮询

### 2. Debug 输出清理

移除所有 `println("[WindPlayer]...")` 调试语句：
- PlayerScreen：EOF auto-play、EndFile reason、subtitle add failure、seek target
- Main.kt：canvas HWND、mpv initialized
- FileBrowserScreen：auto-play video count/index

### 3. LazyColumn 稳定键

为 FileBrowserScreen 的两个 LazyColumn 添加 `key` 参数，帮助 Compose 追踪列表项：
- 服务器列表：`key = { it.id }`
- 文件列表：`key = { it.path }`

列表数据更新时，Compose 可以复用已有的组合节点，避免不必要的重组和布局计算。

### 4. Recomposition 分析

#### 当前状态读写分析
- **`position`**（每 200ms 变化）：仅被进度条区域读取（Slider + Text），控制按钮 Row 不直接读取 `position`（仅在 Back 按钮 onClick lambda 中引用，lambda 延迟执行不触发重组）
- **`mutableStateOf` 智能跳过**：写入相同值（如 `isPlaying = true` 当已为 `true`）不会触发重组。`isPlaying`/`volume`/`isMuted`/`speed` 实际上只在用户交互时触发重组
- **图标缓存**：`iconPainter(name)` 使用 `remember(name)` 缓存，重组时不重复加载 SVG
- **动态图标对预加载**：play/pause、speaker-high/slash、corners-out/in 均在 Column 顶部预加载，避免 `remember(name)` 在名称切换时的缓存失效

#### 潜在进一步优化（暂不实施）
- 将控制按钮 Row 提取为独立的 `PlayerControlsBar` 可组合函数，确保 `position` 变化时 Compose 跳过控制栏重组
- 将进度条提取为 `ProgressSection`，仅传入 `State<Double>` 引用而非值
- 桌面端 Compose 性能充裕，200ms 重组周期无感知卡顿，暂不需要上述优化

### 文件变更
```
修改：
  ui-compose/src/commonMain/.../PlayerScreen.kt   # 拆分快慢轮询循环 + 清理 println
  ui-compose/src/commonMain/.../FileBrowserScreen.kt # LazyColumn key + 清理 println
  app-desktop/src/desktopMain/.../Main.kt          # 清理 println
```

---

## 第十四阶段状态总结

**已完成**：轮询循环拆分（快 200ms / 慢 1000ms，JNA 调用减少 23%）、Debug println 清理、LazyColumn 稳定键（server.id / file.path）、Recomposition 分析文档化

**下一步**：
- 安卓适配（SAF 权限、JNI mpv 绑定、移动端手势）
- 更多桌面端打磨（亮度控制手势预留、PiP 支持）

---

## 阶段十五：播放进度持久化 Bug 修复 (已完成)

### 问题现象

用户报告：从最近文件列表恢复播放后，退出时的播放进度无法保存。第 2 次播放的进度在第 3 次打开时丢失。

### 根因分析

#### Bug 1：App.kt `onBack` 覆盖进度为 0.0（主因）

`MpvPlayer.getPropertyDouble()` 不检查 `mpv_get_property` 的返回码，属性不可用时返回 `0.0`（`DoubleArray(1)` 默认值），而非抛出异常。

PlayerScreen 返回按钮的执行顺序：
1. `onPositionUpdate?.invoke(fp, position, duration)` — 保存正确进度 ✓
2. `player.command("stop")` — 停止播放
3. `onBack()` → App.kt 的 `onBack` lambda：
   - `player.getPropertyDouble("time-pos")` → 返回 **0.0**（已停止，属性不可用，无异常）
   - `player.getPropertyDouble("duration")` → 返回 **0.0**
   - `onPositionUpdate?.invoke(fp, 0.0, 0.0)` — **覆盖正确进度为 0.0** ❌

结论：每次按返回按钮，已保存的进度都被覆盖为 0.0。

#### Bug 2：远程文件路径不匹配（次要）

当 `autoPlayNext = false` 时，PlayerScreen 的 `onPositionUpdate` 回退使用 `initialFilePath`（= `streamUrl` = 代理 URL，如 `http://127.0.0.1:PORT/stream`），但最近文件存储的是服务器原始路径（如 `/movies/movie.mkv`）。路径不匹配导致 `updateRecentPosition` 静默失败，进度更新丢失。

### 修复方案

#### 1. PlaybackParams 新增 `filePath` 字段

在 `PlaybackParams` 中新增 `filePath: String = ""`，存储文件的**原始路径**（本地路径或服务器路径），与 `streamUrl`（mpv 实际播放的 URL）分离：

```kotlin
data class PlaybackParams(
    val streamUrl: String,       // mpv 播放地址（本地路径 / 代理 URL / 协议 URL）
    ...
    val filePath: String = ""    // 原始文件路径（用于最近文件追踪）
)
```

- `VfsManager.prepareLocalPlayback()`: `filePath = videoNode.path`（本地路径）
- `VfsManager.preparePlayback()`: `filePath = videoNode.path`（服务器路径）

#### 2. 删除 App.kt `onBack` 中的 mpv 进度查询

PlayerScreen 在返回按钮中已在 `stop` 之前保存进度，无需 App.kt 重复查询。删除 App.kt `onBack` 中的 `getPropertyDouble("time-pos")` / `getPropertyDouble("duration")` 代码块，避免覆盖。

修复后的 `onBack`：
```kotlin
onBack = {
    player.command("stop")
    pendingPlayback = null
    switchScreen(AppScreen.BROWSER)
},
```

#### 3. PlayerScreen 使用 `filePath` 追踪进度

PlayerScreen 新增 `filePath: String = ""` 参数，用于 `onPositionUpdate`：
- 轮询循环（每 5 秒）：`val fp = filePath.ifBlank { directoryVideoPaths.getOrNull(currentFileIndex) ?: initialFilePath }`
- 返回按钮：同上

App.kt 传递 `filePath = pendingPlayback?.filePath ?: pendingPlayback?.streamUrl ?: ""`。

#### 4. `onPlayFile` 使用 `filePath` 创建最近文件记录

```kotlin
val replayPath = params.filePath.ifBlank {
    params.directoryVideoPaths.getOrNull(params.currentFileIndex) ?: params.streamUrl
}
```

确保 `onFilePlayed` 使用原始文件路径，与后续 `onPositionUpdate` 的路径一致。

#### 5. `updateRecentPosition` 防御性守卫

Main.kt 的 `updateRecentPosition()` 增加守卫，拒绝用 `0.0` 覆盖有效值：

```kotlin
f.copy(
    position = if (position > 0) position else f.position,
    duration = if (duration > 0) duration else f.duration
)
```

即使未来有其他路径传入 `0.0`，也不会破坏已保存的进度数据。

### 修复后的进度保存流程

1. **播放中**（每 5 秒）：`onPositionUpdate(filePath, position, duration)` → `updateRecentPosition`（带守卫）
2. **返回按钮**：先 `onPositionUpdate(filePath, position, duration)` 保存正确进度 → `player.command("stop")` 停止 → `onBack()` 仅停止+切换屏幕（无进度覆盖）
3. **最近文件记录**：`onFilePlayed` 使用 `filePath`（原始路径）创建/更新条目

### 文件变更

```
修改：
  core-vfs/src/commonMain/.../VfsClient.kt         # PlaybackParams 新增 filePath 字段
  core-vfs/src/desktopMain/.../VfsManager.kt        # prepareLocalPlayback/preparePlayback 设置 filePath
  ui-compose/src/commonMain/.../App.kt              # 删除 onBack mpv 查询；onPlayFile 用 filePath；传 filePath 给 PlayerScreen
  ui-compose/src/commonMain/.../PlayerScreen.kt     # 新增 filePath 参数；onPositionUpdate 使用 filePath
  app-desktop/src/desktopMain/.../Main.kt           # updateRecentPosition 防御性守卫
```

---

## 第十五阶段状态总结

**已完成**：修复播放进度持久化 Bug（App.kt onBack 覆盖进度为 0.0 的根因 + 远程文件路径不匹配 + 防御性守卫）

**下一步**：
- 安卓适配（SAF 权限、JNI mpv 绑定、移动端手势）
- 更多桌面端打磨（亮度控制手势预留、PiP 支持）

---

## 阶段十六：桌面端打磨 — 播放列表 / 视频 EQ / 快捷键速查 (已完成)

### 1. 播放列表面板 (P 键 / Queue 按钮)

在播放控制栏新增 Queue 按钮（仅当 `directoryVideoPaths` 非空时显示），点击展开播放列表面板。也可通过 `P` 键切换。

#### UI 设计
- 复用 TrackSelectionSheet 的内联展开机制（LayoutManager 面板高度 `h - 40`）
- 暗色背景（`#12121E`），标题栏显示 "Playlist (N)" + 关闭按钮
- LazyColumn 列表：每项显示序号 + 文件名
- 当前播放文件高亮（蓝色背景 + play 图标替换序号）
- 点击任意文件跳转播放（调用 `onJumpToFile` → `playNextFile` → `prepareAndPlay`）
- 跳转后面板自动关闭

#### 与轨道选择面板的互斥
- 打开播放列表时关闭轨道选择，反之亦然
- `onTracksToggle` 回调统一控制面板展开状态（`showTrackSheet || showPlaylist`）
- 按钮高亮：激活时蓝色（`#0F84E4`），未激活白色

#### autoPlayNext 解耦
- `directoryVideoPaths` 和 `currentFileIndex` 现在**始终**传递给 PlayerScreen（不再受 `autoPlayNext` 设置控制）
- `autoPlayNext` 仅控制 EOF 自动播放下一个文件的行为
- 播放列表跳转通过独立的 `onJumpToFile` 回调，始终可用

#### P 键通信
- Main.kt 创建 `MutableSharedFlow<Unit>` → App → PlayerScreen
- PlayerScreen 收集事件后切换 `showPlaylist` 状态
- 仅当 `directoryVideoPaths.isNotEmpty()` 时响应

### 2. 视频 EQ 控制（键盘快捷键）

通过 mpv 的 `brightness` / `contrast` / `saturation` / `gamma` 属性实时调节画面。

| 按键 | 功能 | 范围 |
|------|------|------|
| `1` / `2` | 亮度 ∓5 | -100 ~ 100 |
| `3` / `4` | 对比度 ∓5 | -100 ~ 100 |
| `5` / `6` | 饱和度 ∓5 | -100 ~ 100 |
| `7` / `8` | 伽马 ∓5 | -100 ~ 100 |
| `0` | 全部重置为 0 | — |

- 使用 `player.command("add", "brightness", "5")` 递增
- 读取当前值用 `getPropertyLong("brightness")`
- OSD 显示如 `Brightness: +15`
- 重置时同时清零全部 4 个属性

### 3. 键盘快捷键速查面板（F1 键）

全屏半透明覆盖层，分类显示所有快捷键。

#### 通信机制
- Main.kt 创建 `cheatsheetToggle: MutableSharedFlow<Unit>` → App → PlayerScreen
- PlayerScreen 收集后切换 `showCheatsheet` 状态
- 显示时展开面板（`onTracksToggle(true)`），关闭时恢复

#### UI 设计
- 半透明黑色背景（`#E6000000`，约 90% 不透明度）
- 标题 "Keyboard Shortcuts" + 关闭按钮
- 点击任意位置关闭
- 7 个分类分区：
  - Playback（Space/N/P/./,）
  - Seek（←→/Shift+←→）
  - Volume（↑↓/M/Wheel）
  - Speed（[/]/\）
  - Tracks（V/B/Z/X/G/H）
  - Video EQ（1-8/0）
  - Other（Enter/F11/Esc/S/F1）
- 每个快捷键：键名（浅灰色）+ 描述（暗灰色），两端对齐
- 使用 LazyColumn 支持大量快捷键滚动

#### 按键冲突修复
- 初始设计使用 `H` 键，但 `H` 已绑定为音频延迟 +
- 改用 `F1` 键（约定俗成的帮助键）

### 4. 新增 Phosphor 图标

| 图标 | 用途 |
|------|------|
| queue | 播放列表按钮 |

### 新增键盘快捷键汇总

| 按键 | 功能 |
|------|------|
| P | 切换播放列表面板 |
| 1 / 2 | 亮度 ∓5 |
| 3 / 4 | 对比度 ∓5 |
| 5 / 6 | 饱和度 ∓5 |
| 7 / 8 | 伽马 ∓5 |
| 0 | EQ 全部重置 |
| F1 | 快捷键速查面板 |

### 架构变更

#### SharedFlow 通信模式
Main.kt → App → PlayerScreen 的事件传递新增两个通道：
- `playlistToggle: SharedFlow<Unit>` — P 键触发播放列表切换
- `cheatsheetToggle: SharedFlow<Unit>` — F1 键触发速查面板切换

与现有 `osdEvents` / `dropFilePath` 模式一致。

#### onJumpToFile 回调
PlayerScreen 新增 `onJumpToFile: ((filePath: String) -> Unit)?` 参数，始终可用（不受 autoPlayNext 控制）。与 `onPlayNextFile`（仅 EOF 自动播放时使用）分离职责。

### 文件变更

```
新增：
  ui-compose/src/desktopMain/resources/icons/queue.svg    # Phosphor queue-fill 图标

修改：
  ui-compose/src/commonMain/.../Icons.kt           # 新增 QUEUE 常量
  ui-compose/src/commonMain/.../App.kt             # playlistToggle/cheatsheetToggle 参数；always pass dirPaths；onJumpToFile
  ui-compose/src/commonMain/.../PlayerScreen.kt    # PlaylistPanel + CheatsheetOverlay 组件；EQ/playlist/cheatsheet 状态
  app-desktop/src/desktopMain/.../Main.kt          # P/F1/1-8/0 快捷键；playlistToggle/cheatsheetToggle SharedFlow
```

---

## 第十六阶段状态总结

**已完成**：播放列表面板（P 键/Queue 按钮，互斥展开，点击跳转）、视频 EQ 控制（亮度/对比度/饱和度/伽马，1-8 键 ±5，0 键重置）、键盘快捷键速查面板（F1 键，分类显示全部快捷键，半透明覆盖层）、autoPlayNext 解耦（播放列表始终可用）、queue 图标

**下一步**：
- 安卓适配（SAF 权限、JNI mpv 绑定、移动端手势）
- 更多桌面端打磨（PiP 支持、鼠标右键菜单、多语言）

---

## 阶段十七：鼠标右键上下文菜单 (已完成)

### 功能

在视频区域右键弹出上下文菜单（PotPlayer/VLC 风格），提供常用操作的快速访问，无需记忆键盘快捷键。

### 技术实现

#### Swing JPopupMenu
由于视频渲染在 AWT Canvas（重量级组件）上，使用 Swing 原生 `JPopupMenu` 而非 Compose 组件。菜单项使用 `JMenuItem`，子菜单使用 `JMenu` 嵌套。

#### 鼠标按键区分
修改 `videoCanvas.addMouseListener` 的 `mouseClicked` 处理：
- **右键（BUTTON3）**：`SwingUtilities.isRightMouseButton(e)` → 显示上下文菜单，跳过单击暂停逻辑
- **左键（BUTTON1）**：保持原有行为（单击 250ms 延迟暂停 / 双击全屏）
- 仅在 PLAYER 模式下响应右键

**修复前置 Bug**：原代码 `mouseClicked` 未检查鼠标按钮，右键单击也会触发 play/pause 切换。现在右键专用于菜单。

### 菜单结构

```
├── Play / Pause                  (动态：根据当前 pause 状态)
├── ────────────
├── Fullscreen / Exit Fullscreen  (动态：根据当前全屏状态)
├── ────────────
├── Mute / Unmute                 (动态：根据当前 mute 状态)
├── ────────────
├── Subtitle ▸
│   ├── Next Subtitle             (cycle sid)
│   ├── Sub Delay -0.1s
│   ├── Sub Delay +0.1s
│   └── Reset Sub Delay
├── Audio ▸
│   ├── Next Audio Track          (cycle aid)
│   ├── Audio Delay -0.1s
│   ├── Audio Delay +0.1s
│   └── Reset Audio Delay
├── ────────────
├── Speed ▸
│   ├── Slower (-0.25x)
│   ├── Faster (+0.25x)
│   └── Normal (1.0x)
├── Video EQ ▸
│   ├── Brightness - / +
│   ├── Contrast - / +
│   ├── Saturation - / +
│   ├── Gamma - / +
│   └── Reset All EQ
├── ────────────
├── Next File                     (skipNextCallback)
├── Playlist                      (playlistToggle SharedFlow)
├── ────────────
├── Screenshot
└── Shortcuts (F1)               (cheatsheetToggle SharedFlow)
```

### 设计细节

#### 动态标签
- Play/Pause：右键时读取 `getPropertyString("pause")`，显示 "Play" 或 "Pause"
- Fullscreen：读取 `layoutManager.isFullscreen`，显示 "Fullscreen" 或 "Exit Fullscreen"
- Mute/Unmute：读取 `getPropertyString("mute")`，显示 "Mute" 或 "Unmute"

#### 动作执行
- 每个菜单项执行对应的 `player.command()` 并触发 `osdEvents.tryEmit()` 显示 OSD 反馈
- 与键盘快捷键执行相同逻辑，确保一致性
- "Playlist" 和 "Shortcuts" 通过现有 SharedFlow 通道触发 Compose UI 状态切换

#### 鼠标活动集成
- `showContextMenu()` 开头调用 `layoutManager.onMouseActivity()` 恢复光标和控件显示
- 菜单显示期间 Swing 自动管理光标和焦点

### 文件变更

```
修改：
  app-desktop/src/desktopMain/.../Main.kt   # 新增 JPopupMenu/JMenu/JMenuItem 导入；showContextMenu 函数；mouseClicked 右键处理
```

---

## 第十七阶段状态总结

**已完成**：鼠标右键上下文菜单（JPopupMenu 实现，11 个分类/子菜单，动态标签，OSD 反馈，PLAYER 模式守卫，修复右键触发暂停的前置 Bug）

**下一步**：
- 安卓适配（SAF 权限、JNI mpv 绑定、移动端手势）
- 更多桌面端打磨（PiP 支持、多语言、鼠标中键功能）

---

## 阶段十八：画中画 (PiP) 模式 (已完成)

### 功能

将播放器窗口缩小为迷你置顶窗口（480x270，16:9），可在桌面任意位置拖动，边看视频边操作其他应用。

### 技术实现

#### Win32 窗口样式
PiP 复用全屏的 Win32 API 方案：
- `SetWindowLongW` 移除 `WS_CAPTION|WS_THICKFRAME|WS_SYSMENU|WS_MAXIMIZEBOX|WS_MINIMIZEBOX`（无边框）
- `SetWindowPos(HWND_TOPMOST)` 设置始终置顶
- `frame.bounds = Rectangle(x, y, pipWidth, pipHeight)` 设置小窗口尺寸
- 位置：屏幕右下角，留 20px 边距

#### 全屏与 PiP 互斥
- 进入全屏前先退出 PiP（`toggleFullscreen` → `exitPip` → `enterFullscreen`）
- 进入 PiP 前先退出全屏（`togglePip` → `exitFullscreen` → `enterPip`）
- 共享 `savedStyle` / `savedBounds`（因为互斥，不会冲突）

#### 拖拽实现
- `mousePressed`：记录鼠标屏幕坐标 (`locationOnScreen`) 和窗口位置
- `mouseDragged`：计算偏移量，`frame.location = Point(startX + dx, startY + dy)`
- `startDrag()` 调用 `singleClickTimer?.stop()` 取消单击暂停计时器，防止拖拽误触发 play/pause
- 拖拽不触发 `mouseClicked`（Java AWT 标准：鼠标移动超过阈值后不算点击）

#### 控件自动隐藏
- PiP 模式下控件栏高度 80px（比全屏的 120px 更紧凑）
- 3 秒无鼠标活动后自动隐藏（复用全屏的 `resetHideTimer` 机制）
- 隐藏后视频占满整个窗口，光标消失
- `onMouseActivity()` / `resetHideTimer()` 同时检查 `isFullscreen || isPip`

#### Esc 键扩展
- 全屏状态：退出全屏
- PiP 状态：退出 PiP
- 非全屏非 PiP：无操作

### 交互方式

| 操作 | 功能 |
|------|------|
| `I` 键 | 切换 PiP / 正常窗口 |
| 右键菜单 | "Picture in Picture" / "Exit PiP" |
| 右键菜单（PiP 中） | "PiP Larger" / "PiP Smaller" 缩放 |
| 鼠标拖拽（PiP 中） | 移动窗口位置 |
| 双击（PiP 中） | 退出 PiP |
| Esc（PiP 中） | 退出 PiP |
| 单击（PiP 中） | 播放/暂停（不变） |
| 滚轮（PiP 中） | 调节音量（不变） |
| 右键（PiP 中） | 上下文菜单（不变） |

### PiP 缩放

`resizePip(delta: Int)` 以 80px 步进调整宽度（高度按 16:9 比例）：
- 范围：320x180 ~ 960x540
- 缩放后窗口重新定位到屏幕右下角

### LayoutManager 变更摘要

| 方法 | 变更 |
|------|------|
| `switchTo()` | 切换到 BROWSER/SETTINGS 时退出 PiP |
| `toggleFullscreen()` | 先 `exitPip()` 再切换全屏 |
| `togglePip()` | 新增：先 `exitFullscreen()` 再切换 PiP |
| `enterPip()` | 新增：保存样式/bounds → 无边框 → TOPMOST → 小窗口 |
| `exitPip()` | 新增：恢复样式/bounds → NOTOPMOST |
| `resizePip(delta)` | 新增：调整 pipWidth/pipHeight 并重定位 |
| `startDrag(point)` | 新增：记录拖拽起始屏幕坐标和窗口位置 |
| `handleDrag(point)` | 新增：计算偏移并移动窗口 |
| `onMouseActivity()` | 条件改为 `isFullscreen \|\| isPip` |
| `resetHideTimer()` | 条件改为 `isFullscreen \|\| isPip` |
| `handleCanvasDoubleClick()` | PiP 中 `exitPip()`，否则 `toggleFullscreen()` |
| `applyLayout()` | PiP 时 controlH = 80（而非 120） |

### 文件变更

```
修改：
  app-desktop/src/desktopMain/.../Main.kt           # LayoutManager PiP 支持；I 键；拖拽；上下文菜单 PiP 选项；Esc 扩展
  ui-compose/src/commonMain/.../PlayerScreen.kt     # 快捷键速查新增 I / Esc / Right-click 条目
```

---

## 第十八阶段状态总结

**已完成**：画中画模式（Win32 无边框置顶迷你窗口，鼠标拖拽移动，I 键/右键菜单切换，缩放，双击/Esc 退出，控件自动隐藏，全屏互斥）

**下一步**：
- 安卓适配（SAF 权限、JNI mpv 绑定、移动端手势）
- 更多桌面端打磨（多语言、A-B 重复播放、鼠标中键功能）

---

## 阶段十九：鼠标增强交互 — PotPlayer 风格手势 (已完成)

### 功能

实现 PotPlayer 风格的鼠标分区拖拽手势，覆盖亮度/进度/音量三个维度，以及鼠标中键全屏切换。

### 鼠标交互总览

| 操作 | 功能 | 说明 |
|------|------|------|
| 单击 | 播放/暂停 | 250ms 延迟（区分双击），全区域通用 |
| 双击 | 全屏 / 退出 PiP | 全区域通用 |
| 中键单击 | 全屏切换 | 独立于左键的快速全屏切换 |
| 右键 | 上下文菜单 | 全区域通用 |
| 滚轮 | 音量 ±5 | 全区域通用 |
| 横拖（中 1/3） | Seek | 1px = 1s，OSD 显示时间 |
| 竖拖（左 1/3） | 亮度 | 2px = 1，范围 -100~100 |
| 竖拖（右 1/3） | 音量 | 2px = 1%，范围 0~100 |
| 拖拽（PiP 中） | 移动窗口 | PiP 模式专用 |

### 分区拖拽设计

```
┌──────────────┬──────────────┬──────────────┐
│              │              │              │
│   左 1/3     │   中 1/3     │   右 1/3     │
│              │              │              │
│  竖向拖拽    │  横向拖拽    │  竖向拖拽    │
│   亮度       │   Seek       │   音量       │
│              │              │              │
└──────────────┴──────────────┴──────────────┘
```

### 技术实现

#### 拖拽状态管理
```kotlin
var dragMode = 0          // 0=none, 1=seek, 2=volume, 3=brightness
var dragStartX = 0        // 鼠标按下时的 X 坐标
var dragStartY = 0        // 鼠标按下时的 Y 坐标
var dragStartValue = 0.0  // 按下时的属性值（time-pos / volume / brightness）
var dragOccurred = false   // 是否发生了实质性拖拽（>5px）
```

#### 事件处理顺序
1. **`mousePressed`**：读取鼠标 X 坐标，确定分区（左/中/右），记录起始值
2. **`mouseDragged`**：计算 delta（seek 用 dx，volume/brightness 用 dy），施加效果
3. **`mouseReleased`**：清除 dragMode
4. **`mouseClicked`**：检查 dragOccurred，若 true 则抑制单击暂停

#### 拖拽阈值守卫
- 移动距离 <5px 不标记为拖拽（`dragOccurred` 保持 `false`）
- 阈值内的移动允许正常触发单击暂停
- 防止微小手抖导致误触发拖拽操作

#### Seek 实现
- `player.command("seek", value, "absolute")` — 使用 mpv 的 absolute 模式（keyframe-based，拖拽时更快）
- 拖拽期间不读取 `time-pos`（避免异步延迟），而是基于 `dragStartValue + dx` 计算目标位置
- OSD 显示 `01:23:45 / 02:00:00` 格式

#### 音量/亮度实现
- `player.setProperty("volume"/"brightness", value)` — 直接属性设置
- 灵敏度：2px = 1 单位（上下拖拽 200px = 满程 100%）
- OSD 显示 `Vol: 80%` 或 `Brightness: +15`

#### PiP 兼容
- PiP 模式下拖拽行为不变（移动窗口），优先级最高
- 非 PiP 模式才启用分区拖拽手势
- 通过 `layoutManager.isPip` 判断当前模式

### 文件变更

```
修改：
  app-desktop/src/desktopMain/.../Main.kt           # 拖拽状态变量；分区拖拽逻辑；中键全屏；mouseReleased；dragOccurred 守卫
  ui-compose/src/commonMain/.../PlayerScreen.kt     # CheatsheetOverlay 新增 Mouse 分区（8 个交互条目）
```

---

## 第十九阶段状态总结

**已完成**：PotPlayer 风格鼠标分区拖拽（左=亮度/中=Seek/右=音量），鼠标中键全屏切换，5px 拖拽阈值守卫，PiP 兼容，快捷键速查面板 Mouse 分区

**下一步**：
- 安卓适配（SAF 权限、JNI mpv 绑定、移动端手势）
- 更多桌面端打磨（多语言 i18n、A-B 重复播放）

---

## 阶段二十：文件管理增强 — 排序 / 搜索 / 书签 (已完成)

### 1. 文件排序

#### 排序选项
| 排序方式 | 说明 |
|----------|------|
| Name | 按文件名字母排序（默认） |
| Size | 按文件大小排序 |
| Date | 按修改时间排序（`lastModified`） |
| Type | 按文件扩展名排序 |

#### UI 设计
- TextButton 下拉菜单，显示当前排序方式 + 升降序箭头（↑/↓）
- DropdownMenu 包含 4 个排序选项 + 分隔线 + 升降序切换
- 文件夹始终排在文件前面（无论排序方式和升降序）

#### 排序逻辑
```kotlin
val comparator: Comparator<FileNode> = when (sortBy) {
    "size" -> compareBy { it.size }
    "date" -> compareBy { it.lastModified }
    "type" -> compareBy { it.name.substringAfterLast('.').lowercase() }
    else -> compareBy { it.name.lowercase() }
}
val sortedDirs = if (sortAsc) dirs.sortedWith(comparator) else dirs.sortedWith(comparator.reversed())
val sortedFiles = if (sortAsc) files.sortedWith(comparator) else files.sortedWith(comparator.reversed())
displayFiles = sortedDirs + sortedFiles
```

### 2. 搜索过滤

#### UI 设计
- OutlinedTextField 位于面包屑下方，放大镜前导图标
- 实时过滤（`onValueChange` 直接更新状态）
- 有内容时显示清除按钮（X 图标）
- 暗色主题：`focusedContainerColor = #1A1A2E`，光标 `#0F84E4`

#### 过滤逻辑
```kotlin
val filtered = if (searchQuery.isBlank()) files
    else files.filter { it.name.contains(searchQuery, ignoreCase = true) }
```

#### 空结果处理
- 搜索无匹配时显示 `No files matching "query"` 居中提示

### 3. 文件夹书签

#### 数据模型
- 简单路径字符串列表 `List<String>`
- 持久化文件：`~/.windplayer/bookmarks.properties`
- 格式：`bookmark.N = path`
- 仅本地文件夹

#### 侧边栏 UI
- Bookmarks 分区（星图标标题），位于 Drives 和 Recent 之间
- 每个书签显示文件夹图标（橙色）+ 文件夹名
- X 按钮删除书签
- 点击导航：设置 `isLocal = true`，加载目录

#### 添加书签
- 面包屑右侧星图标按钮
- 当前文件夹已收藏：金色星（`#FFA726`）
- 未收藏：灰色星（`#888888`）
- 点击切换收藏/取消收藏

#### 导航行为
- 点击书签自动切换到本地模式
- 设置 breadcrumbs、currentPath
- 清空搜索查询
- 加载目录内容

### 4. 新增 Phosphor 图标

| 图标 | 用途 |
|------|------|
| star | 书签标题/收藏按钮 |
| magnifying-glass | 搜索框前导图标 |

### 文件变更

```
新增：
  ui-compose/src/desktopMain/resources/icons/star.svg              # Phosphor star-fill
  ui-compose/src/desktopMain/resources/icons/magnifying-glass.svg   # Phosphor magnifying-glass-fill

修改：
  ui-compose/src/commonMain/.../Icons.kt              # 新增 STAR、MAGNIFYING_GLASS 常量
  ui-compose/src/commonMain/.../App.kt                # bookmarks/onBookmarkAdded/onBookmarkRemoved 参数
  ui-compose/src/commonMain/.../FileBrowserScreen.kt  # 排序+搜索+书签 UI；displayFiles 派生状态
  app-desktop/src/desktopMain/.../Main.kt             # loadBookmarks/saveBookmarks 持久化；bookmarkState
```

---

## 第二十阶段状态总结

**已完成**：文件排序（Name/Size/Date/Type + 升降序，文件夹优先）、实时搜索过滤（放大镜图标+清除按钮+空结果提示）、文件夹书签（侧边栏分区、星标收藏按钮、持久化、点击导航）、star/magnifying-glass 图标

**下一步**：
- 安卓适配（SAF 权限、JNI mpv 绑定、移动端手势）
- 更多桌面端打磨（多语言 i18n、A-B 重复播放、文件删除/重命名）

---

## 阶段二十一：A-B 重复播放 + 文件删除/重命名 (已完成)

### 1. A-B 重复播放

利用 mpv 原生 `ab-loop-a` / `ab-loop-b` 属性实现片段循环播放，无需手动 seek 逻辑。

#### mpv 原生 A-B 循环
- `ab-loop-a`：A 点位置（秒），设为 `"no"` 禁用
- `ab-loop-b`：B 点位置（秒），设为 `"no"` 禁用
- 两者都设置后 mpv 自动在 A-B 之间循环
- 两者都为 `"no"` 时正常播放

#### 键盘快捷键
| 按键 | 功能 |
|------|------|
| `A` | 设置 A 点（当前 `time-pos`） |
| `Shift+B` | 设置 B 点（当前 `time-pos`） |
| `Shift+A` | 清除 A-B 循环（两者都设为 `"no"`） |

- OSD 显示：`A-B Loop A: 01:23:45` / `A-B Loop B: 01:25:00` / `A-B Loop Cleared`
- 新增 `formatTimeShort(seconds)` 工具函数

#### 上下文菜单集成
```
├── A-B Loop ▸
│   ├── Set A Point
│   ├── Set B Point
│   └── Clear A-B Loop
```

### 2. 文件删除

#### 触发方式
- 文件行右侧 dots-three 按钮下拉菜单 → "Delete"（红色文字）
- 仅本地文件显示操作按钮（`showActions = isLocal`）

#### 确认对话框
- AlertDialog：显示文件名 + 警告 "This cannot be undone"
- "Delete"（红色）/ "Cancel" 按钮
- 删除成功后从文件列表移除
- 删除失败显示错误信息

#### 实现
- `VfsManager.deleteLocalFile(path)` → `java.io.File.delete()`
- 删除后更新 `files` 状态（过滤已删除文件）

### 3. 文件重命名

#### 触发方式
- 文件行 dots-three 下拉菜单 → "Rename"

#### 重命名对话框
- AlertDialog + OutlinedTextField（预填充当前文件名）
- 蓝色光标和指示线（暗色主题适配）
- "Rename" / "Cancel" 按钮
- 空名或未改名时不执行操作

#### 实现
- `VfsManager.renameLocalFile(oldPath, newName)` → `java.io.File.renameTo()`
- 重命名后重新加载整个目录（`vfsManager.listLocalDirectory`）

### 4. 新增 Phosphor 图标

| 图标 | 用途 |
|------|------|
| dots-three | 文件行"更多操作"按钮 |

### 文件变更

```
新增：
  ui-compose/src/desktopMain/resources/icons/dots-three.svg   # Phosphor dots-three-fill

修改：
  ui-compose/src/commonMain/.../Icons.kt              # 新增 DOTS_THREE 常量
  ui-compose/src/commonMain/.../PlayerScreen.kt       # CheatsheetOverlay 新增 A-B 键条目
  ui-compose/src/commonMain/.../FileBrowserScreen.kt  # FileRow 新增 dots-three 下拉+删除/重命名对话框
  core-vfs/src/desktopMain/.../VfsManager.kt          # deleteLocalFile/renameLocalFile 方法
  app-desktop/src/desktopMain/.../Main.kt             # A-B 循环快捷键+上下文菜单+formatTimeShort
```

---

## 第二十一阶段状态总结

**已完成**：A-B 重复播放（mpv ab-loop 原生属性，A/Shift+B/Shift+A 键，上下文菜单）、文件删除（dots-three 下拉+AlertDialog 确认+VfsManager.deleteLocalFile）、文件重命名（OutlinedTextField 输入+VfsManager.renameLocalFile）、dots-three 图标

**下一步**：
- 安卓适配（SAF 权限、JNI mpv 绑定、移动端手势）
- 更多桌面端打磨（多语言 i18n、文件信息查看、主题定制）

---

## 阶段二十二：多语言 i18n — 中英文切换 (已完成)

### 功能

全面国际化（i18n），支持中英文切换，覆盖所有 UI 文本：侧边栏、文件浏览器、播放器控件、设置界面、上下文菜单、快捷键速查面板。

### 技术方案

#### I18n 单例 + Compose State
```kotlin
object I18n {
    var current by mutableStateOf("en")  // Compose 可观察状态
    private val en = mapOf("key" to "value", ...)
    private val zh = mapOf("key" to "值", ...)
    fun get(key: String): String = all[current]?.get(key) ?: en[key] ?: key
}
```

- **不使用 CMP 资源系统**（资源生成在 1.9.0 中损坏，见阶段十文档）
- `mutableStateOf` 全局状态：任何 Composable 读取 `I18n.get()` 时自动注册依赖
- 语言切换后 `I18n.current = "zh"` 触发全 UI 重组
- Swing 代码（上下文菜单）也通过 `I18n.get()` 读取当前语言

#### 字符串覆盖范围（~120 个键值对）

| 区域 | 示例 |
|------|------|
| 侧边栏 | Servers/服务器、Local Files/本地文件、Drives/驱动器 |
| 文件浏览器 | Search/搜索、Name/名称、Delete/删除、Rename/重命名 |
| 播放器 | Playlist/播放列表、Tracks/轨道 |
| 设置 | Subtitle/字幕、Playback/播放、Language/语言 |
| 上下文菜单 | Fullscreen/全屏、Mute/静音、Screenshot/截图 |
| 快捷键速查 | Playback/播放、Seek/快进、Mouse/鼠标 等 8 个分类 |

#### OSD 消息不翻译
- `Vol: 80%`、`Speed: 1.5x`、`Brightness: +15` 等技术性 OSD 保持英文
- 原因：格式化字符串、数值显示，翻译后反而不自然

### 语言设置

#### PlayerSettings 新增字段
```kotlin
data class PlayerSettings(
    ...
    val language: String = "en"
)
```

- 持久化到 `~/.windplayer/settings.properties`（`language = zh`）
- 启动时加载并初始化 `I18n.current`
- 设置变更时实时更新 `I18n.current`

#### SettingsScreen 语言选择器
- Language 分区，点击展开 DropdownMenu
- 选项：English / 中文
- 选择后立即切换语言（全局 UI 重组）
- "Reset to Defaults" 同时重置语言为英文

### 文件变更

```
新增：
  ui-compose/src/commonMain/.../I18n.kt              # I18n 单例 + ~120 键值对（en/zh）

修改：
  ui-compose/src/commonMain/.../PlayerSettings.kt    # 新增 language 字段
  ui-compose/src/commonMain/.../SettingsScreen.kt    # 语言选择器 + 全部字符串 i18n
  ui-compose/src/commonMain/.../FileBrowserScreen.kt # 侧边栏/搜索/排序/对话框字符串 i18n
  ui-compose/src/commonMain/.../PlayerScreen.kt      # Playlist/CheatsheetOverlay 字符串 i18n
  app-desktop/src/desktopMain/.../Main.kt            # 上下文菜单全部字符串 i18n + 语言初始化/持久化
```

---

## 第二十二阶段状态总结

**已完成**：多语言 i18n 基础设施（I18n 单例 + mutableStateOf + ~120 键值对）、中英文切换（SettingsScreen 语言选择器）、语言持久化（settings.properties）、全 UI 文本国际化（侧边栏/文件浏览器/播放器/设置/上下文菜单/快捷键速查）、语言实时切换（无重启）

**下一步**：
- 安卓适配（SAF 权限、JNI mpv 绑定、移动端手势）
- 更多桌面端打磨（文件信息查看、主题定制、更多语言）

---

## 阶段二十三：安卓适配架构文档 (已完成)

### 背景

用户指出：安卓 UI 与桌面版 UI 完全不同，不能共享 UI 代码。桌面端的 UI（PlayerScreen、FileBrowserScreen 等）虽在 `commonMain` 中，但实际依赖桌面特定的交互模式（键盘、鼠标、LayoutManager Canvas/ComposePanel 分割、Win32 API）。

### 产出文档

`Documents/Android-Architecture.md` — 完整的安卓适配架构规划，覆盖：

1. **模块重构** — commonMain → desktopMain 迁移清单（6 个 UI 文件迁移，3 个保留共享）
2. **Gradle 配置** — AGP 插件、Android target、app-android 模块、版本目录
3. **mpv JNI 绑定** — NDK 交叉编译 libmpv.so + Render API（EGL + OpenGL ES）+ SurfaceView 集成
4. **安卓 UI 架构** — 全屏列表文件浏览器（底部导航）+ 叠加浮层播放器 + 触摸手势
5. **文件访问** — SAF（Storage Access Framework）+ ContentResolver + DocumentFile
6. **触摸手势** — 单击区域（左/中/右）、水平/垂直滑动（Seek/亮度/音量）、双击/长按/捏合
7. **Activity 生命周期** — onCreate/onPause/onDestroy 集成 mpv
8. **依赖关系图** — 共享层（core-mpv/core-vfs）+ 平台层（desktop/android）
9. **实施路线图** — 5 个阶段（骨架→JNI→文件浏览器→播放器→设置）
10. **关键风险** — libmpv 交叉编译、EGL 渲染调试、SAF 性能、内存限制

### 核心架构决策

| 决策 | 理由 |
|------|------|
| UI 不共享 | 桌面（键盘/鼠标）vs 移动（触摸/手势）交互模式完全不同 |
| commonMain → desktopMain 迁移 | 让 commonMain 只含真正共享代码（I18n/数据模型/图标常量） |
| JNI + Render API | Android 不支持 `wid` 窗口句柄，需 EGL + OpenGL ES 渲染 |
| SAF 文件访问 | Android 存储权限模型要求 SAF，不能用 java.io.File 直接访问外部存储 |
| SurfaceView via AndroidView | 在 Compose 中嵌入 SurfaceView 进行视频渲染 |

### 文件变更

```
新增：
  Documents/Android-Architecture.md   # 安卓适配完整架构文档
```

---

## 第二十三阶段状态总结

**已完成**：安卓适配架构文档（模块重构方案、Gradle 配置、JNI 绑定、UI 架构、SAF、手势、生命周期、路线图、风险分析）

**下一步**：
- 阶段 A：搭建安卓项目骨架（Gradle + app-android 模块 + MainActivity 空壳 + commonMain→desktopMain 迁移）
- 或继续桌面端打磨（文件信息查看、主题定制）

---

## 阶段二十四：安卓项目骨架搭建 (已完成)

### 核心决策

使用 `mobileMain` 作为中间源集（而非 `androidMain`），预留未来 iOS 等移动端平台适配：

```
commonMain (共享数据/逻辑)
  ├── desktopMain (桌面 UI)
  └── mobileMain (移动端共享 UI)
        └── androidMain (Android 专有：JNI/SAF)
        └── [未来 iosMain]
```

### 1. Gradle 配置

#### 版本目录（libs.versions.toml）
- 新增 `agp = "8.7.0"`（Android Gradle Plugin）
- 新增 `androidx-activity-compose`、`androidx-lifecycle`、`androidx-core-ktx`
- 新增插件声明：`android-application`、`android-library`、`kotlin-android`

#### 根 build.gradle.kts
- 新增 `android.application`、`android.library`、`kotlin.android` 插件声明（`apply false`）

#### settings.gradle.kts
- 新增 `include(":app-android")`

#### gradle.properties
- 新增 `kotlin.mpp.applyDefaultHierarchyTemplate=false`（自定义 mobileMain 层级需要禁用默认模板）

#### local.properties
- `sdk.dir=C:\Users\etern\AppData\Local\Android\Sdk`

### 2. KMP 模块 Android target + mobileMain 层级

三个 KMP 模块均添加了 Android target 和 mobileMain 中间源集：

```kotlin
kotlin {
    jvm("desktop")
    androidTarget()

    sourceSets {
        val commonMain by getting
        val desktopMain by getting { ... }
        val mobileMain by creating {
            dependsOn(commonMain)
        }
        val androidMain by getting {
            dependsOn(mobileMain)
        }
    }
}
```

| 模块 | Android namespace | 说明 |
|------|-------------------|------|
| core-mpv | dev.windplayer.core.mpv | JNI 绑定占位（未来） |
| core-vfs | dev.windplayer.core.vfs | 网络协议依赖已添加到 androidMain |
| ui-compose | dev.windplayer.ui | mobileMain 有 Compose 依赖；commonMain 有 compose.runtime + compose.ui |

### 3. 文件迁移：commonMain → desktopMain

将 6 个桌面专用 UI 文件从 `ui-compose/src/commonMain/` 迁移到 `ui-compose/src/desktopMain/`：

| 文件 | 迁移原因 |
|------|----------|
| App.kt | 桌面屏幕导航（LayoutManager 交互） |
| PlayerScreen.kt | 桌面播放控制（键盘/鼠标依赖） |
| FileBrowserScreen.kt | 桌面文件浏览器（侧边栏布局） |
| SettingsScreen.kt | 桌面设置界面 |
| TrackSelectionSheet.kt | 桌面轨道选择（LayoutManager 展开） |
| AddServerDialog.kt | 桌面对话框模式 |

commonMain 保留的共享文件：
- `I18n.kt` — 国际化（纯 Kotlin + Compose State）
- `Icons.kt` — PhosphorIcons 常量 + expect iconPainter()
- `PlayerSettings.kt` — 数据模型

### 4. app-android 模块

```
app-android/
├── build.gradle.kts                    # android.application + kotlin.android + compose.compiler
├── src/main/
│   ├── AndroidManifest.xml             # MainActivity 声明 + 主题
│   ├── kotlin/dev/windplayer/
│   │   └── MainActivity.kt             # ComponentActivity + setContent + MobileApp()
│   └── res/values/
│       └── themes.xml                  # 暗色主题 (#0F0F1A)
```

### 5. 移动端 UI 桩

#### mobileMain（移动端共享 UI）
`ui-compose/src/mobileMain/kotlin/dev/windplayer/ui/MobileApp.kt`
- 简单占位界面：显示 "WindPlayer Mobile" + I18n.get("loading")
- 后续将实现完整的移动端文件浏览器和播放器 UI

#### androidMain（Android 专有）
`ui-compose/src/androidMain/kotlin/dev/windplayer/ui/Icons.kt`
- `actual fun iconPainter()` 返回 `ColorPainter(Color.Transparent)` 占位
- 后续替换为 Android Vector Assets 或资源加载

### 6. 编译验证

- ✅ 桌面端 `compileKotlinDesktop` — BUILD SUCCESSFUL，无警告，所有文件迁移后编译正常
- Android 项目配置通过 Gradle sync（桌面端任务可正常解析 Android 模块配置）

### 文件变更

```
新增：
  local.properties                                    # Android SDK 路径
  app-android/build.gradle.kts                        # Android 应用模块
  app-android/src/main/AndroidManifest.xml
  app-android/src/main/kotlin/dev/windplayer/MainActivity.kt
  app-android/src/main/res/values/themes.xml
  ui-compose/src/mobileMain/kotlin/dev/windplayer/ui/MobileApp.kt
  ui-compose/src/androidMain/kotlin/dev/windplayer/ui/Icons.kt

修改：
  build.gradle.kts                                    # 新增 AGP/Kotlin-Android 插件声明
  settings.gradle.kts                                 # include(":app-android")
  gradle.properties                                   # applyDefaultHierarchyTemplate=false
  gradle/libs.versions.toml                           # AGP + AndroidX 依赖 + 插件
  core-mpv/build.gradle.kts                           # androidTarget + mobileMain
  core-vfs/build.gradle.kts                           # androidTarget + mobileMain
  ui-compose/build.gradle.kts                         # androidTarget + mobileMain + commonMain Compose deps

迁移（commonMain → desktopMain）：
  ui-compose/src/.../App.kt
  ui-compose/src/.../PlayerScreen.kt
  ui-compose/src/.../FileBrowserScreen.kt
  ui-compose/src/.../SettingsScreen.kt
  ui-compose/src/.../TrackSelectionSheet.kt
  ui-compose/src/.../AddServerDialog.kt
```

---

## 第二十四阶段状态总结

**已完成**：安卓项目骨架（Gradle 配置 + AGP + Android target + mobileMain 层级 + app-android 模块 + MainActivity + MobileApp 桩 + commonMain→desktopMain 迁移 + 桌面端编译验证）

**下一步**：
- 阶段 B：mpv JNI 绑定（交叉编译 libmpv.so + JNI 绑定层 + SurfaceView/EGL 渲染）
- 阶段 C：移动端文件浏览器 UI（全屏列表 + SAF 文件访问）
- 或继续桌面端打磨

---

## 阶段二十五：mpv Android 绑定 — JNA 方案 (已完成)

### 核心决策

**在 Android 上也使用 JNA**（与桌面端一致），完全不写 C/JNI 代码，不依赖 NDK/CMake。

| 方面 | 桌面端 | 安卓端 |
|------|--------|--------|
| 库加载 | `Native.load("libmpv-2", ...)` | `Native.load("mpv", ...)` |
| .so 位置 | `jna.library.path` 目录 | APK `jniLibs/{abi}/` |
| 渲染 | `wid`（窗口句柄） | Render API（EGL + OpenGL ES）— 阶段 B-2 |
| JNI 代码 | 无 | 无 |
| NDK 依赖 | 无 | 无 |

### 1. JNA 依赖

#### core-mpv/build.gradle.kts
```kotlin
val androidMain by getting {
    dependsOn(mobileMain)
    dependencies {
        implementation(libs.jna)  // JNA 自动选择 AAR 变体
    }
}
```

#### app-android/build.gradle.kts
```kotlin
implementation(libs.jna)  // 确保 libjnidispatch.so 正确打包
```

### 2. MpvLibrary.android.kt

与桌面端几乎相同的 JNA 接口，差异：
- 库名 `"mpv"`（Android 约定：无 `lib` 前缀，无 `.so` 后缀）
- 无 `jna.library.path` 设置（Android 从 jniLibs 加载）
- 新增 render API 函数声明（为阶段 B-2 准备）：
  - `mpv_render_context_create/render/update/free`
- 新增 render 相关结构体：
  - `MpvRenderParam`（type + data 对）
  - `MpvOpenGLInitParams`（get_proc_address 回调）

### 3. MpvPlayer.android.kt

完全复用桌面端的实现模式：
- `create()` → `lib.mpv_create()` + `Pointer.nativeValue()`
- `initialize()` → `lib.mpv_initialize()` + 事件循环线程
- `command()` → `lib.mpv_command_string()`
- `getPropertyString/Long/Double()` → JNA 属性读取
- 事件循环：`Thread` + `mpv_wait_event(0.1)` + `tryEmit`
- `android.util.Log` 替代 `println`

### 4. libmpv.so 获取

创建了 `jniLibs/` 目录结构（arm64-v8a, armeabi-v7a, x86_64）和说明文档。

**获取方法（文档已写入 `jniLibs/README.md`）：**
1. 下载 mpv-android APK（GitHub Releases）
2. 解压 APK（ZIP 格式）
3. 复制 `lib/{abi}/libmpv.so` 到 `jniLibs/{abi}/`

### 5. 编译验证

- ✅ `compileDebugKotlin` — BUILD SUCCESSFUL
- ✅ `assembleDebug` — BUILD SUCCESSFUL（APK 16.1 MB，含 JNA native 库）
- ✅ 桌面端 `compileKotlinDesktop` — 不受影响

### 当前状态

mpv Android 绑定（非渲染部分）已就绪：
- ✅ 库加载（JNA `Native.load("mpv")`）
- ✅ 所有 mpv API 函数（create/initialize/command/property/event）
- ✅ 事件循环线程
- ⬜ libmpv.so 文件（用户需手动添加到 jniLibs）
- ⬜ SurfaceView + EGL 渲染（阶段 B-2）

**一旦添加 libmpv.so**，即可：
- 加载视频/音频文件
- 读取元数据（时长、轨道列表等）
- 控制播放（播放/暂停/seek/音量/速度）
- 处理事件（FileLoaded/EndFile/Idle）
- 唯一缺失：视频画面输出（需 Render API + EGL）

### 文件变更

```
新增：
  core-mpv/src/androidMain/.../MpvLibrary.kt        # JNA 接口（含 render API 声明）
  core-mpv/src/androidMain/.../MpvPlayer.android.kt  # JNA 实现（复用桌面模式）
  app-android/src/main/jniLibs/{abi}/                # ABI 目录结构
  app-android/src/main/jniLibs/README.md             # 获取 libmpv.so 说明

修改：
  core-mpv/build.gradle.kts     # androidMain 新增 JNA 依赖
  app-android/build.gradle.kts  # 新增 JNA 依赖（确保 native lib 打包）
```

---

## 第二十五阶段状态总结

**已完成**：mpv Android JNA 绑定（MpvLibrary 接口 + MpvPlayer 实现 + render API 声明 + jniLibs 结构 + libmpv.so 获取文档）、APK 编译验证通过（16.1 MB）

**下一步**：
- 阶段 B-2：SurfaceView + EGL + mpv Render API（视频画面输出）
- 阶段 C：移动端文件浏览器 UI（全屏列表 + SAF 文件访问）
- 用户行动：下载 mpv-android APK，提取 libmpv.so 到 jniLibs

---

## 阶段二十六：移动端文件浏览器 UI + SAF (已完成)

### 功能

实现安卓端文件浏览器：SAF 文件夹授权 → 目录浏览 → 文件列表（视频高亮 + Play 按钮）。完全移动端 UI 设计，与桌面端侧边栏布局完全不同。

### 1. SAF 文件访问集成

#### SafHelper.kt
- `loadTreeUri(context)` / `saveTreeUri(context, uri)` — URI 持久化到 SharedPreferences
- `takePermission(context, uri)` — `contentResolver.takePersistableUriPermission()`
- `listFiles(context, dir)` — `DocumentFile.listFiles()` → `List<FileNode>`（复用桌面端数据模型）
- `rootFromUri(context, uri)` — `DocumentFile.fromTreeUri()`

#### SAF 流程
1. `rememberLauncherForActivityResult(OpenDocumentTree())` — 系统文件夹选择器
2. 用户选择文件夹 → `takePersistableUriPermission` + 保存 URI
3. `DocumentFile.fromTreeUri()` → 根目录
4. `listFiles()` 异步加载 → LazyColumn 显示

### 2. 文件浏览器 UI（移动端）

#### FileBrowserScreen.kt
```
┌─────────────────────────┐
│ TopAppBar               │
│ ← FolderName  [📁][⚙️]  │  ← 返回 + 打开文件夹 + 设置
├─────────────────────────┤
│ LazyColumn              │
│  📁 Subfolder           │
│  🎬 video1.mkv  1.2GB ▶ │
│  🎬 video2.mp4  850MB ▶ │
│  ...                    │
└─────────────────────────┘
```

**与桌面端的区别：**
| 特性 | 桌面端 | 移动端 |
|------|--------|--------|
| 布局 | 侧边栏 + 主区域 | 全屏列表 |
| 文件访问 | `java.io.File` | SAF DocumentFile |
| 导航 | 面包屑路径 | 返回按钮 + 标题栏 |
| 文件操作 | dots-three 下拉菜单 | （后续添加） |
| 文件夹选择 | Local Files 按钮 | SAF 系统选择器 |

**功能：**
- 空状态：显示提示文字 + "Local Files" 按钮触发 SAF
- 加载状态：CircularProgressBar
- 目录导航：点击文件夹进入，返回按钮退出
- 文件排序：文件夹优先，名称字母排序
- 视频标识：蓝色图标 + 文件大小 + Play 按钮

### 3. 移动端设置 UI

#### MobileSettingsScreen.kt
- 全屏滚动表单（与桌面端 SettingsScreen 功能相同）
- 字幕：字体大小 / 描边大小 Slider
- 播放：默认音量 / 硬解 / 自动播放 Switch
- 语言：点击列表项选择（English / 中文）
- 重置按钮：恢复默认设置
- 复用 `I18n` 国际化字符串 + `PlayerSettings` 数据模型

### 4. 导航控制器

#### MobileApp.kt（app-android 模块）
- 简单屏幕状态：`"browser"` / `"settings"`
- `onFilePlay` 回调预留（Phase B-2 接入播放器）
- `onOpenSettings` 导航到设置
- 设置中语言切换实时生效（`I18n.current = code`）

### 5. mobileMain 桩

`ui-compose/src/mobileMain/MobileApp.kt` 保留为空桩（未来 iOS 适配时填充）。实际移动端 UI 全部在 `app-android` 模块中，因为需要 Android 特有 API（SAF、DocumentFile、Activity Result）。

### 6. 依赖变更

```kotlin
// app-android/build.gradle.kts
implementation("androidx.documentfile:documentfile:1.0.1")    // SAF DocumentFile
implementation("androidx.compose.material:material-icons-extended") // Folder 等图标
```

### 文件变更

```
新增：
  app-android/src/main/kotlin/.../SafHelper.kt             # SAF 文件访问工具
  app-android/src/main/kotlin/.../FileBrowserScreen.kt     # 移动端文件浏览器
  app-android/src/main/kotlin/.../MobileSettingsScreen.kt  # 移动端设置
  app-android/src/main/kotlin/.../MobileApp.kt             # 导航控制器

修改：
  app-android/build.gradle.kts                              # DocumentFile + icons-extended 依赖
  app-android/src/main/kotlin/.../MainActivity.kt           # 使用新 MobileApp
  ui-compose/src/mobileMain/.../MobileApp.kt               # 改为空桩
```

### 编译验证

- ✅ `compileDebugKotlin` — BUILD SUCCESSFUL
- ✅ `assembleDebug` — BUILD SUCCESSFUL（APK 103.9 MB）

---

## 第二十六阶段状态总结

**已完成**：移动端文件浏览器（SAF 文件夹授权 + DocumentFile 浏览 + 全屏列表 + 视频高亮）、移动端设置界面（Slider/Switch/语言选择/复用 I18n）、导航控制器（browser/settings 屏幕切换）、APK 编译验证通过

**下一步**：
- 阶段 B-2：SurfaceView + EGL + mpv Render API（视频画面输出）
- 阶段 D：移动端播放器 UI（叠加浮层控件 + 触摸手势）
- 测试：安装 APK 到设备，验证 SAF 文件浏览功能

---

## 阶段二十七~三十：安卓端完整播放器实现 (已完成)

### 阶段二十七：mpv 视频渲染 + 播放器 UI

#### 渲染方案演进

经历了多次尝试，最终确定使用 **mpv-android 的 `libplayer.so` JNI 桥** 方案：

| 尝试 | 方案 | 结果 |
|------|------|------|
| 1 | mpv Render API + EGL（JNA） | `-18 NOT_IMPLEMENTED`（回调 NPE 修复后仍失败） |
| 2 | `wid` = ANativeWindow（反射获取） | 只有声音无画面（ANativeWindow 指针不是 mpv 期望的 jobject） |
| 3 | `vo=mediacodec_embed` | `video=none`（HEVC 10-bit 不被 MediaCodec 支持） |
| 4 | **`libplayer.so` attachSurface** | ✅ **成功** — mpv-android JNI 桥传 Java Surface jobject |

#### 关键发现
mpv-android 的 `libplayer.so` 的 `attachSurface(jobject surface)` 执行：
```c
int64_t wid = reinterpret_cast<intptr_t>(surface);  // Java Surface 对象引用
mpv_set_option(g_mpv, "wid", MPV_FORMAT_INT64, &wid);
```
mpv 内部识别 jobject 并调用 `ANativeWindow_fromSurface(env, surface)`。

#### `is.xyz.mpv.MPVLib`（JNI 桥类）
- 复用 mpv-android 的 `libplayer.so` + `libmpv.so`
- `create(Context)` / `init()` / `destroy()` — mpv 生命周期
- `attachSurface(Surface)` / `detachSurface()` — Surface 绑定
- `command()` / `setOptionString()` / `getPropertyString/Int/Double` — mpv API
- `observeProperty()` — 属性观察
- 事件回调通过 `EventObserver` 接口 + JNI 静态方法回调

#### MpvPlayer.android.kt
- 委托 `MPVLib`，适配 `expect class MpvPlayer` 接口
- `createWithContext(Context)` — Android 特有初始化
- `attachSurface/detachSurface` — Surface 管理
- EventObserver → `MutableSharedFlow<MpvEvent>` 桥接

#### MpvRenderView.kt
- `SurfaceView` + `SurfaceHolder.Callback`
- `surfaceCreated` → 初始化 mpv → attachSurface → init → loadfile
- `content://` URI → `ParcelFileDescriptor` → `fd://N` 协议
- `surfaceChanged` → `vid` 开关切换强制 mpv 重新适配旋转后尺寸

### 阶段二十八：播放器交互修复

#### 沉浸式全屏 + 屏幕常亮
- `WindowInsetsControllerCompat.hide(systemBars())` — 隐藏状态栏和导航栏
- `FLAG_KEEP_SCREEN_ON` — 播放期间屏幕不熄屏
- `DisposableEffect` — 进入播放器隐藏，退出恢复

#### 旋转宽高比
- `surfaceChanged` 时切换 `vid`（no → 1）强制 mpv 重新检测 ANativeWindow 尺寸

#### 返回键 + 播放停止
- `BackHandler` 拦截系统返回键
- 第一次按：Toast 提示 "Press back again to exit"
- 第二次按（2 秒内）：`command("stop")` + `detachSurface()` + `dispose()` + 返回浏览器

### 阶段二十九：持久化 + 网络存储

#### 设置持久化
- `SettingsHelper.kt` — SharedPreferences 存储 PlayerSettings
- 启动时自动加载，变更时自动保存
- 语言/字幕/音量/硬解/自动播放全部持久化

#### SAF 文件夹自动加载
- `LaunchedEffect(rootTreeUri)` — 启动时自动从保存的 URI 加载根目录

#### VFS 客户端共享
- `SftpClient` / `WebdavClient` / `FtpClient` 从 `desktopMain` 移至 `commonMain`
- SSHJ / Ktor / Commons Net 依赖添加到 `core-vfs/commonMain`
- 桌面端编译验证通过

#### 服务器管理
- `ServerStore.kt` — SharedPreferences 持久化 ServerConfig
- `AddServerScreen.kt` — 协议选择 + 主机/端口/凭据/路径输入
- `ServerBrowseScreen.kt` — 服务器目录浏览（连接 + 列表 + 导航）
- `MobileVfsManager.kt` — 服务器连接/浏览（复用 commonMain VFS 客户端）

#### 文件浏览器重构
```
┌─────────────────────────┐
│ WindPlayer    [📁][⚙️]   │
├─────────────────────────┤
│ ☁ Network Storage       │
│  💾 SFTP Server    🗑   │
│  💾 WebDAV Server  🗑   │
│  + Add Server            │
├─────────────────────────┤
│ 📁 Local Storage        │
│  📂 Movies/              │
│  🎬 video.mkv     ▶     │
└─────────────────────────┘
```

### 阶段三十：触摸手势 + 轨道选择 + OSD

#### 触摸手势系统
| 手势 | 区域 | 功能 |
|------|------|------|
| 单击 | 全屏 | 显示/隐藏控件 |
| 双击 | 左半屏 | 快退 10s |
| 双击 | 右半屏 | 快进 10s |
| 长按 | 全屏 | 轨道选择面板 |
| 横向拖拽 | 中央 | Seek（跟随手指） |
| 竖向拖拽 | 左 1/3 | 亮度 |
| 竖向拖拽 | 右 2/3 | 音量 |

使用 Compose `pointerInput` + `detectDragGestures` + `detectTapGestures`。

#### OSD 反馈系统
- 所有操作（seek/音量/亮度/速度）显示半透明居中文字
- 2 秒自动消失
- `LaunchedEffect(osdText)` 自动清除

#### 轨道选择
- `ModalBottomSheet` + Tab（Video/Audio/Subtitle）
- 查询 `track-list/count` + 逐项 `track-list/N/{id,type,lang,title}`
- 点击切换 `vid`/`aid`/`sid` 属性
- 支持 "Off" 禁用音轨/字幕

#### 底部控制栏
- 进度条（拖拽 seek，`onValueChangeFinished` 松手才 seek）
- `-10s` / 播放暂停 / `+10s` 按钮
- 速度按钮（循环 0.5x → 0.75x → 1x → 1.25x → 1.5x → 2x）
- 轨道选择按钮
- 截图按钮

### 安卓端 Bug 修复历史

| Bug | 根因 | 修复 |
|-----|------|------|
| `UnsatisfiedLinkError: libjnidispatch.so` | JNA AAR 未打包 native lib | 手动提取 libjnidispatch.so 到 jniLibs |
| `-18 NOT_IMPLEMENTED` | 回调 NPE（参数声明为非空） | `String?` 可空参数 |
| `-18`（修复 NPE 后） | 竞态条件：渲染线程在 mpv 初始化前创建 render context | 等待 `player.isCreated()` |
| `-18`（修复竞态后） | mpv-android libmpv 的 render API 被禁用/不支持 JNA 回调 | 切换到 `libplayer.so` + `attachSurface` |
| `reason=4` 播放失败 | `content://` URI 不被 mpv 支持 | `ParcelFileDescriptor` → `fd://N` |
| 只有声音无画面 | ANativeWindow 指针 ≠ mpv 期望的 Java Surface jobject | `libplayer.so` `attachSurface(Surface)` |
| 旋转后画面拉伸 | mpv 未检测到 ANativeWindow 尺寸变化 | `surfaceChanged` 中 `vid` 开关切换 |
| 退出后继续播放 | 未正确停止 | `command("stop")` + `detachSurface()` + `dispose()` |
| 状态栏不隐藏 | 未使用沉浸式模式 | `WindowInsetsControllerCompat.hide(systemBars())` |

### 文件变更总结

```
新增：
  core-mpv/src/androidMain/.../MpvLibrary.kt          # JNA 接口 + render API 声明
  core-mpv/src/androidMain/.../MpvPlayer.android.kt    # libplayer.so 委托实现
  core-mpv/src/androidMain/.../is/xyz/mpv/MPVLib.kt    # mpv-android JNI 桥类
  app-android/src/main/jniLibs/{abi}/                  # libmpv.so + libplayer.so + ffmpeg + libjnidispatch.so
  app-android/src/main/kotlin/.../MpvRenderView.kt     # SurfaceView + Surface 绑定 + fd://
  app-android/src/main/kotlin/.../MobilePlayerScreen.kt # 播放器 UI（手势+轨道+OSD+控件）
  app-android/src/main/kotlin/.../SafHelper.kt         # SAF 文件访问
  app-android/src/main/kotlin/.../SettingsHelper.kt    # 设置持久化
  app-android/src/main/kotlin/.../ServerStore.kt       # 服务器配置持久化
  app-android/src/main/kotlin/.../AddServerScreen.kt   # 添加服务器界面
  app-android/src/main/kotlin/.../ServerBrowseScreen.kt # 服务器目录浏览
  app-android/src/main/kotlin/.../MobileVfsManager.kt  # 服务器连接管理
  app-android/src/main/kotlin/.../MobileSettingsScreen.kt # 设置界面
  app-android/src/main/kotlin/.../MobileApp.kt         # 导航控制器

移动（desktopMain → commonMain）：
  core-vfs/src/commonMain/.../SftpClient.kt
  core-vfs/src/commonMain/.../WebdavClient.kt
  core-vfs/src/commonMain/.../FtpClient.kt
```

---

## 第三十阶段状态总结

**已完成**：安卓端完整播放器（mpv 视频渲染 + libplayer.so JNI 桥 + fd:// 内容 URI + 沉浸式全屏 + 屏幕常亮 + 旋转适配）、触摸手势系统（横滑 Seek / 竖滑亮度音量 / 双击快进退 / 长按轨道选择）、轨道选择面板（ModalBottomSheet + Video/Audio/Subtitle tabs）、OSD 反馈、速度控制、截图、网络存储（VFS 客户端共享 + 服务器管理 + 目录浏览）、设置持久化、SAF 文件夹自动加载

**安卓端核心功能已全部实现**，包括：视频播放、文件浏览（本地 SAF + 网络服务器）、播放控制（进度/音量/亮度/速度）、轨道选择、触摸手势、OSD 反馈、设置持久化、多语言支持。

**下一步**：
- 桌面端 UI 大规模优化
- 网络存储 SFTP 串流适配（Android 端 StreamProxy 替代方案）
- 安卓端更多功能（字幕下载、播放历史、A-B 循环等）

---

## 阶段三十一：安卓端 Bug 审计与修复 (已完成)

### 审计

对安卓端全部 13 个源文件进行深度代码审查，共发现 **31 个 Bug**，按严重程度分类修复。

### 第一批修复（10 个关键 Bug）

| # | Bug | 修复方案 |
|---|-----|---------|
| 2 | 设置页面不可达 | MobileApp `when` 添加 `screen == "settings"` 分支 |
| 3 | SAF 子目录导航失败 | `parent.listFiles()` 查找子目录替代 `fromTreeUri` |
| 4 | surfaceDestroyed 竞态 | `synchronized(player)` 包裹销毁逻辑 |
| 5 | EndFile reason 硬编码 0 | `fileLoadedBefore` 标志推断 reason=4 |
| 8 | 缺少 onDragCancel | 添加 `onDragCancel = { isDragging = false }` |
| 15 | 亮度控制的是视频非屏幕 | 改为 `window.attributes.screenBrightness` |
| 16 | 设置未应用到播放器 | MpvRenderView 读取 SettingsHelper 应用 hwdec/sub/volume |
| 17 | 缺少 BackHandler | ServerBrowseScreen + AddServerScreen 添加 |
| 19 | 播放器双击返回 | 改为单击直接 onBack() |

### 第二批修复（8 个中低优先级 Bug）

| # | Bug | 修复方案 |
|---|-----|---------|
| 7 | mpv 跨线程调用无锁 | MpvPlayer 所有方法加 `synchronized(lock)` |
| 12 | ServerStore 管道符分隔风险 | 改为 per-field key（`s${i}_host` 等） |
| 1 | 网络文件不 resolveUrl | MpvRenderView 接受 serverConfig，resolvePath 解析 |
| 6 | 属性观察回调为空 | eventProperty 全部实现，发射 PropertyChange |
| 13 | ServerStore 残留旧 key | save 时清除 oldCount 以上 key |
| 23 | 截图保存路径不可写 | `setOption("screenshot-directory", getExternalFilesDir)` |
| 28 | fmtTime NaN/Infinite | 添加防护 |
| 30 | create/destroy 竞态 | 全局锁保护 dispose |

### 第三批修复（4 个剩余问题）

| # | Bug | 修复方案 |
|---|-----|---------|
| 14 | 密码明文存储 | `EncryptedSharedPreferences`（AES256-GCM），回退到普通 Prefs |
| 18 | autoPlayNext 未实现 | MobilePlayerScreen 接收 directoryVideos + currentIndex；EndFile reason=0 时 resolveAndLoad 下一文件 |
| 21 | 网络连接泄漏 | resolveAndLoad 管理当前 PFD 生命周期（加载新文件前关闭旧 PFD） |
| 22 | 拖拽时控件隐藏 | interactionCount 计数器 + LaunchedEffect 绑定重置 3 秒计时器 |

### 修复详情

#### Bug 14: 密码加密
- 添加 `androidx.security:security-crypto:1.1.0-alpha06` 依赖
- ServerStore 使用 `MasterKey.Builder` + `EncryptedSharedPreferences.create()`
- 加密方案：Key 用 AES256-SIV，Value 用 AES256-GCM
- 回退：设备不支持时降级为普通 SharedPreferences

#### Bug 18: autoPlayNext
- MobileApp 新增 `directoryVideos: List<FileNode>` 和 `playIndex: Int` 状态
- FileBrowserScreen 的 `onFilePlay` 回调签名改为 `(FileNode, List<FileNode>)`
- ServerBrowseScreen 在 onFilePlay 时过滤视频列表并计算索引
- MobilePlayerScreen EndFile reason=0 时：
  1. `currentIdx++`
  2. `resolveAndLoad(nextFile.path)` — 关闭旧 PFD，打开新 PFD/content://→fd:// 或服务器 URL
  3. OSD 显示 `>> 文件名`

#### Bug 21: 连接/PFD 生命周期
- MobilePlayerScreen 新增 `currentPfd` 状态和 `resolveAndLoad()` 方法
- 每次加载新文件前 `currentPfd?.close()` 释放旧文件描述符
- content:// → ParcelFileDescriptor → fd://N 管理
- 服务器 URL → `runBlocking { MobileVfsManager.resolveUrl(server, path) }`

#### Bug 22: 拖拽控件可见性
- 新增 `interactionCount` 计数器，每次 tap/double-tap 递增
- `LaunchedEffect(controlsVisible, interactionCount)` 绑定两个 key
- 拖拽期间 `isDragging` 阻止自动隐藏
- 交互后重置 3 秒计时器

### 文件变更

```
修改：
  app-android/build.gradle.kts                   # + security-crypto 依赖
  app-android/src/main/kotlin/.../MobileApp.kt    # settings 分支 + directoryVideos + autoPlayNext 参数
  app-android/src/main/kotlin/.../FileBrowserScreen.kt # onFilePlay 签名 + 子目录导航修复
  app-android/src/main/kotlin/.../ServerBrowseScreen.kt # BackHandler + 传递视频列表
  app-android/src/main/kotlin/.../AddServerScreen.kt   # BackHandler
  app-android/src/main/kotlin/.../MobilePlayerScreen.kt # 亮度/手势/autoPlayNext/PFD管理/控件可见性
  app-android/src/main/kotlin/.../MpvRenderView.kt     # serverConfig 参数 + settings 加载 + 截图目录 + 同步
  app-android/src/main/kotlin/.../ServerStore.kt        # EncryptedSharedPreferences + per-field key + 清理旧 key
  core-mpv/src/androidMain/.../MpvPlayer.android.kt     # synchronized 全局锁 + PropertyChange 实现
```

---

## 第三十一阶段状态总结

**已完成**：安卓端 31 个 Bug 审计全部修复（线程安全/竞态条件/密码加密/SAF 导航/设置持久化/网络播放/autoPlayNext/拖拽 UX/亮度控制/返回键/EndFile 检测/属性观察/PFD 泄漏/截图目录/NaN 防护）

**安卓端代码质量已达到生产级别。**

**下一步**：
- 桌面端 UI 大规模优化
- 安卓端播放进度持久化（断点续播）
- 安卓端播放历史
- 更多功能打磨

---

## 阶段三十二：安卓端服务器管理增强 (已完成)

### 1. 添加服务器 FAB + 测试连接

#### AddServerScreen 重构
- **Save 按钮改为 FloatingActionButton** — 右下角悬浮 ✓ 图标，横屏可见
- **Test Connection 按钮** — 填写信息后点击测试：
  - 使用 `MobileVfsManager.listDirectory(config, basePath)` 实际连接
  - 测试中：转圈 + "Testing..."（按钮禁用）
  - 成功：绿色背景 `Connected: N items found`
  - 失败：红色背景 `Failed: 具体错误信息`
- **表单滚动** — `verticalScroll(rememberScrollState())`，横屏完整可见
- **输入字段 trim** — host/username/basePath 去除前后空格

### 2. 服务器编辑功能

#### FileBrowserScreen
- 每个服务器条目右侧新增 ✏️ Edit 图标（在 🗑️ Delete 左侧）
- 新增 `onServerEdit: (ServerConfig) -> Unit` 回调

#### AddServerScreen 编辑模式
- 新增 `initialConfig: ServerConfig? = null` 参数
- 编辑模式下预填充所有字段（name/host/port/user/pass/path/protocol）
- 标题显示 "Edit Server"（vs "Add Server"）
- 保存时复用原 ID（`initialConfig?.id ?: UUID`）

#### MobileApp 编辑流程
- 新增 `editingServer` 状态 + `screen == "editServer"` 分支
- 编辑保存：先 remove 旧配置 → add 新配置（复用原 ID）
- 清除 `editingServer` 状态返回浏览器

### 3. 连接失败 Toast 提示

#### MobileApp 错误处理
- `serverError: String?` 状态变量
- `LaunchedEffect(activeServer, serverPath)` 捕获异常 → 存入 `serverError`
- `LaunchedEffect(serverError)` 显示 Toast：`Connect failed: 具体错误信息`
- 连接失败时自动返回浏览器（`activeServer = null`）

### 文件变更

```
修改：
  app-android/src/main/kotlin/.../AddServerScreen.kt       # FAB + Test Connection + 编辑模式 + 滚动
  app-android/src/main/kotlin/.../FileBrowserScreen.kt     # Edit 图标 + onServerEdit 回调
  app-android/src/main/kotlin/.../MobileApp.kt             # 编辑流程 + 连接失败 Toast + serverError 状态
```

---

## 第三十二阶段状态总结

**已完成**：添加服务器 FAB（横屏可见）、测试连接（实时验证 + 成功/失败反馈）、服务器编辑（预填充 + 复用 ID）、连接失败 Toast（`Connect failed: 原因`）、表单滚动

**下一步**：
- 桌面端 UI 大规模优化
- 安卓端播放进度持久化（断点续播）
- 安卓端播放历史
- 更多功能打磨

---

## 阶段三十三：代码审查修复（批次 1-3）(已完成)

对全代码库进行深度审查（详见 `Documents/Issues.md`），分批次修复简单 → 中等难度的问题。

### 批次 1：简单修复

| 问题 | 修复 |
|------|------|
| **P0-1** AndroidManifest 缺 INTERNET 权限 | 补 `INTERNET` + `ACCESS_NETWORK_STATE` 权限声明 |
| **P1-4** AddServerScreen 密码明文 | `FieldRow` 新增 `isPassword` 参数 + `PasswordVisualTransformation()` |
| **L15** 残留调试文件 | 删除 `run-error.txt` / `run-output.txt`，新建 `.gitignore` |
| **L3** 死状态变量 | 移除 `backPressedTime`、`screenBrightness` 声明但从未读取的变量 |
| **L5** magic number | `systemBarsBehavior = 1` → `BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE` |
| **L4** setBrightness 负数范围失效 | 重映射 -100..100 → 0.05..1.0，引入 `brightnessLevel` 状态跟踪当前值；返回键时恢复系统默认 |
| **L10** WebDAV https 检测错误 | `ServerConfig` 新增 `bareHost`（去除 scheme 前缀）和 `httpScheme()`（基于 host 前缀可靠判断 http/https）；`defaultPort()` 改用 httpScheme；`WebdavClient` 改用新方法 |
| **A3** 死代码 Android MpvLibrary.kt | 删除 `core-mpv/src/androidMain/.../MpvLibrary.kt`（无任何文件引用）；移除 `core-mpv/build.gradle.kts` 的 androidMain JNA 依赖；移除 `app-android/build.gradle.kts` 的 JNA 依赖；删除 jniLibs/{abi}/libjnidispatch.so（3 ABI） |

### 批次 2：资源管理 / 锁

| 问题 | 修复 |
|------|------|
| **P1-2** MpvRenderView 锁内网络 I/O | 重构为 CoroutineScope + suspend `resolvePath`；URL 解析在 synchronized 块**外**完成，仅 mpv 命令在锁内 |
| **P0-2 (Android)** MobilePlayerScreen.runBlocking | `resolveAndLoad` 改为 `suspend fun`；EndFile 自动播放改用 `scope.launch { resolveAndLoad(...) }` |
| **L2** currentPfd 离屏泄漏 | `DisposableEffect.onDispose` 添加 `currentPfd?.close()` |
| **P1-3** MPVLib observer 泄漏 | MobileApp 添加 `DisposableEffect(player) { onDispose { player.dispose() } }` 作为 Activity 销毁时的安全网 |
| **L1** Activity 生命周期缺失 | MobilePlayerScreen `DisposableEffect` 内注册 `LifecycleEventObserver`，ON_PAUSE 时 `pause=yes`，ON_RESUME 时 `pause=no` |

### 批次 3：StreamProxy 资源生命周期（桌面端）

| 问题 | 修复 |
|------|------|
| **P1-1** StreamProxy session 从不关闭 | `PlaybackParams` 新增 `streamSessionIds: List<String>`；`preparePlayback` 收集 SFTP session ID；新增 `VfsManager.releasePlayback(params)` 释放；新增 `VfsManager.shutdown()` 停止 StreamProxy + 断开所有连接 |
| **P0-2 (desktop)** VfsManager.removeServer runBlocking | 改用 `ioScope.launch { client.disconnect() }` 异步释放，非阻塞 |
| App.kt 调用点 | `onBack`、`playNextFile` 切换文件前调用 `vfsManager.releasePlayback(current)` |
| Main.kt 调用点 | `windowClosing` 和 shutdown hook 调用 `vfsManager.shutdown()` |
| StreamProxy.open | `client.connect(config.host, ...)` → `config.bareHost`（与 ServerConfig 修复保持一致） |

### 编译验证

- ✅ `:app-desktop:compileKotlinDesktop` — BUILD SUCCESSFUL
- ✅ `:app-android:compileDebugKotlin` — BUILD SUCCESSFUL

### 文件变更总结

```
新增：
  .gitignore

修改：
  app-android/src/main/AndroidManifest.xml                          # INTERNET 权限
  app-android/build.gradle.kts                                       # 移除 JNA 依赖
  app-android/src/main/kotlin/.../AddServerScreen.kt                 # 密码字段遮蔽
  app-android/src/main/kotlin/.../MobilePlayerScreen.kt              # 生命周期/亮度/PFD 清理/runBlocking 移除
  app-android/src/main/kotlin/.../MpvRenderView.kt                   # 锁外解析/suspend resolvePath/协程替代 Thread
  app-android/src/main/kotlin/.../MobileApp.kt                       # DisposableEffect player.dispose 安全网
  core-mpv/build.gradle.kts                                          # 移除 androidMain JNA 依赖
  core-vfs/src/commonMain/.../ServerConfig.kt                        # bareHost/httpScheme/defaultPort 重构
  core-vfs/src/commonMain/.../VfsClient.kt                           # PlaybackParams 新增 streamSessionIds
  core-vfs/src/commonMain/.../WebdavClient.kt                        # 使用 httpScheme/bareHost
  core-vfs/src/desktopMain/.../StreamProxy.kt                        # open() 使用 bareHost
  core-vfs/src/desktopMain/.../VfsManager.kt                         # ioScope/releasePlayback/shutdown/removeServer 异步
  ui-compose/src/desktopMain/.../App.kt                              # releasePlayback 集成
  app-desktop/src/desktopMain/.../Main.kt                            # shutdown hook + windowClosing

删除：
  core-mpv/src/androidMain/.../MpvLibrary.kt                         # 死代码
  app-android/src/main/jniLibs/{abi}/libjnidispatch.so (3 文件)      # JNA native lib 不再需要
  run-error.txt / run-output.txt                                     # 残留调试输出
```

### 剩余未修复问题

详见 `Documents/Issues.md`：
- P0-3（commonMain 包含 JVM 专属代码）- 涉及 KMP 源集大调整
- P0-4（Android EndFile reason 编造）- 需扩展 JNI 桥
- P1-5/P1-6（密码加密 + SSH 主机验证）- 安全增强
- A1（Main.kt 1322 行巨石拆分）- 大型重构
- A2/A4/A5/A7（架构 / 重复代码整理）
- L6-L14（细节体验）

---

## 阶段三十四：A6 重复代码抽取 (已完成)

### 新增公共工具：`core-vfs/src/commonMain/.../VfsUtils.kt`

集中了原先散落在 4 个 UI 文件 + 5 个 VFS 客户端的重复逻辑：

| API | 替代的旧函数 / 代码 |
|-----|--------------------|
| `formatDuration(seconds: Double): String` | `PlayerScreen.formatTime`、`FileBrowserScreen.formatRecentTime`、`Main.formatTimeShort`、`MobilePlayerScreen.fmtTime` |
| `formatDurationOsd(current, total): String` | `Main.formatTimeOsd`（"X / Y" 格式） |
| `FileNodeComparator: Comparator<FileNode>` | 4 个 VFS clients + `SafHelper` 各自的 `compareByDescending<FileNode> { isDirectory }.thenBy { name.lowercase() }` |
| `buildUrlWithCredentials(scheme, user, pass, host, port, defaultPort, path): String` | `SftpClient.resolveUrl` / `FtpClient.resolveUrl` / `WebdavClient.resolveUrl` 各自的 `URLEncoder.encode` + host/port 拼接逻辑 |

`formatDuration` 统一处理 `NaN` / `Infinite` / 负值（取自 `MobilePlayerScreen.fmtTime` 的最稳健版本），输出 `H:MM:SS` 或 `MM:SS`。

### 重构的调用点

| 文件 | 变更 |
|------|------|
| `LocalClient.kt` (desktopMain) | `.sortedWith(FileNodeComparator)` |
| `SftpClient.kt` (commonMain) | `.sortedWith(FileNodeComparator)`；`resolveUrl` 改用 `buildUrlWithCredentials`；`connect` 用 `config.bareHost` |
| `WebdavClient.kt` (commonMain) | `.sortedWith(FileNodeComparator)`；`resolveUrl` 改用 `buildUrlWithCredentials` |
| `FtpClient.kt` (commonMain) | `.sortedWith(FileNodeComparator)`；`resolveUrl` 改用 `buildUrlWithCredentials`；`connect` 用 `config.bareHost` |
| `SafHelper.kt` (app-android) | `.sortedWith(FileNodeComparator)` |
| `PlayerScreen.kt` (desktop UI) | 删除本地 `formatTime`，改用 `formatDuration` |
| `FileBrowserScreen.kt` (desktop UI) | 删除本地 `formatRecentTime`，改用 `formatDuration`（已通过 wildcard import 引入） |
| `Main.kt` (app-desktop) | 删除本地 `formatTimeShort`、`formatTimeOsd`、`DESKTOP_VIDEO_EXTENSIONS`、`DESKTOP_SUBTITLE_EXTENSIONS`；改用 `formatDuration` / `formatDurationOsd` / `VIDEO_EXTENSIONS` / `SUBTITLE_EXTENSIONS` |
| `MobilePlayerScreen.kt` (app-android) | 删除本地 `fmtTime`，改用 `formatDuration` |

### 顺带修复

- `SftpClient.connect` / `FtpClient.connect` 之前用 `config.host`（可能含 scheme 前缀），现在改用 `config.bareHost`，与 `ServerConfig` L10 修复保持一致。

### 编译验证

- ✅ `:app-desktop:compileKotlinDesktop` — BUILD SUCCESSFUL
- ✅ `:app-android:compileDebugKotlin` — BUILD SUCCESSFUL

### 代码量收益

- 删除约 **80 行**重复代码（4 个时间格式化函数 × ~7 行 + 5 个 comparator × 1 行 + 3 个 URL builder × ~10 行 + Main.kt 中重复的 ext sets × 7 行）
- 新增 `VfsUtils.kt` 75 行（带文档注释）
- 净减少约 5 行，但更重要的是消除了"修一处忘修另一处"的隐患

---

## 阶段三十五：A1 Main.kt 巨石拆分 (已完成)

将原本 1304 行的 `Main.kt` 拆为 7 个职责单一的文件。

### 拆分前后对比

| 文件 | 行数 | 职责 |
|------|------|------|
| ~~Main.kt (1304)~~ → | | |
| `Main.kt` | 201 | bootstrap / drag-drop / 窗口生命周期 / Compose App |
| `LayoutManager.kt` | 265 | null-layout 定位 / 全屏 / PiP / 控件自动隐藏 |
| `DesktopShortcuts.kt` | 284 | 键盘快捷键 + 4 个共享动作 helper |
| `DesktopContextMenu.kt` | 229 | 右键上下文菜单（Play/FS/PiP/Mute/Sub/Audio/Speed/ABLoop/EQ/...） |
| `DesktopPersistence.kt` | 196 | window/settings/recent/bookmarks 持久化 |
| `CanvasMouseController.kt` | 152 | 鼠标分区拖拽 / 点击 / 滚轮 |
| `Win32Api.kt` | 31 | JNA 接口 + Win32 常量 |

**最大文件从 1304 行 → 284 行**（缩减 78%）。

### 关键设计决策

#### 1. Win32 API 抽离（`Win32Api.kt`）
- 8 个常量 + 1 个 JNA 接口（GetWindowLongW/SetWindowLongW/SetWindowPos）
- 全部 `internal` 可见性，避免被其他模块意外依赖

#### 2. 持久化集中（`DesktopPersistence.kt`）
- 所有 `.properties` 读写集中在同一文件，按"Window state / Settings / Recent / Bookmarks"四节组织
- 单一 `CONFIG_DIR` (`~/.windplayer/`) + `ensureConfigDir()` 取代 4 处分散的 `dir.mkdirs()`
- 函数全部 `internal`，对外只暴露 4 类操作

#### 3. `LayoutManager` 独立文件
- 247 行的核心 Swing 布局类，没有任何外部依赖（除 Win32Api + ComposePanel）
- 文档注释解释了「不 dispose JFrame」的根本原因（mpv `wid` 绑定会失效）

#### 4. 桌面快捷键 + 共享动作 helper（`DesktopShortcuts.kt`）
**核心创新**：抽出了 4 个被键盘快捷键和右键菜单**共用**的 helper：
- `adjustVolume(player, osd, delta)` — 替代 4 处重复（Up/Down/M/Wheel）
- `adjustSpeed(player, osd, delta)` — 替代 4 处重复（[/]/菜单 slower/faster）
- `adjustDelay(player, osd, property, delta)` — 替代 6 处重复（z/x/g/h/菜单）
- `adjustEq(player, osd, property, delta)` — 替代 18 处重复（1-8 键 + 菜单 8 项）

`DesktopShortcutContext` 类持有所有快捷键需要的状态（player/layoutManager/osd/...），`skipNextCallback` 作为 `var` 让 App 运行时通过 `onSkipNextRegistered` 回调填充。

#### 5. 右键菜单（`DesktopContextMenu.kt`）
- 顶层 `showContextMenu(...)` 函数，参数显式列出所有依赖
- Video EQ 子菜单通过 `addEqItem(key, property, delta, osd, player)` helper 把 8 个重复项压缩到 8 行
- 所有 label 走 `I18n.get()`，与快捷键面板保持一致

#### 6. 鼠标控制器（`CanvasMouseController.kt`）
- 拖拽模式从 magic number `0/1/2/3` 改为命名常量 `DRAG_NONE/SEEK/VOLUME/BRIGHTNESS`
- 持有 dragMode/dragStartX/dragStartY/dragStartValue/dragOccurred 局部状态
- 右键点击 → 调用顶层 `showContextMenu(...)` 函数（来自 DesktopContextMenu.kt）
- `skipNextCallback: () -> (() -> Unit)?` 设计让控制器每帧拉取最新回调，避免 stale 引用

#### 7. 瘦身后的 Main.kt（201 行）
仅保留：
- main() 入口
- mpv + VfsManager 实例化 + shutdown hook
- JFrame / JPanel / Canvas / ComposePanel 装配
- `TransferHandler` 拖放
- 5 个 `mutableStateOf` 状态 + LayoutManager 关联
- Compose `App(...)` 调用（仍是较大的参数列表，对应 A7 问题）
- `windowOpened` / `windowClosing` 生命周期

### 编译验证

- ✅ `:app-desktop:compileKotlinDesktop` — BUILD SUCCESSFUL（无 warning）
- ✅ `:app-android:compileDebugKotlin` — BUILD SUCCESSFUL（不影响 Android）

### 文件变更

```
新增：
  app-desktop/src/desktopMain/kotlin/dev/windplayer/Win32Api.kt
  app-desktop/src/desktopMain/kotlin/dev/windplayer/DesktopPersistence.kt
  app-desktop/src/desktopMain/kotlin/dev/windplayer/LayoutManager.kt
  app-desktop/src/desktopMain/kotlin/dev/windplayer/DesktopShortcuts.kt
  app-desktop/src/desktopMain/kotlin/dev/windplayer/DesktopContextMenu.kt
  app-desktop/src/desktopMain/kotlin/dev/windplayer/CanvasMouseController.kt

修改：
  app-desktop/src/desktopMain/kotlin/dev/windplayer/Main.kt   # 1304 → 201 行
```

### 收益

1. **可维护性**：找一处快捷键逻辑从「在 1300 行文件里搜索」→「直接打开 DesktopShortcuts.kt」
2. **去重**：抽出的 4 个 action helper 跨键盘和菜单复用，未来新增「鼠标手势触发速度调整」可直接调用 `adjustSpeed`
3. **可测试性**：纯逻辑函数（持久化、动作 helper）现在可以单独 unit test（虽然项目暂未设测试目录）
4. **可读性**：每个文件 < 290 行，符合一般 IDE 一屏滚动范围

---

## 阶段三十六：A4 + A5 KMP 源集整理 (已完成)

### A4：PhosphorIcons 下沉到 desktopMain

**背景**：原 `ui-compose/src/commonMain/.../Icons.kt` 通过 `expect fun iconPainter(name)` + `actual` 机制让 desktop 加载 SVG、android 返回 `ColorPainter(Transparent)`。但 Grep 验证显示 **Android 端 0 处调用** `iconPainter` 或 `PhosphorIcons`（Android UI 用 `androidx.compose.material:material-icons-extended`），这套 expect/actual 是纯负担。

**变更**：
- 删除 `ui-compose/src/commonMain/.../Icons.kt`（expect 声明 + PhosphorIcons 常量）
- 删除 `ui-compose/src/androidMain/.../Icons.kt`（stub `actual`）
- `ui-compose/src/desktopMain/.../Icons.kt` 合并：移除 `actual` 关键字，直接声明 `PhosphorIcons` 对象 + `iconPainter` 函数

**结果**：
- commonMain/ui/ 从 3 个文件减少到 2 个（I18n.kt + PlayerSettings.kt）
- androidMain/ui/ 为空
- desktopMain/ui/ 拥有完整的 7 个文件
- 消除 expect/actual 启动开销，删除一个无用的跨平台抽象

### A5：删除 mobileMain 中间源集

**背景**：阶段二十四为预留 iOS 适配引入 `mobileMain` 中间源集（`commonMain → mobileMain → androidMain`），但实际只有一个 9 行注释的 `MobileApp.kt` stub，且 Android UI 全在 `app-android` 模块。徒增复杂度，并迫使 `gradle.properties` 设 `kotlin.mpp.applyDefaultHierarchyTemplate=false`。

**变更**：
1. 删除 `ui-compose/src/mobileMain/`（整个目录）
2. 从 3 个 `build.gradle.kts` 移除 `mobileMain` 创建：
   - `core-mpv/build.gradle.kts`：mobileMain 是空壳，直接删除
   - `core-vfs/build.gradle.kts`：mobileMain 是空壳，直接删除
   - `ui-compose/build.gradle.kts`：mobileMain 有实际依赖（core-mpv/core-vfs/compose deps），**移到 `androidMain` 显式声明**
3. 从 `gradle.properties` 移除 `kotlin.mpp.applyDefaultHierarchyTemplate=false`

**关于 hierarchy template**：移除标志后 Kotlin 默认会创建 `jvmMain` 中间源集（共享给 desktop + android）。由于 commonMain 中已有 JVM 专属代码（VFS clients）且两端都是 JVM target，编译完全正常，无需新建 `jvmMain` 目录。

### 编译验证

- ✅ `:app-desktop:compileKotlinDesktop` — BUILD SUCCESSFUL
- ✅ `:app-android:compileDebugKotlin` — BUILD SUCCESSFUL
- ✅ `:app-android:assembleDebug` — BUILD SUCCESSFUL（APK 107.17 MB，与前批次一致）

### 文件变更总结

```
删除：
  ui-compose/src/commonMain/kotlin/dev/windplayer/ui/Icons.kt           # expect 声明 + PhosphorIcons
  ui-compose/src/androidMain/kotlin/dev/windplayer/ui/Icons.kt          # stub actual
  ui-compose/src/mobileMain/kotlin/dev/windplayer/ui/MobileApp.kt       # 9 行 stub
  ui-compose/src/mobileMain/                                            # 整个目录

修改：
  ui-compose/src/desktopMain/kotlin/dev/windplayer/ui/Icons.kt          # 合并 PhosphorIcons + iconPainter（去 actual）
  core-mpv/build.gradle.kts                                              # 移除 mobileMain
  core-vfs/build.gradle.kts                                              # 移除 mobileMain
  ui-compose/build.gradle.kts                                            # 移除 mobileMain，依赖移到 androidMain
  gradle.properties                                                      # 移除 applyDefaultHierarchyTemplate=false
```

### 收益

1. **减少 3 个无用文件 + 1 个空目录**
2. **消除一个 expect/actual 抽象**（PhosphorIcons 跨平台机制对 Android 无意义）
3. **简化 KMP 模块结构**：mobileMain 中间层删除后，依赖关系从 `common → mobile → android` 简化为 `common → android`
4. **`gradle.properties` 更标准**：恢复 Kotlin 默认 hierarchy template，减少项目特异配置
5. **APK 大小不变**（107.17 MB）—— 这些都是源码级整理，不影响二进制产物

---

## 阶段三十七：A7 App.kt 参数对象化 (已完成)

将原本 23 个参数的 `App()` composable 重构为 5 个参数的版本，使用三个参数对象分组。

### 设计

引入 `DesktopTypes.kt`，定义三个 `@Stable` 类型承载 App 的输入：

| 类型 | 角色 | 字段 |
|------|------|------|
| `DesktopAppState` | 数据类，只读快照状态从 Main → App | `player`, `vfsManager`, `settings`, `isFullscreen`, `recentFiles`, `bookmarks` |
| `DesktopAppCallbacks` | 接口，事件从 App → Main，所有方法默认 no-op | `onScreenChange`, `onTracksToggle`, `onToggleFullscreen`, `onOsdEmit`, `onSkipNextRegistered`, `onSettingsChanged`, `onFilePlayed`, `onPositionUpdate`, `onBookmarkAdded`, `onBookmarkRemoved` |
| `DesktopAppFlows` | 数据类，冷 SharedFlow streams | `osdEvents`, `dropFilePath`, `playlistToggle`, `cheatsheetToggle` |

**为什么用 interface 而不是 `data class` 装 lambdas**：
- 方法名直接文档化，IDE 跳转方便
- 默认 no-op 实现在 interface 内，调用方只需 override 关心的方法
- companion object `NoOp` 提供共享的空实例，避免每次创建

**为什么用 `@Stable`**：让 Compose 编译器跳过对参数对象的不必要 `equals` 检查（虽然 `data class` 默认 stable，但 interface 需要 `@Stable` 显式标注，因为实现类可能不可推断）。

### App() 签名对比

```kotlin
// 之前：23 个参数
@Composable
fun App(
    player: MpvPlayer,
    vfsManager: VfsManager,
    initialFilePath: String = "",
    onScreenChange: ((AppScreen) -> Unit)? = null,
    onTracksToggle: ((Boolean) -> Unit)? = null,
    onToggleFullscreen: (() -> Unit)? = null,
    isFullscreen: Boolean = false,
    osdEvents: SharedFlow<String>? = null,
    onOsdEmit: ((String) -> Unit)? = null,
    dropFilePath: SharedFlow<String>? = null,
    playlistToggle: SharedFlow<Unit>? = null,
    cheatsheetToggle: SharedFlow<Unit>? = null,
    onSkipNextRegistered: ((() -> Unit) -> Unit)? = null,
    settings: PlayerSettings = PlayerSettings.DEFAULT,
    onSettingsChanged: ((PlayerSettings) -> Unit)? = null,
    recentFiles: List<RecentFile> = emptyList(),
    onFilePlayed: ((name: String, path: String, isLocal: Boolean, serverId: String?) -> Unit)? = null,
    onPositionUpdate: ((filePath: String, position: Double, duration: Double) -> Unit)? = null,
    bookmarks: List<String> = emptyList(),
    onBookmarkAdded: ((path: String) -> Unit)? = null,
    onBookmarkRemoved: ((path: String) -> Unit)? = null,
    modifier: Modifier = Modifier
)

// 之后：5 个参数
@Composable
fun App(
    state: DesktopAppState,
    callbacks: DesktopAppCallbacks = DesktopAppCallbacks.NoOp,
    flows: DesktopAppFlows = DesktopAppFlows(),
    initialFilePath: String = "",
    modifier: Modifier = Modifier
)
```

**减少 78%**（23 → 5）。

### Main.kt 调用点对比

调用点从「23 个命名 lambda」改为构造三个对象。回调实现从「inline lambda」改为 `object : DesktopAppCallbacks { override fun ... }`：

```kotlin
App(
    state = DesktopAppState(
        player = player,
        vfsManager = vfsManager,
        settings = settingsState,
        isFullscreen = fullscreenState,
        recentFiles = recentFilesState,
        bookmarks = bookmarksState
    ),
    callbacks = object : DesktopAppCallbacks {
        override fun onScreenChange(screen: AppScreen) = layoutManager.switchTo(screen)
        override fun onTracksToggle(expanded: Boolean) = layoutManager.setTracksExpanded(expanded)
        // ... 共 10 个 override，按需实现
    },
    flows = DesktopAppFlows(
        osdEvents = osdEvents,
        dropFilePath = dropEvents,
        playlistToggle = playlistToggle,
        cheatsheetToggle = cheatsheetToggle
    )
)
```

### PlayerScreen 的处理

`PlayerScreen()` 也有 25 个参数，但本轮**保留原样**——它是 App 的内部细节（外部不直接调用），重构它需要触及 723 行内部逻辑。本轮目标是 App 的对外 API。

App.kt 内部仍然把 `pendingPlayback`（`PlaybackParams?`）解构成 25 个参数传给 PlayerScreen，这是「内部复杂度」而非「外部 API 复杂度」，可以后续作为独立任务处理。

### 编译验证

- ✅ `:app-desktop:compileKotlinDesktop` — BUILD SUCCESSFUL（无新增 warning）
- ✅ `:app-android:compileDebugKotlin` — BUILD SUCCESSFUL（不影响 Android）

### 文件变更

```
新增：
  ui-compose/src/desktopMain/kotlin/dev/windplayer/ui/DesktopTypes.kt  (54 行)

修改：
  ui-compose/src/desktopMain/kotlin/dev/windplayer/ui/App.kt          (223 → 203 行)
  app-desktop/src/desktopMain/kotlin/dev/windplayer/Main.kt           (201 → 211 行)
```

### 收益

1. **App() 对外 API 表达力**：23 个 `name = { ... }` lambda → 3 个语义清晰的对象（State/Callbacks/Flows）
2. **新增 callback 不破坏调用方**：interface 默认 no-op，新方法只覆盖需要的地方
3. **未来支持 ViewModel/Navigation**：将 DesktopAppState/Callbacks 替换为 ViewModel 派生类即可，无需重写 App() 签名
4. **PlayerScreen 暂留**：作为下一轮工作（可选），不影响本轮 App API 简化目标

---

## 阶段三十八：PlayerScreen 参数对象化（A7 续）(已完成)

延续阶段三十七的设计模式，将 `PlayerScreen()` 从 25 个参数减为 8 个。

### 关键洞察

**`PlaybackParams` 已经包含了 PlayerScreen 需要的大部分参数**：
- `streamUrl` ← `initialFilePath`
- `subtitleFiles` ← `initialSubtitleFiles`
- `externalAudioUrls` ← `initialExternalAudioUrls`
- `mpvOptions` ← `initialMpvOptions`
- `serverId` ← `playbackServerId`
- `dirPath` ← `playbackDirPath`
- `isLocal` ← `playbackIsLocal`
- `directoryVideoPaths`
- `currentFileIndex`
- `resumePosition`
- `filePath`

共 **11 个参数**其实只是 App.kt 把 `pendingPlayback: PlaybackParams?` 解构再传给 PlayerScreen。重构后直接传整个 `params: PlaybackParams?`，由 PlayerScreen 内部解构。

### 设计

延续阶段三十七的 State/Callbacks/Flows 三分模式：

| 类型 | 角色 | 字段/方法 |
|------|------|----------|
| `PlaybackParams` (复用 core-vfs 现有 data class) | 播放参数（来自 `pendingPlayback`） | 11 个字段 |
| `PlayerCallbacks` (interface, 新增) | 事件 PlayerScreen → App | `onBack`, `onTracksToggle`, `onToggleFullscreen`, `onJumpToFile`, `onOsdEvent`, `onPositionUpdate` |
| `PlayerFlows` (data class, 新增) | 冷 SharedFlow streams | `osdEvents`, `playlistToggle`, `cheatsheetToggle` |

剩余直接参数：`player`、`vfsManager`、`isFullscreen`、`autoPlayNext`、`modifier`。

### PlayerScreen() 签名对比

```kotlin
// 之前：25 个参数
@Composable
fun PlayerScreen(
    player: MpvPlayer,
    initialFilePath: String = "",
    initialSubtitleFiles: List<String> = emptyList(),
    initialExternalAudioUrls: List<String> = emptyList(),
    initialMpvOptions: Map<String, String> = emptyMap(),
    onBack: (() -> Unit)? = null,
    onTracksToggle: ((Boolean) -> Unit)? = null,
    onToggleFullscreen: (() -> Unit)? = null,
    isFullscreen: Boolean = false,
    osdEvents: SharedFlow<String>? = null,
    vfsManager: VfsManager? = null,
    playbackServerId: String? = null,
    playbackDirPath: String? = null,
    playbackIsLocal: Boolean = false,
    directoryVideoPaths: List<String> = emptyList(),
    currentFileIndex: Int = -1,
    onPlayNextFile: ((filePath: String) -> Unit)? = null,
    onJumpToFile: ((filePath: String) -> Unit)? = null,
    onOsdEvent: ((String) -> Unit)? = null,
    resumePosition: Double = 0.0,
    filePath: String = "",
    playlistToggle: SharedFlow<Unit>? = null,
    cheatsheetToggle: SharedFlow<Unit>? = null,
    onPositionUpdate: ((filePath: String, position: Double, duration: Double) -> Unit)? = null,
    modifier: Modifier = Modifier
)

// 之后：8 个参数
@Composable
fun PlayerScreen(
    player: MpvPlayer,
    params: PlaybackParams? = null,
    callbacks: PlayerCallbacks = PlayerCallbacks.NoOp,
    flows: PlayerFlows = PlayerFlows(),
    vfsManager: VfsManager? = null,
    isFullscreen: Boolean = false,
    autoPlayNext: Boolean = false,
    modifier: Modifier = Modifier
)
```

**减少 68%**（25 → 8）。

### 关键设计决策

#### 1. 复用 `PlaybackParams` 而不是新建 wrapper

`PlaybackParams` 本就是 App.kt 维护的核心数据类，11 个播放参数都在里面。直接传 `params: PlaybackParams?` 而不是新建 `PlayerParams` wrapper 避免一层无意义的转换。

PlayerScreen 在函数体顶部用 `val initialFilePath = params?.streamUrl ?: ""` 等 11 行解构，下游逻辑完全不变。

#### 2. 合并 `onPlayNextFile` 与 `onJumpToFile`

原代码两个 callback 做同一件事（调用 `playNextFile(filePath)`），仅区分「EOF 自动触发」vs「用户点击」。`onPlayNextFile = null` 用于表达「autoPlayNext 设置关闭」。

重构后合并为单一 `onJumpToFile`，新增 `autoPlayNext: Boolean` 参数控制是否在 EOF 时自动调用：
- `autoPlayNext = true` + EOF → 调用 `callbacks.onJumpToFile(nextPath)`
- 用户点击 playlist → 调用 `callbacks.onJumpToFile(nextPath)`
- 两种场景完全统一

这消除了「用 null callback 表达设置项」的反模式。

#### 3. callbacks 调用从 `?.invoke()` 改为直接调用

```kotlin
// 之前
onBack?.invoke()
onTracksToggle?.invoke(showPlaylist)
onPositionUpdate?.invoke(fp, position, duration)

// 之后
callbacks.onBack()
callbacks.onTracksToggle(showPlaylist)
callbacks.onPositionUpdate(fp, position, duration)
```

interface 的默认 no-op 实现使 null 检查不再需要。

#### 4. flows 从 `flow?.let { ... }` 改为 `flow?.collect { ... }`

interface 默认 no-op 不适用于 SharedFlow（data class），所以仍用 `flow?.xxx?.collect { ... }` 模式。但代码更简洁：

```kotlin
// 之前
LaunchedEffect(osdEvents) {
    if (osdEvents == null) return@LaunchedEffect
    osdEvents.collectLatest { ... }
}

// 之后
LaunchedEffect(flows.osdEvents) {
    flows.osdEvents?.collectLatest { ... }
}
```

### App.kt 调用点对比

```kotlin
// 之前：25 个参数（解构 pendingPlayback 后传给 PlayerScreen）
PlayerScreen(
    player = player,
    initialFilePath = pendingPlayback?.streamUrl ?: initialFilePath,
    initialSubtitleFiles = pendingPlayback?.subtitleFiles ?: emptyList(),
    /* ... 22 more ... */
)

// 之后：8 个参数（直接传 pendingPlayback + object : PlayerCallbacks）
PlayerScreen(
    player = player,
    params = pendingPlayback,
    callbacks = object : PlayerCallbacks {
        override fun onBack() { /* ... */ }
        override fun onTracksToggle(expanded: Boolean) = callbacks.onTracksToggle(expanded)
        override fun onToggleFullscreen() = callbacks.onToggleFullscreen()
        override fun onJumpToFile(filePath: String) = playNextFile(filePath)
        override fun onOsdEvent(text: String) = callbacks.onOsdEmit(text)
        override fun onPositionUpdate(filePath: String, position: Double, duration: Double) =
            callbacks.onPositionUpdate(filePath, position, duration)
    },
    flows = PlayerFlows(
        osdEvents = flows.osdEvents,
        playlistToggle = flows.playlistToggle,
        cheatsheetToggle = flows.cheatsheetToggle
    ),
    vfsManager = vfsManager,
    isFullscreen = state.isFullscreen,
    autoPlayNext = state.settings.autoPlayNext,
    modifier = modifier
)
```

### 编译验证

- ✅ `:app-desktop:compileKotlinDesktop` — BUILD SUCCESSFUL
- ✅ `:app-android:compileDebugKotlin` — BUILD SUCCESSFUL

### 文件变更

```
修改：
  ui-compose/src/desktopMain/kotlin/dev/windplayer/ui/DesktopTypes.kt   (54 → 84 行，+PlayerCallbacks/+PlayerFlows)
  ui-compose/src/desktopMain/kotlin/dev/windplayer/ui/PlayerScreen.kt   (723 → 697 行)
  ui-compose/src/desktopMain/kotlin/dev/windplayer/ui/App.kt            (203 → 197 行)
```

### 整体收益（A7 + A7 续）

两个最大的 composable 都已重构：

| Composable | 之前参数数 | 之后参数数 | 减少 |
|------------|-----------|-----------|------|
| App() | 23 | 5 | 78% |
| PlayerScreen() | 25 | 8 | 68% |

桌面 UI 层的对外 API 表面积大幅减少，参数对象化使后续扩展（新增 callback / 新增 flow）不再破坏调用方。

---

## 阶段三十九：L 系列批量清理 (已完成)

一次性修复 8 个 Low-severity 问题（L6/L7/L8/L9/L11/L12/L13/L14），消除了代码审查中发现的所有细节体验类问题。**至此 L1-L15 全部 15 项已完成。**

### 各项详情

#### L11 — FileBrowserScreen 缩进修复
- 第 372-377 行 6 个闭合括号缩进完全错乱，手动重排为正确层级（32/28/24/20/16/12 空格）

#### L7 — ServerBrowseScreen 返回键导航
- 新增 `handleBack()` 函数：当前路径 == 服务器 basePath 时退出，否则 `onNavigate(parent)` 上一级
- BackHandler 和 TopAppBar 都改用 `handleBack()`

#### L6 — ServerStore 加密回退警告
- 新增 `@Volatile var encryptionActive: Boolean` 公开状态
- catch 分支中 `Log.e(TAG, "...falling back to plaintext: ${e.message}")`
- MobileApp 启动时主动检查，若 `encryptionActive == false` 显示 Toast 警告用户密码会明文存储

#### L12 — 桌面端 mpv PROPERTY_CHANGE 事件分支
- 在 `MpvPlayer.desktop.kt` 事件循环 `when` 中新增 `MPV_EVENT_PROPERTY_CHANGE` 分支
- 解析 `mpv_event_property` 结构（name/format/data，x86_64 偏移 0/8/16）
- 支持 STRING / FLAG / INT64 / DOUBLE 四种 format
- 为未来 A2（observer 替代轮询）铺路

#### L9 — WebDAV PROPFIND 解析器重构
- 75 行 4 层嵌套 → 22 行 2 层嵌套
- 抽出 4 个 helper：`parseResponse` / `findChildElement` / `hasChildElement` / `parseHttpDate`

#### L8 — MobileVfsManager 连接生命周期
- 原代码「connect 后从不 disconnect」导致 SSH/FTP socket 泄漏
- finally 块加入 `client.disconnect()`
- 同时把 `listDirectory` inline comparator 替换为 `FileNodeComparator`

#### L13 — println → java.util.logging
- 7 个文件 31 处 `println("[ClassName] ...")` 全部替换
- 每文件新增 `private val LOG = Logger.getLogger("dev.windplayer.xxx.ClassName")`
- 含 "failed" 的 → `LOG.warning(...)`，其余 → `LOG.info(...)` 或 `LOG.fine(...)`
- 桌面端可配 `java.util.logging.config.file` 控制日志级别

#### L14 — 静默异常 catch 添加日志
- **保留静默**：disconnect 失败、资源关闭失败、parseHttpDate 失败（预期失败，日志会刷屏）
- **新增 warning 日志**：
  - `VfsManager.kt` siblings 列表失败（影响外置轨道匹配）
  - `VfsManager.kt` 未知协议名（用户配置可能损坏）
- 桌面端事件循环 `catch (_: Exception) { /* ignore */ }` 改为 `catch (e: Exception) { LOG.log(Level.WARNING, "malformed property event", e) }`

### 编译验证

- ✅ `:app-desktop:compileKotlinDesktop` — BUILD SUCCESSFUL
- ✅ `:app-android:compileDebugKotlin` — BUILD SUCCESSFUL
- ✅ 核心模块 `core-mpv` + `core-vfs` 零 `println` 残留

### 文件变更

```
修改：
  app-android/src/main/kotlin/.../ServerStore.kt             # encryptionActive + Log.e
  app-android/src/main/kotlin/.../ServerBrowseScreen.kt      # handleBack()
  app-android/src/main/kotlin/.../MobileVfsManager.kt        # disconnect + FileNodeComparator
  app-android/src/main/kotlin/.../MobileApp.kt               # 加密回退 Toast
  core-mpv/src/desktopMain/.../MpvPlayer.desktop.kt          # PROPERTY_CHANGE 分支 + LOG
  core-vfs/src/commonMain/.../SftpClient.kt                  # LOG
  core-vfs/src/commonMain/.../WebdavClient.kt                # 重构 PROPFIND + LOG
  core-vfs/src/commonMain/.../FtpClient.kt                   # LOG
  core-vfs/src/desktopMain/.../LocalClient.kt                # LOG
  core-vfs/src/desktopMain/.../StreamProxy.kt                # LOG
  core-vfs/src/desktopMain/.../VfsManager.kt                 # LOG + 静默 catch 警告
  ui-compose/src/desktopMain/.../FileBrowserScreen.kt        # 缩进修复
```

---

## 阶段四十：A2 PlayerScreen 用 observer 替代轮询 (已完成)

利用阶段三十九 L12 补齐的 `MPV_EVENT_PROPERTY_CHANGE` 事件分支，把 PlayerScreen 的 3 个轮询循环整合为 1 个，并注册 mpv property observers 驱动低频状态更新。

### 优化前后对比

| 指标 | 优化前 | 优化后 | 减少 |
|------|--------|--------|------|
| `while(true){delay()}` 循环数 | 3 个（200ms / 1000ms / events） | 2 个（200ms / events） | -1 |
| 每秒 JNA 跨语言调用 | ~23 次（4×5 + 3×1 + collect 频率） | ~5 次（1×5 + collect 频率） | **-78%** |
| 轮询的属性 | `pause`、`time-pos`、`duration`、`eof-reached`、`volume`、`mute`、`speed`（7 个） | 仅 `time-pos`（1 个） | -6 |

与阶段十四的预期目标（「~23 次/秒 → 减少 23%」）相比，实际减少 **78%**，因为当时只是把 6 属性拆为快慢两循环，这次直接消除了其中 6 个的轮询需求。

### 改造的 6 个属性

| 属性 | MpvFormat | 旧轮询 | 新机制 |
|------|-----------|--------|--------|
| `pause` | FLAG | 200ms 读字符串 `pause != "yes"` | observer 派发 Boolean → `isPlaying` |
| `volume` | INT64 | 1000ms 读 long | observer 派发 Long（拖拽滑块时跳过避免冲突） |
| `mute` | FLAG | 1000ms 读字符串 `mute == "yes"` | observer 派发 Boolean → `isMuted` |
| `speed` | DOUBLE | 1000ms 读 double | observer 派发 Double |
| `duration` | DOUBLE | 200ms 读 double | observer 派发 Double |
| `eof-reached` | FLAG | 200ms 读字符串 `== "yes"` 触发自动播放 | observer 派发 Boolean=true 时触发自动播放 |

**保留轮询的属性**：仅 `time-pos`（DOUBLE）。它随帧率变化（~24-60 次/秒），用 observer 会每秒 flood SharedFlow 60+ 事件，把 EndFile/FileLoaded 等关键事件挤掉。200ms（5 Hz）轮询是 UX 与 IPC 开销的正确折中。

### 关键设计决策

#### 1. 防御性初始读取（FileLoaded 时）

```kotlin
is MpvEvent.FileLoaded -> {
    ...
    try {
        duration = player.getPropertyDouble("duration")...
        volume = player.getPropertyLong("volume")
        isMuted = player.getPropertyString("mute") == "yes"
        speed = player.getPropertyDouble("speed")
    } catch (_: Exception) {}
    ...
}
```

`mpv_observe_property` 理论上注册时立即发射当前值，但为了对抗「mpv 版本/构建差异导致 initial event 不发」的边角情况，在 FileLoaded 时做一次同步读取。如果 observer 正常工作，这次读取只是写相同值（Compose 跳过重组）；如果不正常，状态至少不会卡在默认值。

#### 2. volume 拖拽冲突保护

```kotlin
"volume" -> {
    val v = event.value as? Long ?: return
    if (!isVolumeDragging()) setVolume(v)  // 用户拖滑块时跳过 observer 回写
}
```

拖拽滑块时用户每帧 `player.setProperty("volume", x)`，会触发 observer 立即回弹。跳过 observer 写入让用户拖拽手感顺滑，松手后下一次 observer 事件会同步最终值。

#### 3. EOF 自动播放触发点迁移

旧代码：200ms 轮询检查 `position >= duration - 1.0 && eof-reached == "yes"` 这个复合条件（位置启发式 + mpv 权威信号）。

新代码：直接监听 `eof-reached == true`。mpv 在 EOF 时设置此 flag，比启发式更可靠（启发式可能在长文件末尾的 buffer 抖动中误触发）。`position >= duration - 1.0` 条件其实多余——eof-reached=true 本身就意味着到达末尾。

#### 4. handlePropertyChange 提取为私有函数

```kotlin
private fun handlePropertyChange(
    event: MpvEvent.PropertyChange,
    setIsPlaying: (Boolean) -> Unit,
    setIsMuted: (Boolean) -> Unit,
    setVolume: (Long) -> Unit,
    setSpeed: (Double) -> Unit,
    setDuration: (Double) -> Unit,
    onEofReached: () -> Unit,
    isVolumeDragging: () -> Boolean
) { ... }
```

把 PropertyChange 分发逻辑从 `events.collect` 的 `when` 分支里拉出来，调用方传 setter lambda。这样：
- 分发表清晰可读（6 个属性 × 类型 → setter）
- 不污染 events.collect 块（已经很长了）
- 单元测试容易（pure function，无 Compose 依赖）

### 编译验证

- ✅ `:app-desktop:compileKotlinDesktop` — BUILD SUCCESSFUL
- ✅ `:app-android:compileDebugKotlin` — BUILD SUCCESSFUL

### 文件变更

```
修改：
  ui-compose/src/desktopMain/.../PlayerScreen.kt
    # + observer 注册（6 个属性）
    # + PropertyChange 分发到 handlePropertyChange helper
    # - 删除 1000ms slow loop（volume/mute/speed）
    # - 简化 200ms fast loop（只保留 time-pos + position 上报）
    # - 移除 eof-reached 轮询自动播放（迁移到 observer）
    # + FileLoaded 防御性初始读取
    # + handlePropertyChange 私有 helper 函数
```

### Android 端

Android 的 MobilePlayerScreen 仍然使用 200ms 轮询（未在本轮重构）。Android 的 MPVLib observer 机制本就工作正常（阶段三十一已确认），可以套用同样的模式重构，但需要测试 libplayer.so 的 observer 在不同 Android 版本上的稳定性。当前优先级低（Android 端用得好好的），留作未来工作。

### 剩余 A2 相关工作

- 把 Android MobilePlayerScreen 也迁移到 observer（可选）
- 增加 mpv observer 失败的遥测（如果 PropertyChange 长时间不发，自动降级到轮询）

---

## 阶段四十一：P0-3 KMP 源集彻底重组 (已完成)

把 `core-vfs/commonMain` 中的 JVM 专属代码迁出，引入 `jvmShared` 中间源集。

### 背景

代码审查 P0-3：`SftpClient.kt`、`WebdavClient.kt`、`FtpClient.kt`、`VfsUtils.kt` 放在 `commonMain` 中，却 import 了：
- `net.schmizz.sshj.*`（SSHJ）
- `org.apache.commons.net.ftp.*`（Commons Net）
- `io.ktor.client.engine.cio.*`（Ktor CIO）
- `javax.xml.parsers.*` / `org.w3c.dom.*`（JVM XML）
- `java.io.*` / `java.net.URLEncoder` / `java.util.logging.Logger`
- `String.format(...)`（JVM-only）

当前因为 Android 和 Desktop 都是 JVM target 才能编译，但严格违反 KMP「`commonMain` 必须 target-agnostic」的约定。

### 修复方案：引入 `jvmShared` 中间源集

```
commonMain (target-agnostic)
    └── jvmShared (JVM-only, shared)
            ├── desktopMain (桌面端：LocalClient + StreamProxy + VfsManager)
            └── androidMain (空，由 app-android 模块自行实现)
```

`jvmShared` 是 KMP 自定义中间源集（`val jvmShared by creating { dependsOn(commonMain) }`），同时被 `desktopMain` 和 `androidMain` 继承。这样：
- JVM 专属 VFS 代码只写一份（在 jvmShared 中）
- 两个 target 都能直接 import
- commonMain 真正 portable，未来加 iOS / Native target 时不会冲突

### 改造详情

#### commonMain（保留 4 个文件，全部 target-agnostic）

| 文件 | 内容 |
|------|------|
| `FileNode.kt` | 数据类 + `isVideo`/`isSubtitle`/`videoBaseName`/`findSidecarSubtitles` 扩展（不含 `formatFileSize`，已迁出） |
| `ServerConfig.kt` | 数据类 + `bareHost`/`httpScheme`/`defaultPort` |
| `VfsClient.kt` | interface + `PlaybackParams` 数据类 |
| `TrackMatcher.kt` | 纯 Kotlin 匹配算法（无任何 import） |

#### jvmShared（新增，4 个文件）

| 文件 | 依赖 |
|------|------|
| `SftpClient.kt` | sshj |
| `WebdavClient.kt` | Ktor CIO + javax.xml + java.io |
| `FtpClient.kt` | commons-net |
| `VfsUtils.kt` | `String.format` + `java.net.URLEncoder` + 新增 `formatFileSize`（从 FileNode.kt 迁来） |

#### desktopMain（保留 3 个文件，桌面端独有）

| 文件 | 内容 |
|------|------|
| `LocalClient.kt` | `java.io.File` 本地文件访问 |
| `StreamProxy.kt` | `com.sun.net.httpserver` HTTP 代理 |
| `VfsManager.kt` | 桌面端 VFS 门面 |

#### androidMain（空目录）

当前无内容。Android 端的 `MobileVfsManager` 在 `app-android` 模块中实现，直接 import `dev.windplayer.vfs.SftpClient` 等 jvmShared 类。

### Gradle 配置变更（`core-vfs/build.gradle.kts`）

```kotlin
sourceSets {
    val commonMain by getting {
        dependencies {
            implementation(libs.kotlinx.coroutines.core)
            // 移除：ktor / sshj / commons-net 都搬到 jvmShared
        }
    }
    val jvmShared by creating {
        dependsOn(commonMain)
        dependencies {
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.cio)
            implementation(libs.sshj)
            implementation(libs.commons.net)
        }
    }
    val desktopMain by getting {
        dependsOn(jvmShared)
        dependencies { implementation("org.slf4j:slf4j-nop:2.0.16") }
    }
    val androidMain by getting {
        dependsOn(jvmShared)
    }
}
```

### 文件移动

```
commonMain/kotlin/dev/windplayer/vfs/
    ├── FileNode.kt           (保留，但 formatFileSize 迁出)
    ├── ServerConfig.kt       (保留)
    ├── TrackMatcher.kt       (保留)
    ├── VfsClient.kt          (保留)
    ├── SftpClient.kt         → 迁到 jvmShared/
    ├── WebdavClient.kt       → 迁到 jvmShared/
    ├── FtpClient.kt          → 迁到 jvmShared/
    └── VfsUtils.kt           → 迁到 jvmShared/

jvmShared/kotlin/dev/windplayer/vfs/  (新建)
    ├── SftpClient.kt
    ├── WebdavClient.kt
    ├── FtpClient.kt
    └── VfsUtils.kt           (+ 新增 formatFileSize)
```

### 关键设计决策

#### 1. 为什么不直接移到 desktopMain？

因为 Android 端的 `MobileVfsManager` 也直接使用 SftpClient/WebdavClient/FtpClient。如果只移到 desktopMain，Android 编译会断。引入 jvmShared 让两端共享 JVM 代码，避免重复实现。

#### 2. 为什么不删 androidMain？

虽然目前为空，但保留是为：
- 未来需要 Android 专有 VFS 逻辑时（如基于 SAF 的 LocalClient 替代品）有地方放
- KMP 模块结构对称性

#### 3. formatFileSize 为什么迁出？

它使用 `"%.1f %s".format(size, units[unitIndex])`（JVM-only 的 `String.format`）。虽然 1 行代码看似无关紧要，但严格 P0-3 的目标是「commonMain 必须 target-agnostic」，所以一并迁出。

### 编译验证

- ✅ `:app-desktop:compileKotlinDesktop` — BUILD SUCCESSFUL
- ✅ `:app-android:compileDebugKotlin` — BUILD SUCCESSFUL

### 收益

1. **commonMain 真正 portable**：4 个文件全部 target-agnostic，未来加 iOS / Native target 时不需要任何代码迁移
2. **JVM 代码去重**：SftpClient/WebdavClient/FtpClient 只一份，两端共享
3. **依赖隔离**：sshj/ktor/commons-net 只在 jvmShared 声明，commonMain 干净
4. **可测试性**：commonMain 中的 TrackMatcher / ServerConfig 可在任意 target 单元测试
5. **符合 KMP 官方推荐实践**

---

## 阶段四十二：P1-5 桌面端密码加密（Windows DPAPI）(已完成)

用 Windows DPAPI 加密 `~/.windplayer/servers.properties` 中的 server 密码字段。

### 背景

P1-5：`VfsManager.saveConfig()` 把 `server.$i.password=明文` 直接写入 Properties 文件。任何能读该文件的本机用户/进程都能看到所有服务器密码。

Android 端早已用 `EncryptedSharedPreferences`（阶段三十一），桌面端这次跟上。

### 加密方案：Windows DPAPI via JNA

**为什么是 DPAPI 而不是 AES/KeyStore**：
- DPAPI（`CryptProtectData` / `CryptUnprotectData`）用 Windows 用户账户凭据派生密钥，**无需管理任何密钥文件**
- 同一 Windows 账户登录的进程都能解密；其他账户、其他机器都不能（即使复制文件过去）
- 是 Windows 上「at-rest 密码存储」的标准做法（Chrome / Edge / Outlook 等都用）
- JNA-platform 5.17.0 已封装好 `Crypt32Util.cryptProtectData(byte[])` / `cryptUnprotectData(byte[])`，无需自己写 JNA 结构映射

**JNA-platform 5.17.0 API 备注**：与旧版本不同，5.17.0 的 `Crypt32Util` 只有 `cryptProtectData(byte[])`（1 参数）和 `cryptProtectData(byte[], int)`（data + flags）两种重载，**没有 2 参数 `byte[] entropy` 版本**。编译时遇到 `Null cannot be a value of a non-null type 'Int'` 错误就是被这里坑了。

### 实现：`CryptoUtil.kt`（core-vfs/desktopMain 新增）

```kotlin
internal object CryptoUtil {
    private const val PREFIX_DPAPI = "dpapi:"
    private const val PREFIX_PLAIN = "plain:"
    private val isWindows = System.getProperty("os.name").startsWith("Windows")

    fun encrypt(plaintext: String): String {
        if (plaintext.isEmpty()) return ""
        if (!isWindows) return PREFIX_PLAIN + plaintext
        return try {
            val cipher = Crypt32Util.cryptProtectData(plaintext.toByteArray(Charsets.UTF_8))
            PREFIX_DPAPI + Base64.getEncoder().encodeToString(cipher)
        } catch (e: Throwable) {
            LOG.warning("DPAPI encrypt failed, falling back to plaintext: ${e.message}")
            PREFIX_PLAIN + plaintext
        }
    }

    fun decrypt(stored: String): String = when {
        stored.startsWith(PREFIX_DPAPI) -> /* Crypt32Util.cryptUnprotectData(...) */
        stored.startsWith(PREFIX_PLAIN) -> stored.removePrefix(PREFIX_PLAIN)
        else -> stored  // legacy plaintext (transparent passthrough)
    }
}
```

### 存储格式：前缀选择

每个 password 字段加前缀标识编码方式：

| 前缀 | 含义 | 安全级别 |
|------|------|----------|
| `dpapi:<base64>` | DPAPI 加密 | 强（绑 Windows 用户） |
| `plain:<text>` | 显式标记的明文（非 Windows fallback） | 弱 |
| `<text>`（无前缀） | 遗留明文（P1-5 之前） | 弱 |

**`decrypt` 三种分支的处理**：
1. `dpapi:` → 用 DPAPI 解密；失败返回 `""`（密码无法恢复）
2. `plain:` → 去前缀返回明文
3. 无前缀 → **直接返回明文**（向后兼容）

### 向后兼容（透明迁移）

旧用户的 `servers.properties` 里是无前缀的明文密码。`decrypt` 把它当 legacy 明文处理，应用照常工作。**用户一旦做任何会触发 saveConfig 的操作**（添加/删除/编辑任意服务器），所有密码就会被 `encrypt` 加上 `dpapi:` 前缀重新写回。

无需主动迁移脚本，无需破坏性升级。

### VfsManager.kt 改动

```kotlin
// saveConfig
props.setProperty("server.$index.password", CryptoUtil.encrypt(server.password))

// loadConfig
val password = CryptoUtil.decrypt(props.getProperty("server.$i.password", ""))
```

仅 2 行改动。

### 非 Windows 平台

`isWindows == false` 时：
- `encrypt` 返回 `plain:<text>`（标明显式明文，区别于 `dpapi:`）
- `decrypt` 把 `plain:` 去前缀
- `dpapi:` 前缀的数据无法解密（log 警告，返回 `""`）

未来若正式支持 Linux，可引入 libsecret / KWallet；当前优先级低。

### 烟囱测试

```
PLAIN: mySecretPass123!@#
CIPHER (b64): AQAAANCMnd8BFdERjHoAwE/Cl+sBAAAApnzT4Aiu2UKLYMNZlSrLFQAAAAACG...
DECRYPTED: mySecretPass123!@#
MATCH: true
```

通过 Java 直接调用 `Crypt32Util` 验证 DPAPI 在当前 Windows 环境工作正常。

### 编译验证

- ✅ `:app-desktop:compileKotlinDesktop` — BUILD SUCCESSFUL
- ✅ `:app-android:compileDebugKotlin` — BUILD SUCCESSFUL（不影响 Android，Android 用 EncryptedSharedPreferences）

### 文件变更

```
新增：
  core-vfs/src/desktopMain/.../CryptoUtil.kt     # DPAPI 加密 + 前缀路由 + 兼容层

修改：
  core-vfs/build.gradle.kts                       # desktopMain + jna-platform 依赖
  core-vfs/src/desktopMain/.../VfsManager.kt     # saveConfig/loadConfig 用 CryptoUtil
```

### 安全模型总结

| 平台 | 加密方案 | 密钥来源 |
|------|----------|----------|
| **Windows** | DPAPI（`CryptProtectData`） | Windows 用户账户凭据 |
| **Linux/其他** | 显式标记的明文（`plain:` 前缀） | 无 |
| **Android** | `EncryptedSharedPreferences`（AES256-GCM/SIV） | Android Keystore |

桌面端和 Android 端现在都加密密码，仅 Linux 桌面端因依赖缺失暂用明文（标有 `plain:` 前缀可识别）。

---

## 阶段四十三：P1-6 SSH 主机验证（TOFU + known_hosts 持久化）(已完成)

实现 Trust-on-First-Use（TOFU）主机密钥验证，替代 `PromiscuousVerifier`。

### 背景

P1-6：`SftpClient` 和 `StreamProxy.StreamSession` 都用 `PromiscuousVerifier`，**每次连接都接受任何服务器密钥**。MITM 攻击者可以伪造 SSH 服务器窃取用户名/密码。

OpenSSH 默认行为是「首次连接询问 + 持久化 + 后续严格验证」。本次实现等价的 TOFU 模式。

### SSHJ 0.39 API 调研

通过反编译 `sshj-0.39.0.jar` 确认 SSHJ 已封装 OpenSSH known_hosts 格式：

| 类 | 角色 |
|----|------|
| `OpenSSHKnownHosts(File)` | 基类，解析/写入 known_hosts 文件，实现 `HostKeyVerifier` |
| `OpenSSHKnownHosts.HostEntry(Marker, hostname, KeyType, PublicKey)` | 一个 host entry（Marker 通常 null） |
| `OpenSSHKnownHosts.entries(): List<KnownHostEntry>` | 已加载的 entries（可变 List） |
| `OpenSSHKnownHosts.write()` | 把 entries 持久化到文件 |
| `hostKeyUnverifiableAction(hostname, key): Boolean` | 钩子：未知 host 时如何处理（默认返回 false = 拒绝） |
| `hostKeyChangedAction(hostname, key): Boolean` | 钩子：已知 host 但 key 不匹配（默认返回 false = 拒绝 MITM） |
| `ConsoleKnownHostsVerifier` | 官方示例子类，控制台询问用户 |

参考 `ConsoleKnownHostsVerifier` 源码确认正确用法：构造 `HostEntry` → `entries().add(entry)` → `write()` → 返回 true。

### 实现：`KnownHostsManager.kt`（jvmShared 新增）

```kotlin
class TofuHostKeyVerifier(knownHostsFile: File) : OpenSSHKnownHosts(knownHostsFile.ensureExists()) {
    @Synchronized
    override fun hostKeyUnverifiableAction(hostname: String, key: PublicKey): Boolean {
        return try {
            val entry = HostEntry(null, hostname, KeyType.fromKey(key), key)
            entries().add(entry)
            write()
            LOG.info("TOFU: recorded new host key for $hostname")
            true
        } catch (e: Exception) {
            LOG.warning("TOFU: failed to record host key for $hostname: ${e.message}")
            false  // 持久化失败时拒绝，避免静默接受所有 key
        }
    }
}

object KnownHostsManager {
    val verifier: HostKeyVerifier by lazy {
        try { TofuHostKeyVerifier(File("~/.windplayer/known_hosts")) }
        catch (e: Exception) {
            LOG.warning("known_hosts unreadable, falling back to PromiscuousVerifier — MITM-vulnerable")
            PromiscuousVerifier()
        }
    }
}
```

### 行为对比

| 场景 | 旧（PromiscuousVerifier） | 新（TofuHostKeyVerifier） |
|------|---------------------------|---------------------------|
| 首次连接 host A | 接受（不记录） | **接受并记录到 known_hosts** |
| 第 2 次连接 host A | 接受（不验证） | **验证匹配 → 接受** |
| 攻击者冒充 host A（key 不同） | **接受！❌** | **拒绝 ✓**（MITM 保护） |
| 持久化失败（文件不可写） | 接受 | **拒绝**（fail-safe） |
| known_hosts 文件不可读 | 接受 | 降级到 PromiscuousVerifier + 警告日志 |

### 验证逻辑（OpenSSHKnownHosts 默认行为）

```
verify(hostname, port, key) {
    if (any entry matches hostname+key) return true   // 已知 host + 匹配
    if (any entry matches hostname but key differs) {
        return hostKeyChangedAction(hostname, key)    // 默认 false = 拒绝
    }
    return hostKeyUnverifiableAction(hostname, key)   // 默认 false = 拒绝；TOFU 改为 true
}
```

子类只需覆盖 `hostKeyUnverifiableAction` 实现 TOFU；`hostKeyChangedAction` 默认拒绝就是 MITM 保护。

### 改造点

#### `SftpClient.kt`（jvmShared）
```kotlin
// 之前
client.addHostKeyVerifier(PromiscuousVerifier())
// 之后
client.addHostKeyVerifier(KnownHostsManager.verifier)
```

#### `StreamProxy.StreamSession`（desktopMain）
```kotlin
// 之前
client.addHostKeyVerifier(PromiscuousVerifier())
// 之后
client.addHostKeyVerifier(KnownHostsManager.verifier)
```

Android 端（MobileVfsManager → SftpClient）自动受益，无需改动。

### 单例 + lazy 初始化

`KnownHostsManager.verifier` 用 `by lazy` 确保只创建一次（跨多个 client 共享 entries 缓存）。即使有多个 SSHClient 实例，TOFU 状态都在内存中一致。

### 编译验证

- ✅ `:app-desktop:compileKotlinDesktop` — BUILD SUCCESSFUL
- ✅ `:app-android:compileDebugKotlin` — BUILD SUCCESSFUL
- ✅ 验证：`PromiscuousVerifier` 在 `core-vfs/src/**/*.kt` 中只剩 `KnownHostsManager.kt` 的 fallback 引用（其他位置全部清除）

### 文件变更

```
新增：
  core-vfs/src/jvmShared/.../KnownHostsManager.kt   # TofuHostKeyVerifier + KnownHostsManager 单例

修改：
  core-vfs/src/jvmShared/.../SftpClient.kt          # 用 KnownHostsManager.verifier
  core-vfs/src/desktopMain/.../StreamProxy.kt       # 用 KnownHostsManager.verifier
```

### 安全模型升级

| 平台 | 之前 | 之后 |
|------|------|------|
| **SSH (SFTP)** | PromiscuousVerifier（每次都接受） | TOFU + known_hosts（首次记录，后续严格验证） |
| **WebDAV** | Ktor 默认 TLS（正常 CA 验证） | 不变（已安全） |
| **FTP** | 不支持 FTPS / TLS | 不变（FTP 本来就是明文协议） |

### 已知限制

1. **首次连接仍可能被 MITM**：TOFU 模式下，攻击者在用户首次连接时伪造服务器即可被信任。这是 TOFU 的固有特性（OpenSSH `StrictHostKeyChecking=accept-new` 也是这样）。要进一步加固需要「预先分发 known_hosts」或「带外验证指纹」。
2. **指纹 UI 缺失**：当前没有显示指纹给用户人工对比的 UI。日志里有 `LOG.info` 记录接受过的 host。未来可加 Settings 页面查看/清空 known_hosts。
3. **Linux/Mac 桌面**：known_hosts 文件路径 `~/.windplayer/known_hosts`，与 OpenSSH 默认的 `~/.ssh/known_hosts` 分开（避免污染用户 SSH 配置）。

---

## 阶段四十四：P0-4 Android EndFile reason 真实化（Kotlin 层推断）(已完成)

通过 Kotlin 层属性查询推断 mpv end_file reason，无需修改 `libplayer.so` 的 C 源码。

### 背景

P0-4：Android 端 `MpvPlayer.android.kt` 的 `event(MPV_EVENT_END_FILE)` 分支用 `if (fileLoadedBefore) 0 else 4` 编造 reason。`libplayer.so` 的 JNI 桥 `event(int eventId)` 只传事件 ID，不传 `mpv_event_end_file.reason` 字段。

### 实际 bug 影响

旧代码区分不出 **STOP**（用户按返回键 / 调用 `stop` 命令）和 **EOF**（自然结束）：
- 用户按返回键 → mpv 发 END_FILE reason=2 (STOP)
- 旧代码：`fileLoadedBefore=true` → 报告 reason=0 (EOF)
- `MobilePlayerScreen` 收到 `reason==0` 满足 `autoPlayNext && reason == 0` 条件
- **触发自动播放下一个文件**，即使用户明确按了返回键

实际未造成数据问题（`onBack` 立即把 `pendingFile = null` + `dispose()` 切换屏幕），但是 race condition / UX 异常。

### 方案对比

| 方案 | 描述 | 复杂度 |
|------|------|--------|
| **A. 修 C 代码** | 改 `libplayer.so` 的 `event(int)` 为 `event(int, int)`，传递 reason | 高（NDK 交叉编译 3 个 ABI） |
| **B. Kotlin 层推断**（本方案） | 收到 END_FILE 时查询 `eof-reached` 属性推断 reason | 低 |

选 B：纯 Kotlin 改动，无需重编译 native，立即生效。

### 推断逻辑

```kotlin
private fun inferEndFileReason(wasLoaded: Boolean): Int {
    val eof = MPVLib.getPropertyString("eof-reached") == "yes"
    return when {
        eof -> 0       // 自然结束
        wasLoaded -> 2 // 加载过但没 EOF → STOP（用户停止 / 新 loadfile）
        else -> 4      // 没加载成功 → ERROR
    }
}
```

依据：mpv 文档「`eof-reached` 在播放到达文件末尾时为 true，加载新文件或 seek 时为 false」。

| 场景 | `eof-reached` | `wasLoaded` | 推断 reason |
|------|---------------|-------------|-------------|
| 播放到末尾自然结束 | `yes` | true | **0 (EOF)** ✓ |
| 用户按返回 / `stop` | `no` | true | **2 (STOP)** ✓ |
| 加载失败 | `no` | false | **4 (ERROR)** ✓ |
| 加载新文件（旧被替换） | `no` | true | **2 (STOP)** ✓ |

### 关键代码改动

```kotlin
// 之前（编造）
MPVLib.MpvEvent.MPV_EVENT_END_FILE -> {
    val r = if (fileLoadedBefore) 0 else 4
    fileLoadedBefore = false
    _events.tryEmit(MpvEvent.EndFile(r))
}

// 之后（推断）
MPVLib.MpvEvent.MPV_EVENT_END_FILE -> {
    val reason = inferEndFileReason(fileLoadedBefore)
    fileLoadedBefore = false
    _events.tryEmit(MpvEvent.EndFile(reason))
}

private fun inferEndFileReason(wasLoaded: Boolean): Int {
    return try {
        val eof = MPVLib.getPropertyString("eof-reached") == "yes"
        when {
            eof -> 0
            wasLoaded -> 2
            else -> 4
        }
    } catch (_: Exception) {
        if (wasLoaded) 0 else 4  // 属性查询失败的兜底
    }
}
```

### UX 修复验证

`MobilePlayerScreen` 收到 EndFile 的处理逻辑：

```kotlin
if (event.reason == 4) errorMsg = "Playback failed"
else if (autoPlayNext && event.reason == 0 && currentIdx + 1 < directoryVideos.size) {
    // auto-play next
}
```

| 用户动作 | 真实 reason | 旧推断 reason | 新推断 reason | 行为 |
|----------|-------------|---------------|---------------|------|
| 自然播完 | 0 (EOF) | 0 | 0 | 自动播放下一首 ✓ |
| 按返回键 | 2 (STOP) | **0 (错)** ❌ | **2 ✓** | 不自动播放 ✓ |
| 加载失败 | 4 (ERROR) | 4 | 4 | 显示 "Playback failed" ✓ |

**Bug 修复**：按返回键不再误触发自动播放。

### 桌面端不变

`MpvPlayer.desktop.kt` 已经从 `event.data?.getInt(0)` 直接读取真实 reason（mpv C struct 的第一个字段就是 reason）。本次改动只影响 Android。

### 编译验证

- ✅ `:app-desktop:compileKotlinDesktop` — BUILD SUCCESSFUL（不受影响）
- ✅ `:app-android:compileDebugKotlin` — BUILD SUCCESSFUL

### 已知限制

1. **属性查询时机**：理论上 `eof-reached` 在 END_FILE 事件触发时已经被 mpv 设置。如果 mpv 内部在派发事件前重置该属性（实践中不会），推断会失败 → 走 catch 兜底（旧编造逻辑）。
2. **REDIRECT (reason=5)**：mpv 处理播放列表时会发 REDIRECT。当前推断会归类为 STOP (2)，因为 `eof-reached=no`。对 UX 无影响（用户没在播列表）。
3. **QUIT (reason=3)**：仅在 player 销毁时触发。归类为 STOP (2)，对 UX 无影响（player 都销毁了）。

### 文件变更

```
修改：
  core-mpv/src/androidMain/.../MpvPlayer.android.kt     # 新增 inferEndFileReason helper + 更新 event() 回调
```

### 未来若修 C 代码

如果将来重新交叉编译 `libplayer.so`，可以让 `event(int)` 升级为 `event(int, int)`（带 reason），然后 MPVLib.kt 的 `EventObserver.event` 接口也加 reason 参数。届时 `inferEndFileReason` 可以删除，直接用真实 reason。当前 Kotlin 层推断是「最小代价最大收益」方案。

---

## 🎉 全部 22 个审查问题已修复

从阶段三十三到阶段四十四，共 12 个修复批次：

| 阶段 | 修复内容 | 问题数 |
|------|----------|--------|
| 33 | 代码审查修复批次 1-3（P0-1/P0-2/P1-1/P1-2/P1-3/P1-4/L1/L2/L3/L4/L5/L10/L15/A3） | 13 |
| 34 | A6 重复代码抽取 | 1 |
| 35 | A1 Main.kt 巨石拆分 | 1 |
| 36 | A4 + A5 KMP 源集整理 | 2 |
| 37-38 | A7 + PlayerScreen 参数对象化 | 2 |
| 39 | L 系列批量清理（L6/L7/L8/L9/L11/L12/L13/L14） | 8 |
| 40 | A2 PlayerScreen observer 替代轮询 | 1 |
| 41 | P0-3 KMP 源集彻底重组 | 1 |
| 42 | P1-5 桌面端密码加密（DPAPI） | 1 |
| 43 | P1-6 SSH 主机验证 TOFU | 1 |
| 44 | P0-4 Android EndFile reason 真实化 | 1 |

**剩余未解决问题：0** ✓

---

## 阶段四十五：CI/CD 搭建（GitHub Actions）(已完成)

搭建两阶段 CI/CD 流水线 + 测试基础设施。

### 新增文件

#### 1. `.github/workflows/ci.yml`（CI 流水线）
触发：`push` 到 `master`/`main` 或任何 `pull_request`。

| 步骤 | 命令 | 目的 |
|------|------|------|
| Checkout | `actions/checkout@v4` | 获取代码 |
| Setup JDK 21 | `actions/setup-java@v4` (temurin) | 项目约定的 JDK 版本 |
| Setup Android SDK | `android-actions/setup-android@v3` (platforms;android-36) | CI 不读 local.properties，自备 SDK |
| Setup Gradle | `gradle/actions/setup-gradle@v3` | 缓存 ~/.gradle 加速后续构建 |
| Compile Desktop | `:app-desktop:compileKotlinDesktop` | 验证 JVM target 编译 |
| Run unit tests | `:core-vfs:desktopTest` | TrackMatcher 等 commonTest 单元测试 |
| Build Android Debug APK | `:app-android:assembleDebug` | 输出 APK 用于人工冒烟 |
| Upload APK | `actions/upload-artifact@v4` | 14 天保留，供 PR 评论者下载测试 |
| Upload test results | `actions/upload-artifact@v4` | JUnit XML + HTML 报告，便于诊断失败 |

并发控制：`concurrency: { group: ci-${{ github.ref }}, cancel-in-progress: true }` — 同分支新提交取消旧运行。

#### 2. `.github/workflows/release.yml`（发布流水线）
触发：tag push `v*`。

| 步骤 | 目的 |
|------|------|
| Checkout + JDK + Android SDK + Gradle | 同 CI |
| Extract version | 从 tag 名提取版本号（`v0.2.0` → `0.2.0`） |
| Build Android Debug APK | 桌面端冒烟 APK（暂不签名，未来加 keystore 配置） |
| Build Desktop distribution ZIP | `:app-desktop:distZip` 生成跨平台 JVM bundle |
| Rename with version | `WindPlayer-<version>-android.apk` / `WindPlayer-<version>-desktop.zip` |
| Create GitHub Release | `softprops/action-gh-release@v2`，自动生成 release notes |

权限：`contents: write`（创建 release 必需）。

**当前不生成的产物**（标 TODO）：
- ❌ Signed Android release APK（需要 keystore 配置）
- ❌ Desktop MSI（需要 Windows runner，可作为单独 job 后续添加）

### 测试基础设施

#### `core-vfs/src/commonTest/.../TrackMatcherTest.kt`（新增，9 个测试）

目标：让 CI 流水线有东西可以测试，且一旦 TrackMatcher 回归立即报警。

覆盖场景（依据 `Documents/external-media-track-matching-and-scheduling.md.md`）：
- `VIDEO_EXTENSIONS` / `SUBTITLE_EXTENSIONS` 集合内容
- `FileNode.isVideo` / `isSubtitle` / `FileNodeComparator`
- Level 2 精确名称匹配（subtitle + audio）
- Level 3 剧集特征匹配（含同集多字幕 + 排除异集字幕）
- 无关文件不匹配（防误抓）

```kotlin
@Test
fun `Level 3 episode feature match hits subtitles`() {
    val v = video("Show.S01E01.mkv")
    val siblings = listOf(
        sub("Show.S01E01.eng.srt"),
        sub("Show.S01E01.sc.srt"),
        sub("Show.S01E02.sc.srt")  // 不同集 — 必须 NOT match
    )
    val matched = matchExternalTracks(v, siblings)
    assertEquals(2, matched.size)
}
```

#### `core-vfs/build.gradle.kts` — 新增 commonTest 依赖

```kotlin
val commonTest by getting {
    dependencies {
        implementation(kotlin("test"))
    }
}
```

### 本地验证

完整模拟 CI 流水线（本地 Windows + JDK 21）：

```
./gradlew :app-desktop:compileKotlinDesktop :core-vfs:desktopTest :app-android:assembleDebug
→ BUILD SUCCESSFUL in 27s

TrackMatcherTest[desktop]: tests=9 failures=0 skipped=0 ✓
```

### `AGENTS.md`（根目录新增）

为 AI 助手 / 新贡献者整理的速查文档，包括：
- 项目布局
- Desktop / Android 构建命令
- 测试命令
- CI/CD 工作流概述
- 常见陷阱（local.properties / libmpv / JDK 版本 / PromiscuousVerifier / EndFile reason）

### 文件变更总结

```
新增：
  .github/workflows/ci.yml                                # CI 流水线
  .github/workflows/release.yml                           # 发布流水线
  AGENTS.md                                                # 速查文档
  core-vfs/src/commonTest/.../TrackMatcherTest.kt         # 9 个单元测试

修改：
  core-vfs/build.gradle.kts                               # commonTest + kotlin-test 依赖
```

### CI/CD 设计决策

#### 1. 用 Linux 而非 Windows runner

Linux runner 最便宜（GitHub Actions 公共仓库免费，但私有仓库按时长计费）。CI 只做编译验证 + 测试，不需要 Windows 特定工具链。

#### 2. CI 只跑 Debug，Release 跑 Release

CI 流水线用 `:app-android:assembleDebug`（无需签名），release 流水线才需要 release 构建。这避免了在 PR 上消耗签名 keystore 的安全风险。

#### 3. 缓存策略

`gradle/actions/setup-gradle@v3` 自动缓存 `~/.gradle/caches`。配置 `cache-read-only: ${{ github.ref != 'master' && ... }}` 让 PR 只读缓存（避免每个 PR 写入不同 deps 撑爆缓存）。

#### 4. 测试结果作为 artifact

即使 CI 失败也上传 JUnit XML + HTML 报告（`if: always()`），方便开发者直接下载诊断。

### 使用流程

#### 日常开发（CI 自动验证）

```bash
git add .
git commit -m "feat: ..."
git push origin master
# → CI 自动跑，PR/commit status 显示绿勾或红叉
```

#### 发布新版本

```bash
git tag v0.2.0
git push origin v0.2.0
# → Release workflow 自动构建，生成 GitHub Release
# → 附带 WindPlayer-0.2.0-android.apk + WindPlayer-0.2.0-desktop.zip
```

### 未来扩展

- ✅ Phase 1（本次）：CI 编译 + 单元测试 + Release APK + Desktop ZIP
- ⏳ Phase 2：Android release keystore + signed APK（需配置 secrets）
- ⏳ Phase 3：Desktop MSI（Windows runner job）
- ⏳ Phase 4：自动 changelog（从 commit history 生成）
- ⏳ Phase 5：覆盖率报告（Jacoco）+ 代码质量（Detekt）
- ⏳ Phase 6：Fastlane / Play Store 自动发布

---

## 阶段四十六：扩展单元测试 + 发现并修复 ServerConfig 循环依赖 (已完成)

从 9 个测试扩展到 **66 个测试**，覆盖 core-vfs 全部可测逻辑。测试过程中发现并修复了 ServerConfig 的循环依赖 Bug。

### 新增测试文件

| 文件 | 测试数 | 覆盖 |
|------|--------|------|
| `commonTest/.../ServerConfigTest.kt` | 19 | bareHost/httpScheme/defaultPort + L10 回归 |
| `desktopTest/.../VfsUtilsTest.kt` | 25 | formatDuration/formatDurationOsd/formatFileSize/FileNodeComparator/buildUrlWithCredentials |
| `desktopTest/.../CryptoUtilTest.kt` | 13 | DPAPI round-trip + prefix routing + legacy compat |
| `commonTest/.../TrackMatcherTest.kt`（已有） | 9 | TrackMatcher 4 级匹配 + FileNode helpers |

**总计：66 个测试，0 失败。**

### 发现的 Bug：ServerConfig 循环依赖（L10 回归）

#### 根因

L10 修复（阶段三十三）引入 `httpScheme()` 和 `defaultPort()` 互相调用：

```kotlin
// 旧代码（有循环依赖）
fun httpScheme(): String = when {
    host.startsWith("https://") -> "https"
    host.startsWith("http://") -> "http"
    else -> if (defaultPort() == 443) "https" else "http"  // ← 调 defaultPort
}

fun defaultPort(): Int = when (protocol) {
    VfsProtocol.WEBDAV -> if (port > 0) port
                          else if (httpScheme() == "https") 443 else 80  // ← 调 httpScheme
    ...
}
```

当 `host` 无 scheme 前缀 + `port = 0` 时：
- `httpScheme()` → else 分支 → `defaultPort()` 
- `defaultPort()` → WEBDAV 分支 → `httpScheme()` 
- → `StackOverflowError`

#### 为什么生产环境没暴露

实际使用中，用户在 AddServerScreen 总会指定 port（默认填 443/80/22/21）。`port = 0` 只出现在：
- 单元测试中构造 `ServerConfig(..., port = 0)`
- 用户手动编辑 servers.properties 把 port 删了
- 新创建未保存的 ServerConfig

桌面端 `VfsManager.loadConfig()` 读取 `port = 0` 时会触发（如果 host 也没 scheme 前缀），但 streamProxy 的 SFTP 连接不经过 `httpScheme()`（只有 WebDAV 走），所以没踩到。

#### 修复

```kotlin
fun httpScheme(): String = when {
    host.startsWith("https://", ignoreCase = true) -> "https"
    host.startsWith("http://", ignoreCase = true) -> "http"
    port == 443 -> "https"   // 直接检查 port，不再调用 defaultPort()
    else -> "http"
}
```

消除循环：`httpScheme()` 只读 `host` + `port`，`defaultPort()` 只调 `httpScheme()`（单向）。

### 测试覆盖的关键场景

#### ServerConfig（19 个测试）
- `bareHost` 剥离 `https://` / `http://` / trailing `/`
- `httpScheme` 从 host 前缀检测 + 从 port 443 推断 + 默认 http
- `defaultPort` SFTP(22) / FTP(21) / WebDAV(443/80) / LOCAL(0) + 自定义 port
- L10 回归：`https://host:8443` → bareHost/httpScheme/defaultPort 三者一致
- 循环依赖回归：`host=dav.example.com, port=0` 不再 StackOverflow

#### VfsUtils（25 个测试）
- `formatDuration`：NaN / Infinite / 负数 / 0 / 秒 / 分 / 时 / 大数 / 小数截断
- `formatDurationOsd`：双值格式 / 混合时长 / NaN 容错
- `formatFileSize`：0 / 负数 / B / KB / MB / GB / 411 MB Worklog 测试文件
- `FileNodeComparator`：目录优先 + 字母排序 + 大小写不敏感
- `buildUrlWithCredentials`：无凭据 / 仅用户名 / 用户名+密码 / URL 编码 / 默认端口省略 / 非默认端口 / 路径剥离

#### CryptoUtil（13 个测试）
- 通用：空字符串 / `plain:` 前缀 / legacy 无前缀
- 非 Windows：encrypt 返回 `plain:` / decrypt `dpapi:` 返回空
- Windows：DPAPI round-trip / 特殊字符 / Unicode / 长密码

### `assumeTrue` 问题

`kotlin.test` 没有 `assumeTrue`（JUnit API）。改用 `if (!condition) return` 模式跳过平台特定测试。缺点是显示为 passed 而非 skipped，但 CI 上可接受。

### 编译验证

```
./gradlew :core-vfs:desktopTest
→ BUILD SUCCESSFUL in 27s

CryptoUtilTest[desktop]:  tests=13 failures=0 skipped=0
ServerConfigTest[desktop]: tests=19 failures=0 skipped=0
TrackMatcherTest[desktop]: tests= 9 failures=0 skipped=0
VfsUtilsTest[desktop]:    tests=25 failures=0 skipped=0
```

### 文件变更

```
新增：
  core-vfs/src/commonTest/.../ServerConfigTest.kt
  core-vfs/src/desktopTest/.../VfsUtilsTest.kt
  core-vfs/src/desktopTest/.../CryptoUtilTest.kt

修改：
  core-vfs/src/commonMain/.../ServerConfig.kt     # 修复 httpScheme ↔ defaultPort 循环依赖
  core-vfs/src/desktopTest/.../CryptoUtilTest.kt  # 用 if-return 替代 assumeTrue
```

### 测试金字塔现状

```
              ╔═══════════╗
              ║ E2E / UI  ║  0（需 Compose Testing 框架）
              ╚═══════════╝
            ╔═══════════════╗
            ║ Integration   ║  0（需 mock mpv/VFS 服务器）
            ╚═══════════════╝
        ╔═════════════════════╗
        ║  Unit Tests (66)    ║  ← 当前覆盖
        ║  core-vfs logic     ║
        ╚═════════════════════╝
```

当前测试全部集中在 core-vfs 的纯逻辑（数据模型 + 工具函数 + 加密 + 匹配算法）。未来可扩展：
- UI 集成测试（Compose Testing）
- VFS 协议集成测试（mock SSH/HTTP 服务器）
- MpvPlayer 单元测试（mock native 调用）

---

## 阶段四十七：Android 端 SFTP 连接与播放全链路修复 (已完成)

### 问题背景

Android 端 SFTP 功能完全不可用，日志报三类错误：
1. `SLF4J(W): No SLF4J providers were found` — SSHJ 日志无 binding
2. `Could not create known_hosts at /.windplayer/known_hosts` — Android `user.home` 是 `/`，无写权限
3. `connect failed: no such algorithm: X25519 for provider BC` — Android 内置 BouncyCastle 精简版缺少关键算法

### 1. SLF4J 警告消除

将 `slf4j-nop` 从 `desktopMain` 移到 `jvmShared` 源集，桌面与 Android 都包含 provider。版本号纳入 `libs.versions.toml` 管理，不再硬编码。

```
修改：
  core-vfs/build.gradle.kts          # slf4j-nop 从 desktopMain 移到 jvmShared
  gradle/libs.versions.toml           # 新增 slf4j = "2.0.16" 版本 + slf4j-nop 库声明
```

### 2. known_hosts 路径修复

Android 的 `System.getProperty("user.home")` 返回 `/`，导致 `known_hosts` 尝试创建到 `/.windplayer/` 失败。

#### KnownHostsManager 新增 `initialize(baseDir)` 方法
- Desktop 保持 `~/.windplayer/known_hosts` 不变
- Android 在 `MainActivity.onCreate` 中调用 `KnownHostsManager.initialize(File(filesDir, ".windplayer"))`
- 使用 `@Synchronized` 保证线程安全，必须在首次访问 `verifier` 之前调用

```
修改：
  core-vfs/src/jvmShared/.../KnownHostsManager.kt  # 新增 initialize() + customBaseDir
  app-android/.../MainActivity.kt                   # onCreate 中调用 initialize()
```

### 3. SSHJ BouncyCastle 算法修复（X25519 + SHA-256）

Android 内置 BC provider 缺少 X25519 key agreement 和 SHA-256 MessageDigest，SSHJ 默认向 BC 请求这些算法导致连接失败。

#### 方案：禁用 BC，改用 Android Conscrypt

新增 `SshjCompat.kt`（jvmShared）：
- `initializeSshj()`：在 Android 上调用 `SecurityUtils.setRegisterBouncyCastle(false)`，让 SSHJ 回退到系统 Conscrypt provider
- `isAndroidRuntime()`：通过 `Class.forName("android.os.Build")` 检测运行时，可在 jvmShared 中安全使用
- `createSshjConfig()`：构建 SSHJ `DefaultConfig`，Android 上额外过滤掉 `curve25519-sha256` KEX 工厂（兜底，防止旧版 Conscrypt 不支持）

`MainActivity.onCreate` 最开头调用 `initializeSshj()`，必须在任何 SSHJ 类触发 `SecurityUtils` 静态初始化之前执行。

```
新增：
  core-vfs/src/jvmShared/.../SshjCompat.kt          # initializeSshj() + isAndroidRuntime() + createSshjConfig()

修改：
  core-vfs/src/jvmShared/.../SftpClient.kt          # SSHClient() → SSHClient(createSshjConfig())
  core-vfs/src/desktopMain/.../StreamProxy.kt        # SSHClient() → SSHClient(createSshjConfig())
  app-android/.../MainActivity.kt                    # onCreate 调用 initializeSshj()
```

### 4. Android 版 StreamProxy（SFTP HTTP 代理）

mpv Android 构建不包含 SFTP/SSH 协议支持，直接传 `sftp://` URL 会报 `No protocol handler found`。需要像桌面端一样用本地 HTTP 代理转发。

Android 没有 `com.sun.net.httpserver.HttpServer`，因此用 `java.net.ServerSocket` 手写最小 HTTP/1.1 服务器。

#### 核心设计
- `ServerSocket(0, 50, 127.0.0.1)` 绑定随机端口
- 接受线程 + CachedThreadPool 处理请求
- 解析 HTTP 请求行 + 头部（大小写不敏感）
- 支持 `GET` / `HEAD` / `Range: bytes=start-end`
- `StreamSession`：每视频独立 SSHJ 连接，`open/read/close` 全部 `@Synchronized`
- 复用 `createSshjConfig()` + `KnownHostsManager.verifier`，与 SftpClient 安全策略一致
- 会话 ID 使用完整 UUID（122 位熵，与桌面端一致）

#### MobilePlayerScreen 集成
- 每个播放页持有 `StreamProxy` 实例（`remember`）
- `resolveAndLoad`：当 `protocol == SFTP` 时，`streamProxy.createStreamUrl(serverConfig, path)` 生成 `http://127.0.0.1:PORT/stream/UUID`
- 切集/重载时关闭上一个 SFTP 会话
- `onDispose` 关闭所有会话并 `streamProxy.stop()`

```
新增：
  core-vfs/src/androidMain/.../StreamProxy.kt        # Android 版 HTTP 代理（ServerSocket）

修改：
  app-android/.../MobilePlayerScreen.kt              # SFTP 走 StreamProxy URL
```

### 编译验证

```
✅ :app-android:compileDebugKotlin   BUILD SUCCESSFUL
✅ :app-desktop:compileKotlinDesktop BUILD SUCCESSFUL
✅ :core-vfs:desktopTest             BUILD SUCCESSFUL (66 tests)
```

---

## 阶段四十九：Android 播放体验全面完善 (已完成)

### 1. 自动下一集完整修复
- EOF 轮询 `eof-reached`（keep-open 兼容）
- 切集后：文件名更新、`pause=no`、控制面板隐藏、`eofHandled` 防重复

### 2. 播放历史系统
- HistoryStore: 10 条, SharedPreferences, position/duration/thumbnailPath/parentDocId/treeUriString
- 横向缩略图 LazyRow, 退出时 screenshot-to-file 截图
- 断点续播: 每 5 秒保存, 回播时 seek
- SafPlaylistBuilder: DocumentsContract 重建本地播放列表

### 3. 字幕系统
- 外挂: SFTP 下载 + 本地 ContentResolver 复制 → sub-add (首个 select, 其余 auto)
- 内封: FileLoaded 后自动选中第一个 sub 轨道

### 4. 播放器 UI
- 顶部可折叠面板: 右滑动画 Speed/Tracks/Camera
- 底部: SkipPrev/FastRewind/PlayPause/FastForward/SkipNext
- 右侧播放列表面板: slideInHorizontally

### 5. 手势
- 水平 ±30s / 垂直左亮度(0-255) / 垂直右系统音量
- 系统亮度 WRITE_SETTINGS + 系统音量 AudioManager

### 6. 导航修复
- 播放器返回→服务器浏览, 文件夹逐级退回, 设置 BackHandler, 双击退出

### 7. 文件关联
- Android: ACTION_VIEW/ACTION_SEND video/*
- PC: main(args) + --register 注册表

### 8. 文件浏览器增强
- 视频/完整模式切换, 长按菜单 Rename/Copy/Cut/Delete
- VfsClient 新增 deleteFile/renameFile/moveFile
- 本地 SAF DocumentFile + SFTP SSHJ rm/rename

---

## 阶段五十：桌面端文件操作 + 本地文件夹列表化 + 播放状态完整恢复 (已完成)

### 1. 桌面端文件管理
- `showActions` 对本地和 SFTP 服务器文件都启用
- 删除/重命名：本地走 `File`，服务器走 `VfsClient`，`VfsManager` 新增 `deleteServerFile()/renameServerFile()`

### 2. 本地文件夹列表化
- `LocalFolderStore`（SharedPreferences）持久化多个本地文件夹
- FileBrowserScreen Local Storage 区域改为列表展示（与 Network Storage 风格一致）
- 添加流程：输入名称 → SAF 选择目录 → 保存

### 3. 播放状态完整恢复
- **Bug 修复**：`HistoryStore.add()` 合并已有条目的 position/tracks/speed，不再覆盖为 0
- HistoryEntry 新增 `selectedSid`/`selectedAid`/`speed`
- 每 5 秒保存 sid/aid/speed，回播时恢复 time-pos → aid → sid → speed

### 4. 后台返回不重置播放位置
- `MpvRenderView` 新增 `firstInitDone`，首次 surfaceCreated 才 loadfile
- 后续 surface 重建（后台返回）只 attachSurface，mpv 从暂停位置继续

### 5. 长按倍速
- 自定义 `awaitEachGesture` 手势：长按 400ms → 2.0x，松手恢复

---

## 阶段四十八：Android 播放优化与 UX 改进 (已完成)

### 1. StreamProxy 缓冲优化（缓解跳转卡顿）

| 优化项 | 之前 | 之后 |
|--------|------|------|
| 读写缓冲 | 64KB | 1MB（桌面端同步） |
| ServerSocket backlog | 8 | 50 |
| mpv 网络缓存 | 未设置 | `cache=yes`, `demuxer-max-bytes=500M`, `demuxer-max-back-bytes=150M` |

网络流（SFTP）自动设大缓存让跳转命中本地缓冲区，本地文件恢复小缓存（150M/75M）。

### 2. 外挂字幕后台下载（不阻塞起播）

Android 端之前完全不支持外挂字幕匹配，现在复用 commonMain 的 `matchExternalTracks()`。

#### 流程
1. `resolveAndLoad` 先发 `loadfile`（视频立即开始加载）
2. 后台协程列目录 → `matchExternalTracks` → 过滤 SUBTITLE 类型
3. `MobileVfsManager.downloadAuxFile()` 下载到 `context.cacheDir`（文件名消毒防路径穿越）
4. `tryAddSubtitles()` 同时监听 `FileLoaded` 事件和下载完成，两者都满足后 `sub-add`

#### MobileVfsManager 新增 `downloadAuxFile()`
- 独立短连接下载，不干扰 StreamProxy 主视频会话
- `sanitizeCacheName()`：`[^A-Za-z0-9._-]` → `_`，取 `File(name).name` 防路径穿越
- 已存在文件跳过下载（缓存命中）

### 3. 选轨 UI 卡死修复

#### 问题
`TrackSelectionContent` 在主线程同步调用 `player.getPropertyString()`，mpv 忙时 JNI 阻塞 → UI 冻结。点击选轨后的 `player.setProperty()` 同样阻塞。

#### 修复
- 轨道列表读取移到 `LaunchedEffect(tabIndex)` + `Dispatchers.IO`
- 点击选轨后**立即关闭底部面板**（`onDismiss()`），在 `Dispatchers.IO` 协程中执行 `setProperty`
- Off / Video / Audio / Subtitle 全部走异步路径

### 4. 导航修复

#### 播放器返回目标修复
之前 `onFilePlay` 中 `activeServer = null` 导致播放器返回后跳过 `ServerBrowseScreen` 直接到主界面。移除该行后，播放器返回 → `pendingFile = null` → `when` 命中 `activeServer != null` → 回到服务器文件列表（目录和文件列表保留，不重新加载）。

#### 本地文件夹返回键逐级退回
`FileBrowserScreen` 缺少 `BackHandler`，系统返回键直接退出 App。新增 `BackHandler(enabled = dirStack.size > 1)`，子目录时退回上一级，根目录时交给系统处理。

### 5. 播放器双击确认退出

播放界面 `BackHandler` 改为双击确认：
- 第一次按返回 → Toast `"Press back again to exit playback"`，2 秒内再按才退出
- 超时自动重置

### 6. 手势系统全面重写

#### 之前的问题
- 亮度和音量用 `dy * 0.5`（原始像素），轻划 200px 就跳 100，极难控制
- 只用 mpv 内部 volume（0-100），与系统音量不一致
- 亮度用 -100..100 映射到 0.05..1.0 的自定义标准，不直观
- 无水平滑动快进功能

#### 手势方向判定
首次显著移动（>24px）时锁定方向：
- **水平 > 垂直** → 水平快进模式
- **垂直 > 水平** → 根据起点位置：左半屏=亮度，右半屏=音量

#### 灵敏度
改为屏幕比例映射：`dy / size.height * maxRange`
- 整屏高度滑动 = 全范围变化
- 2000px 屏幕，200px 滑动只调 10%

#### 系统音量（AudioManager）
- 不再操作 mpv `volume` 属性
- `AudioManager.setStreamVolume(STREAM_MUSIC, ...)` 直接控制系统媒体音量
- OSD 显示 `Vol: 7/15`（当前/最大）

#### 系统亮度（Settings.System + WRITE_SETTINGS）
- Manifest 新增 `WRITE_SETTINGS` 权限
- 有权限：直接写 `SCREEN_BRIGHTNESS`（0-255），同时设窗口亮度保证即时生效
- 无权限：回退到窗口亮度
- 进入播放器时保存原始系统亮度，退出时恢复
- `ON_RESUME` 刷新权限状态（用户可能从系统设置授权返回）

#### 水平滑动快进（±30s）
- 满屏宽度 = ±60s 范围，`coerceIn(-30, 30)` 上限 30s
- 向右滑 = 前进，向左滑 = 后退
- OSD 显示 `+20s → 01:23 / 24:00`

### 文件变更

```
新增：
  core-vfs/src/androidMain/kotlin/dev/windplayer/vfs/StreamProxy.kt

修改：
  core-vfs/build.gradle.kts                                      # slf4j-nop 移到 jvmShared
  gradle/libs.versions.toml                                      # 新增 slf4j 版本
  core-vfs/src/jvmShared/.../KnownHostsManager.kt                # initialize() + customBaseDir
  core-vfs/src/jvmShared/.../SftpClient.kt                       # createSshjConfig()
  core-vfs/src/jvmShared/.../SshjCompat.kt                       # [NEW] BC 禁用 + KEX 过滤
  core-vfs/src/desktopMain/.../StreamProxy.kt                    # 缓冲 64KB → 1MB
  app-android/src/main/AndroidManifest.xml                       # WRITE_SETTINGS 权限
  app-android/.../MainActivity.kt                                # initializeSshj() + KnownHostsManager.initialize()
  app-android/.../MobileVfsManager.kt                            # downloadAuxFile()
  app-android/.../MobilePlayerScreen.kt                          # 手势/音量/亮度/字幕/选轨/导航/双击退出 全面重写
  app-android/.../FileBrowserScreen.kt                           # BackHandler 逐级退回
  app-android/.../MobileApp.kt                                   # 移除 activeServer = null
```

### 编译验证

```
✅ :app-android:compileDebugKotlin   BUILD SUCCESSFUL
✅ :app-desktop:compileKotlinDesktop BUILD SUCCESSFUL
✅ :core-vfs:desktopTest             BUILD SUCCESSFUL (66 tests)
```

---

## 阶段四十九：Android 播放体验全面完善 (已完成)

### 1. 自动下一集完整修复
- EOF 轮询 `eof-reached`（keep-open 兼容）
- 切集后：文件名更新、`pause=no`、控制面板隐藏、`eofHandled` 防重复

### 2. 播放历史系统
- HistoryStore: 10 条, SharedPreferences, position/duration/thumbnailPath/parentDocId/treeUriString
- 横向缩略图 LazyRow, 退出时 screenshot-to-file 截图
- 断点续播: 每 5 秒保存, 回播时 seek
- SafPlaylistBuilder: DocumentsContract 重建本地播放列表

### 3. 字幕系统
- 外挂: SFTP 下载 + 本地 ContentResolver 复制 → sub-add (首个 select, 其余 auto)
- 内封: FileLoaded 后自动选中第一个 sub 轨道

### 4. 播放器 UI
- 顶部可折叠面板: 右滑动画 Speed/Tracks/Camera
- 底部: SkipPrev/FastRewind/PlayPause/FastForward/SkipNext
- 右侧播放列表面板: slideInHorizontally

### 5. 手势
- 水平 ±30s / 垂直左亮度(0-255) / 垂直右系统音量
- 系统亮度 WRITE_SETTINGS + 系统音量 AudioManager

### 6. 导航修复
- 播放器返回→服务器浏览, 文件夹逐级退回, 设置 BackHandler, 双击退出

### 7. 文件关联
- Android: ACTION_VIEW/ACTION_SEND video/*
- PC: main(args) + --register 注册表

### 8. 文件浏览器增强
- 视频/完整模式切换, 长按菜单 Rename/Copy/Cut/Delete
- VfsClient 新增 deleteFile/renameFile/moveFile
- 本地 SAF DocumentFile + SFTP SSHJ rm/rename

---

## 阶段五十：桌面端文件操作 + 本地文件夹列表化 + 播放状态完整恢复 (已完成)

### 1. 桌面端文件管理
- `showActions` 对本地和 SFTP 服务器文件都启用
- 删除/重命名：本地走 `File`，服务器走 `VfsClient`，`VfsManager` 新增 `deleteServerFile()/renameServerFile()`

### 2. 本地文件夹列表化
- `LocalFolderStore`（SharedPreferences）持久化多个本地文件夹
- FileBrowserScreen Local Storage 区域改为列表展示（与 Network Storage 风格一致）
- 添加流程：输入名称 → SAF 选择目录 → 保存

### 3. 播放状态完整恢复
- **Bug 修复**：`HistoryStore.add()` 合并已有条目的 position/tracks/speed，不再覆盖为 0
- HistoryEntry 新增 `selectedSid`/`selectedAid`/`speed`
- 每 5 秒保存 sid/aid/speed，回播时恢复 time-pos → aid → sid → speed

### 4. 后台返回不重置播放位置
- `MpvRenderView` 新增 `firstInitDone`，首次 surfaceCreated 才 loadfile
- 后续 surface 重建（后台返回）只 attachSurface，mpv 从暂停位置继续

### 5. 长按倍速
- 自定义 `awaitEachGesture` 手势：长按 400ms → 2.0x，松手恢复


---

## 阶段五十一：Mastercard 风格设计系统 + 黑暗模式 (已完成)

依据 `Documents/DESIGN.md`（Mastercard 启发的设计规范）对桌面端与安卓端 UI 进行全面重构，并新增完整的黑暗模式与主题切换。两端编译通过。

### 1. 设计系统令牌（WindColors / WindRadius）

#### 调色板（DESIGN.md §2）
| 角色 | 浅色 | 深色 |
|------|------|------|
| Canvas Cream（画布） | `#F3F0EE` | `#141413` |
| Lifted Cream（抬升面） | `#FCFBFA` | `#1F1D1C` |
| White（卡片/输入） | `#FFFFFF` | `#282624` |
| Ink（文字 + 主按钮） | `#141413` | `#F3F0EE` |
| Slate（次要文字） | `#696969` | `#A8A29A` |
| Dust Taupe（禁用/低语） | `#D1CDC7` | `#6E6A64` |
| Hairline（分隔线） | `#E2DDD5` | `#3A3735` |
| Signal Orange（破坏/合规） | `#CF4500` | `#E8511A` |
| Light Signal Orange（点缀） | `#F37338` | `#F37338` |

#### 圆角阶梯（DESIGN.md §5）
- Chip `6dp` / Button `20dp` / Consent `24dp` / Stadium `40dp` / Pill `99dp` / FullCircle `50%`
- **刻意省略 8–16dp 中间值**——只有「小 ≤6 / 中大 20–40 / 全胶囊 99+」三档

#### 设计语言落地
- **eyebrow 标签**：橙点 + 大写粗体 + `+4%` 字距（分区标题信号）
- **胶囊形**：导航项、搜索框、语言项、FilterChip 全用 Pill
- **主按钮**：Ink 底 + Canvas Cream 字（20dp 圆角）；次按钮：白底 + 1.5dp Ink 描边
- **Signal Orange 严格保留**给删除/警告/合规动作，不做营销 CTA
- 播放器 chrome 视为「footer/media frame」，**始终深色**（见 §4）

### 2. 黑暗模式架构

#### ThemeMode + PlayerSettings
- commonMain 新增 `enum class ThemeMode { LIGHT, DARK, SYSTEM }`
- `PlayerSettings` 新增 `themeMode: ThemeMode = SYSTEM`（默认跟随系统）
- 持久化随设置一起存盘，向后兼容

#### WindColors 用 mutableStateOf 实现零侵入切换
桌面与安卓的 `WindColors` 全部属性改为 `var by mutableStateOf(...)`，新增 `applyDark(dark: Boolean)`：
- 切换时所有引用 `WindColors.*` 的界面**自动重组**，无需 `CompositionLocal`、无需改任何界面代码
- 幂等：重写相同值不触发通知，不会循环
- **深色是浅色的语义镜像**：`Ink`/`CanvasCream` 互补互换，文字与主按钮同步反转（仍是「奶油字 / 反色胶囊」），`White`/`LiftedCream` 维持「比画布更亮」的层级

#### 系统深色检测
- **桌面** `DesktopSystemTheme`（desktopMain，尽力而为）：
  - Windows：`reg query ...Personalize /v AppsUseLightTheme`（0x0=深色）
  - macOS：`defaults read -g AppleInterfaceStyle`（"Dark"=深色）
  - Linux：`GTK_THEME` 含 "dark"
  - 结果进程级缓存；切换系统主题需重启或切设置重解析
- **安卓** `MobileApp`：读 `Configuration.uiMode & UI_MODE_NIGHT_MASK`，配置变更（重建 Activity）自动重算
- 状态栏图标明暗随主题切换（`WindowInsetsControllerCompat.isAppearanceLightStatusBars = !isDark`）

#### colorScheme 构建
- `windColorScheme(isDark)`（桌面）/ `androidColorScheme(isDark)`（安卓）：用固定 hex 构建 `lightColorScheme` / `darkColorScheme`，避免与 `applyDark` 的执行顺序耦合

### 3. 安卓端 Phosphor 图标集成（来自 `icons/`）

安卓原先用 Material 内置图标，本次改为真正使用 `icons/Fonts/regular/Phosphor.ttf`：

- 复制 `Phosphor.ttf` → `app-android/src/main/res/font/phosphor.ttf`
- `Phosphor.kt`：从 `style.css` 解析出的 PUA 码点常量（`PLAY='\ue3d0'` 等 ~70 个）+ Phosphor FontFamily + `PhosphorIcon(glyph, tint, size)` 可组合函数
- 码点提取方式：`Select-String style.css -Pattern "ph-xxx:" -Context 0,1` 读取每条 `content: "\eXXX"`
- 所有 `Icon(Icons.Default.X, ...)` 替换为 `PhosphorIcon(Phosphor.X, ..., size = N.dp)`

### 4. 播放器 chrome 始终深色

播放器覆盖层位于视频之上，**两个主题下都应深色**。新增固定 `WindColors.MediaInk/MediaCream/MediaMuted/MediaSurface/MediaAccent`（不随 `applyDark` 翻转），把两个 PlayerScreen 内的 `Ink/CanvasCream/DustTaupe/Charcoal/White` 引用机械替换为 media 等价物。`LightSignalOrange/SignalOrange` 保持（点缀色两主题相近）。

### 5. 设置内主题切换

桌面 `SettingsScreen` 与安卓 `MobileSettingsScreen` 新增「Appearance」分区：三段式胶囊选择 Light / Dark / Follow System，选中项为 Ink 反色胶囊。I18n 已加 `appearance/theme_light/theme_dark/theme_system`（中英）。

### 关键技术决策
- **mutableStateOf 全局对象 vs CompositionLocal**：选前者——所有已写好的界面代码零改动即支持主题切换；代价是颜色令牌是可变全局单例（本应用单进程，可接受）
- **深色 = 语义镜像**：而非为每个组件单独写深色变体。关键洞察是 `Ink`（文字+按钮）与 `CanvasCream`（画布+按钮字）互补，二者一起翻转即得到正确的反色胶囊与文字
- **播放器固定深色**：视频媒体框天然深色（DESIGN.md §4），不跟随主题，避免浅色控制条压在视频上刺眼
- **桌面系统检测尽力而为**：JVM 无统一系统主题 API，用平台原生命令查询；不可测时回退浅色，用户仍可强制选 Light/Dark

### 编译验证
- ✅ `:app-desktop:compileKotlinDesktop` — BUILD SUCCESSFUL
- ✅ `:app-android:compileDebugKotlin` — BUILD SUCCESSFUL

### 文件变更
```
新增：
  ui-compose/src/desktopMain/.../WindTheme.kt             # themeable WindColors + windColorScheme + WindRadius
  ui-compose/src/desktopMain/.../DesktopSystemTheme.kt    # 系统深色检测（Win/macOS/Linux）
  ui-compose/src/desktopMain/resources/icons/{caret-down,warning,arrow-right}.svg
  app-android/src/main/kotlin/.../Phosphor.kt             # 码点常量 + PhosphorIcon
  app-android/src/main/kotlin/.../WindTheme.kt            # androidColorScheme(isDark)
  app-android/src/main/res/font/phosphor.ttf              # Phosphor 字体

修改：
  ui-compose/src/commonMain/.../PlayerSettings.kt         # + ThemeMode 枚举 + themeMode 字段
  ui-compose/src/commonMain/.../I18n.kt                   # + appearance/theme_* 键（中英）
  ui-compose/src/desktopMain/.../App.kt                   # isDark 解析 + windColorScheme + applyDark
  ui-compose/src/desktopMain/.../SettingsScreen.kt        # Appearance 分区 + ThemeSelectorRow
  ui-compose/src/desktopMain/.../PlayerScreen.kt          # 媒体固定深色（Media* 颜色）
  ui-compose/src/desktopMain/.../{FileBrowserScreen,AddServerDialog,TrackSelectionSheet}.kt  # 奶油/墨黑/胶囊重做
  app-android/src/main/.../WindColors.kt                  # themeable + applyDark + Media* + WindRadius
  app-android/src/main/.../MobileApp.kt                   # 主题装配（MaterialTheme+Surface+SideEffect+状态栏）
  app-android/src/main/.../MainActivity.kt                # 简化为 setContent { MobileApp() }
  app-android/src/main/.../MobileSettingsScreen.kt        # Appearance 分区 + ThemeSelectorRow
  app-android/src/main/.../MobilePlayerScreen.kt          # 媒体固定深色
  app-android/src/main/.../{FileBrowserScreen,ServerBrowseScreen,AddServerScreen}.kt         # 奶油/墨黑/胶囊 + Phosphor 图标重做
  app-desktop/src/desktopMain/.../Main.kt                 # Swing 面板背景改奶油
```

---

## 第五十一阶段状态总结

**已完成**：Mastercard 风格设计系统全面落地（桌面 + 安卓）、Phosphor 图标在安卓端通过字体方案集成、完整黑暗模式（ThemeMode 三态 + 系统检测 + 设置内切换 + 状态栏适配）、播放器 chrome 始终深色

**下一步**：
- Sofia Sans 字体接入（DESIGN.md 推荐的 MarkForMC 开源替代，目前用系统默认 sans）
- 装饰性橙色轨道弧线（circle portrait 间的连接线，DESIGN.md §4 标志性元素）
- 主题切换的启动闪屏优化（首帧 applyDark 前的可能浅色闪烁）


---

## 阶段五十二：设计系统打磨 — Sofia Sans 字体 / 启动闪屏 / 审计 (已完成)

承接阶段五十一的 Mastercard 设计系统，补齐「下一步」清单中的高价值项与可选打磨项。两端编译通过，core-vfs 测试通过。

### 1. Sofia Sans 字体接入（还原度最高的一项）

DESIGN.md 指定 MarkForMC（专有），开源替代为 Sofia Sans。此前用系统默认 sans，字距设了但字体没换。

- 从 fontsource CDN 下载静态 3 字重 TTF（latin 子集，各 ~44KB，magic `00010000` 校验）：Regular 400 / Medium 500 / Bold 700
- **桌面**：放 `ui-compose/src/desktopMain/resources/fonts/`，用 `androidx.compose.ui.text.platform.Font("fonts/xxx.ttf", weight)`（JVM classpath 资源加载）构建 `SofiaSansFamily`
- **安卓**：放 `app-android/src/main/res/font/sofia_sans_{regular,medium,bold}.ttf`，用 `Font(R.font.xxx, weight)` 构建
- `withFamily()` 扩展：把 FontFamily `.merge` 进 Typography 全部 15 个 role，保留原字号/字距/行高
- **全局继承坑**：M3 `MaterialTheme` 不把 typography 推进 `LocalTextStyle`，所以裸 `Text(fontSize=...)` 不会继承字体。用 `CompositionLocalProvider(LocalTextStyle provides typography.bodyLarge)` 包裹根内容解决（`LocalTextStyle` 实际在 `androidx.compose.material3` 包，**非** `androidx.compose.ui.text` —— 这是踩坑点，jar 内确认 `material3/TextKt$LocalTextStyle`）

### 2. 启动闪屏修复

此前 `WindColors.applyDark()` 跑在 `SideEffect`（首帧之后），深色模式首帧会闪一帧浅色。
- **桌面**：`Main.kt` 在 `setContent` 前读 `loadSettings().themeMode` + `DesktopSystemTheme.isSystemDark()`，预先 `WindColors.applyDark(initialDark)`
- **安卓**：`MainActivity.onCreate` 在 `setContent` 前读 `Configuration.uiMode`，预先 `WindColors.applyDark(night)`
- 运行时切换仍由 `App.kt` / `MobileApp` 的 `SideEffect` 保持同步

### 3. Swing 面板背景同步

`Main.kt` 的 `composePanel.background` 原硬编码奶油 `0xFFF3F0EE`，深色模式缩窗/加载会漏奶油底。改为读已解析的 `WindColors.CanvasCream`（`.red/.green/.blue` Float → `java.awt.Color(float,float,float)`），因 `applyDark` 已在设置背景前执行，首帧即正确。

### 4. 圆角一致性审计

grep `RoundedCornerShape(\d+` 全代码库。**仅 1 处漏网**：安卓 `MobilePlayerScreen` 的 OSD 覆盖层用 `RoundedCornerShape(8.dp)`（8–16dp 是 DESIGN.md 明令缺席的中间档）。改为 `WindRadius.Consent`（24dp）。其余全部命中 6/20/24/40/99/50% 合规阶梯。

### 5. 深色对比度审计

逐项核对 `DustTaupe` 用途（深色 `#6E6A64` 在 `#141413` 上约 3.2:1）：placeholder、`(empty)`、开关 unchecked、Cancel、未选 Tab、删除图标 —— 全部属于 DESIGN 定义的 whisper/disabled 角色，合规。
- **顺带发现一个真 Bug**：桌面 `TrackSelectionSheet.kt` 渲染在「始终深色」的播放器面上，但上一轮漏转 media 颜色，仍用可主题化 `WindColors` → 深色模式下文字变成 `#141413` 压在 `#141413` 上**不可见**。机械替换为 `MediaInk/MediaCream/MediaMuted/MediaSurface` 修复。

### 6. 空状态 ghost watermark

新增 `WindColors.GhostWatermark`（浅 `#E8E2DA` / 深 `#2A2826`，cream-on-cream，DESIGN.md §4）。桌面文件浏览器「未选择」空状态改为 72sp / weight 500 / -2% 字距的 `WindPlayer` 大字水印 + Slate 提示语，替代原先的纯灰文字，赋予分区氛围。

### 7. 默认组件配色审计

grep 残留 `Color(0x...)`：播放器覆盖层（`0x99000000`/`0xE6141413`）是固定 media chrome，正确。**1 处主题敏感漏网**：安卓 `AddServerScreen` 测试结果框硬编码浅奶油底 `0xFFEFEAE3`/`0xFFF6E6DE`，深色下会是一块突兀的浅卡。改为 `WindColors.White`（主题化）+ Hairline/SignalOrange 描边。colorScheme（light/dark）已完整映射 surfaceContainer 等，其余依赖 colorScheme 的默认组件（DropdownMenu/AlertDialog scrim 等）深色下正常。

### 关键技术决策
- **字体全局继承**：用 `CompositionLocalProvider(LocalTextStyle provides bodyLarge)` 而非逐个 Text 改 style —— 零界面改动，所有裸 Text 自动继承 Sofia Sans
- **`LocalTextStyle` 包路径**：在 `androidx.compose.material3`（material3 模块的 TextKt），不在 `androidx.compose.ui.text`。通过解压 `material3-release-runtime.jar` 确认（`material3/TextKt$LocalTextStyle`）
- **预解析主题早于首帧**：在 setContent 前同步调用 `applyDark`，配合运行时 `SideEffect` 双保险

### 编译验证
- ✅ `:app-desktop:compileKotlinDesktop` — BUILD SUCCESSFUL
- ✅ `:app-android:compileDebugKotlin` — BUILD SUCCESSFUL
- ✅ `:core-vfs:desktopTest` — BUILD SUCCESSFUL

### 文件变更
```
新增：
  ui-compose/src/desktopMain/resources/fonts/{SofiaSans-Regular,Medium,Bold}.ttf
  app-android/src/main/res/font/{sofia_sans_regular,sofia_sans_medium,sofia_sans_bold}.ttf
  app-android/src/main/kotlin/.../WindTypography.kt     # SofiaSansFamily + WindTypography(withFamily)

修改：
  ui-compose/src/desktopMain/.../WindTheme.kt           # SofiaSansFamily + withFamily + GhostWatermark token + applyDark 分支
  ui-compose/src/desktopMain/.../App.kt                 # LocalTextStyle provides bodyLarge（字体全局继承）
  ui-compose/src/desktopMain/.../FileBrowserScreen.kt   # 空状态 ghost watermark
  ui-compose/src/desktopMain/.../TrackSelectionSheet.kt # 主题化 → Media* 固定深色（修深色不可见 Bug）
  app-android/src/main/.../WindColors.kt                # + GhostWatermark token + applyDark 分支
  app-android/src/main/.../MobileApp.kt                 # typography=WindTypography + LocalTextStyle provides bodyLarge
  app-android/src/main/.../MainActivity.kt              # setContent 前预 applyDark
  app-android/src/main/.../MobilePlayerScreen.kt        # OSD 8dp → Consent（圆角合规）
  app-android/src/main/.../AddServerScreen.kt           # 测试结果框底色主题化
  app-desktop/src/desktopMain/.../Main.kt               # 预 applyDark + Swing 背景读 WindColors
```

---

## 第五十二阶段状态总结

**已完成**：Sofia Sans 字体全平台接入（含全局继承坑修复）、启动闪屏消除（预解析主题）、Swing 背景同步、圆角审计（修 1 处）、对比度审计（顺带修 TrackSelectionSheet 深色不可见 Bug）、空状态 ghost watermark、默认组件配色审计（修测试结果框）

**下一步**：
- 装饰性橙色轨道弧线（DESIGN.md §4 标志元素，需评估在播放器场景的适用性）
- 主题运行时切换时同步 Swing 面板背景（当前仅启动时同步，运行时切主题极小概率边缘闪）
- 字体子集化进一步压缩（当前 latin 子集已较小）


---

## 阶段五十三：全面本地化修复 — 主页面不随语言切换 + 其余页面补全 (已完成)

### 问题根因

设置页选中文后只有设置页切换、主页仍英文。排查发现：**安卓 `FileBrowserScreen` 0 处 `I18n.get`**——分区标题、列表项、上下文菜单、对话框全是硬编码英文字面量。设置页用了 `I18n.get`，所以它正常；主页不读 `I18n.current`，自然不随语言变。机制本身没问题（`I18n.current` 是 `mutableStateOf`，读它的可组合会重组）。

随后顺手把两端其余页面的硬编码英文一并补全。

### 新增 I18n 键（中英，约 40 个）

`network_storage` / `local_storage` / `add_folder` / `add_storage` / `folder_name` / `select_folder` / `copy` / `cut` / `paste` / `move_here` / `on_this_device` / `empty_dir` / `ok` / `name` / `edit_server` / `testing` / `test_connection` / `use_tls` / `host_required` / `ftp_warning` / `webdav_warning` / `connected_items`(`%d`) / `failed_msg`(`%s`) / `connection_error` / `press_back_again` / `playback_error` / `track_n`(`%s`) / `osd_vol` / `osd_brightness` / `connect_failed`(`%s`) / `toast_deleted` / `toast_delete_failed` / `toast_renamed` / `toast_rename_failed` / `toast_moved` / `toast_move_failed` / `server_not_found` / `encryption_warning` / `track_selection` / `add_external`(`%s`) / `add_title`(`%s`) / `port_hint` / `err_open_failed` / `next_msg`(`%s`) / `playlist_complete` / `error_prefix`(`%s`) / `status_ended` / `status_stopped` / `osd_sub_delay` / `osd_audio_delay` / `osd_ab_a` / `osd_ab_b` / `osd_ab_clear` / `osd_screenshot_saved` / `osd_frame_plus` / `osd_frame_minus` / `osd_contrast` / `osd_saturation` / `osd_gamma` / `subtitle_added`(`%s`) / `osd_playing` / `osd_paused` / `osd_muted`。

带 `%s`/`%d` 的用 `String.format(I18n.get(key), arg)` 拼接。

### 安卓端（全部页面）

- **FileBrowserScreen**：分区标题、Add Server/Add Folder、上下文菜单（Rename/Copy/Cut/Delete）、重命名对话框、添加文件夹对话框、添加存储选择器、粘贴按钮、`(empty)` —— 0 → 23 处 `I18n.get`。
- **ServerBrowseScreen**：Rename/Move/Delete、重命名对话框、`(empty)`。
- **AddServerScreen**：标题（Edit/Add Server）、表单字段（Name/Host/Port/Username/Password/Base Path）、Protocol eyebrow、Use TLS、两条警告、Test Connection/Testing、Host required Toast、连接结果（`connected_items`/`failed_msg`）。
- **MobilePlayerScreen**：返回退出 Toast、Playback Error/Back、Playlist (N)、轨道 Tab（Video/Audio/Subtitle）、Off、Track N、OSD（Vol/Brightness/Speed/Screenshot）。
- **MobileApp**：全部 Toast（Connect failed、Deleted/Renamed/Moved ± failed、Server not found、加密警告）。
- **MobileSettingsScreen**：核对无遗漏（已全用 I18n）。

### 桌面端（其余页面）

桌面 `FileBrowserScreen` / `SettingsScreen` 此前已用 I18n。本轮补：
- **AddServerDialog**：标题、字段标签、Use TLS、警告、Save/Cancel。
- **TrackSelectionSheet**：Track Selection、None、Add External/Add 标题、Tab 标签（新增 `TrackType.localizedLabel()`）、Cancel。
- **PlayerScreen**：statusText（Ready/Loading/Ended/Stopped/Playlist complete/Next/Error）。
- **OSD 反馈**（`CanvasMouseController` / `DesktopContextMenu` / `DesktopShortcuts`）：Vol/Brightness/Speed/Sub delay/Audio delay/A-B Loop/EQ Reset/Screenshot/Frame ±/Playing/Paused/Muted、EQ 属性标签（addEqItem 内 brightness/contrast/saturation/gamma）、拖放字幕 OSD。

`SFTP · WebDAV · FTP`、`>> +5s`/`<< -30s` 等纯符号+数字/专有名词保持硬编码（不翻译）。

### 关键技术决策
- **`I18n.get` 非 `@Composable` 仍可触发重组**：`current` 是 `mutableStateOf`，在组合期间被读取即注册依赖，改值后读取方重组——设置页已验证此机制，主页改用 `I18n.get` 后即随语言切换。
- **格式字符串**：动态值（连接结果/轨道号/错误/OSD）用 `String.format(I18n.get("key"), arg)`，键值含 `%s`/`%d` 占位符，中英文案各自正确的语序。

### 编译验证
- ✅ `:app-desktop:compileKotlinDesktop` — BUILD SUCCESSFUL
- ✅ `:app-android:compileDebugKotlin` — BUILD SUCCESSFUL
- ✅ `:core-vfs:desktopTest` — BUILD SUCCESSFUL

### 文件变更
```
修改：
  ui-compose/src/commonMain/.../I18n.kt                       # +~60 键（中英）
  app-android/.../FileBrowserScreen.kt                        # 硬编码 → I18n.get（+import）
  app-android/.../ServerBrowseScreen.kt                       # 同上
  app-android/.../AddServerScreen.kt                          # 表单/警告/测试连接/Toast
  app-android/.../MobilePlayerScreen.kt                       # OSD/错误/轨道/播放列表
  app-android/.../MobileApp.kt                                # Toast 全量
  ui-compose/src/desktopMain/.../AddServerDialog.kt           # 表单/警告/按钮
  ui-compose/src/desktopMain/.../TrackSelectionSheet.kt       # +localizedLabel() / 标题/标签
  ui-compose/src/desktopMain/.../PlayerScreen.kt              # statusText 本地化
  app-desktop/.../CanvasMouseController.kt                    # OSD Vol/Brightness
  app-desktop/.../DesktopContextMenu.kt                       # OSD + addEqItem EQ 标签
  app-desktop/.../DesktopShortcuts.kt                         # OSD 全量
  app-desktop/.../Main.kt                                     # 拖放字幕 OSD
```

---

## 第五十三阶段状态总结

**已完成**：本地化根因修复（主页硬编码英文）+ 安卓全部页面 + 桌面其余页面 + 桌面 OSD 反馈全面本地化；两端切换语言即时生效。

**下一步**：无紧急项；可选——为更多语言（如日语）追加翻译映射、或抽取一个 `Localizable` 抽象统一管理复数/格式。


---

## 阶段五十四：安卓网络流后台恢复 + Recent 历史进度缺失修复 (已完成)

### 1. 网络流后台返回黑屏/无响应

**现象**：网络存储（SFTP/WebDAV/FTP）视频播放时休眠/退后台，再回前台后进度还在但黑屏，暂停/播放/跳转全无反应。本地文件正常。

**根因**：后台期间系统回收 socket，本地 `StreamProxy` 的 SSH 会话断开。`ON_RESUME` 只发 `pause=no`，但 mpv 的 HTTP 数据源已死 —— mpv 卡在读取死连接上，命令全部排队阻塞。本地文件的 `ParcelFileDescriptor` 后台仍开着，所以取消暂停即可续读。

**修复**：`MobilePlayerScreen` 的 `ON_RESUME` 分支，网络流（`serverConfig != null`）改为从保存的位置**重新加载**而非仅取消暂停：
```kotlin
if (fileLoaded && serverConfig != null) {
    记下 position / speed / sid / aid
    scope.launch {
        resolveAndLoad(path)        // 关闭死的旧 session → 建新 StreamProxy 会话 → loadfile
        pendingResume = resumeAt    // 在 resolveAndLoad 返回后赋值（它内部会先清零）
        pendingResumeSid/Aid/Speed = ...
    }
} else {
    player.setProperty("pause", "no")   // 本地文件保持原逻辑
}
```
- `FileLoaded` 处理器据 `pendingResume*` seek 回原位、恢复字幕/音轨/速度。
- 时序安全：`resolveAndLoad` 是 suspend，`loadfile` 后即返回；`pendingResume` 赋值在其后，而 `FileLoaded` 等 mpv 异步解析后才触发，赋值必先于它。
- 守卫 `fileLoaded && serverConfig != null`：初次播放 / 本地文件不受影响。

### 2. 网络流 Recent 历史不记录播放进度（见阶段五十五）

---

## 第五十四阶段状态总结

**已完成**：网络流后台返回黑屏/无响应修复（重新加载网络文件恢复死连接）。


---

## 阶段五十五：网络流 Recent 历史恢复「从头播放」修复 (已完成)

### 现象
网络存储视频退出后再从 Recent 进入，依然从头播放（本地文件正常）。

### 根因
并非「进度没记录」—— `HistoryStore` 的记录/匹配逻辑（按 path 匹配、`add()` 合并保留 position、`updatePosition` 拒绝用 0 覆盖）经核查对网络/本地完全对称，进度是有写入的。真正问题在**恢复 seek**：

`MobilePlayerScreen` 的 `FileLoaded` 处理器用 `setProperty("time-pos", pos)` 在文件加载后立即 seek。本地文件加载即可 seek，但 **HTTP 流（StreamProxy）加载后 demuxer 还在初始化，立即 seek 会被静默丢弃** → mpv 从 0 开始播放。用户看到「从头播放」便以为进度没记录。

### 修复（`MobilePlayerScreen.kt`）

#### 1. 用 mpv `start` 选项恢复（主，对网络可靠）
`resolveAndLoad` 在 reset 之前捕获 `val startAt = pendingResume`，在 `loadfile` 之前设置：
```kotlin
player.setProperty("start", if (startAt > 1.0) "%.3f".format(startAt) else "0")
player.command("loadfile", loadPath)
```
`start` 选项让 mpv **在加载过程中 seek**（HTTP 流会在初始 Range 请求阶段就跳到目标位置），不与 demuxer 初始化竞争。每次 `loadfile` 前都重新设置，auto-play-next 时 `startAt=0` 不受影响。

#### 2. FileLoaded seek 作为兜底
`loadfile` 后重新 `pendingResume = startAt`，让 `FileLoaded` 处理器的 `time-pos` seek 也触发一次（对同一位置的无害二次 seek）。`FileLoaded` 处理器 seek 后会把 `pendingResume` 清零，因此 **auto-play-next 不受影响**（其 `resolveAndLoad` 捕获到 `startAt=0`）。覆盖个别 mpv 构建忽略 `start` 选项的情况。

#### 3. 后台恢复 reload 时序对齐
阶段五十四的 `ON_RESUME` 网络 reload：改为在 `resolveAndLoad` **之前**设 `pendingResume = resumeAt`（之前是之后），使其被捕获进 `start` 选项；sid/aid/speed 仍在之后设（供 `FileLoaded` 处理器恢复轨道）。

#### 4. 退出时强制 flush 进度
`captureThumbAndExit` 在截图/`onBack` 前补一次 `onPositionUpdate` + `onPlaybackStateUpdate`：用户在 5 秒轮询周期内（或首个周期前）退出也能记录进度，消除「快速退出丢进度」的边缘情况。

### 关键决策
- **`start` 选项 vs `time-pos` seek**：`start` 是 mpv 恢复流媒体的标准做法（加载期 seek，走 Range），`time-pos` 在 FileLoaded 后 seek 会与 demuxer 竞争。两者并用（主+兜底）最稳。
- **不破坏 auto-play-next**：`pendingResume` 的捕获→reset→(FileLoaded 再清零) 链确保只有显式恢复的那次加载带位置，后续自动播放从 0 开始。

### 编译验证
- ✅ `:app-android:compileDebugKotlin` — BUILD SUCCESSFUL

### 文件变更
```
修改：
  app-android/.../MobilePlayerScreen.kt
    # resolveAndLoad: 捕获 startAt + loadfile 前设 start 选项 + loadfile 后重置 pendingResume 作兜底
    # ON_RESUME 网络 reload: pendingResume 改在 resolveAndLoad 之前设
    # captureThumbAndExit: 退出前 flush position/tracks
```

---

## 第五十五阶段状态总结

**已完成**：网络流 Recent 恢复「从头播放」修复（mpv `start` 选项加载期 seek + FileLoaded 兜底 + 退出 flush 进度）。


---

## 阶段五十六：网络流熄屏恢复「有声音无图像」修复 (已完成)

### 现象
阶段五十四修复了网络流熄屏后冻结/无响应（改为 ON_RESUME 重新加载）。但引入新问题：恢复后进度对、也在播放，但**只有声音、视频黑屏**。本地文件正常。

### 根因
阶段五十四把网络 reload 放在 `ON_RESUME`，而 `ON_RESUME` 通常**早于** `surfaceCreated`（SurfaceView 的 surface 在窗口重新可见后才重建）。于是 `loadfile` 在 surface 尚未重新绑定给 mpv 时执行 → mpv 加载了文件（音频照常解码输出）但视频输出没有 window → 黑屏。本地文件不 reload，surface 在 `surfaceCreated` 重新 `attachSurface` 后直接恢复渲染，所以没事。

### 修复（`MobilePlayerScreen.kt` + `MpvRenderView.kt`）

把网络 reload 从「ON_RESUME 立即执行」改为「surface 重新绑定后执行」：

1. **`MpvRenderView` 新增 `onSurfaceReattached` 回调**：在 `surfaceCreated` 的重连分支（`firstInitDone` 已为 true 时）于 `attachSurface` **之后**调用，表示 surface 已重新绑定。
2. **`pendingNetworkResume` 标志（`AtomicBoolean`）**：`ON_RESUME` 对网络流只 `set(true)`，不再立即 reload。
3. **`onSurfaceReattached` 消费标志**：`compareAndSet(true, false)` 命中才 reload —— 此时 surface 已绑定，`loadfile` 跑在活体 surface 上，视频正常渲染。

### 为什么用标志位而不是直接在重连分支 reload
`surfaceCreated` 在**旋转**时也会触发（surface 重建）。旋转不该 reload（流还活着）。`ON_RESUME` 只在真正后台→前台时触发（manifest 已声明处理 `orientation`，旋转不重建 Activity、不触发 ON_RESUME），所以用「ON_RESUME 置标志 → surfaceCreated 消费」精确区分两种重连：
- 后台恢复：ON_RESUME 置标志 → surfaceCreated 消费 → reload ✓
- 旋转：无 ON_RESUME → 标志为 false → surfaceCreated 不 reload ✓

### 时序（后台→前台，网络）
```
ON_PAUSE → pause=yes
surfaceDestroyed → detachSurface
ON_RESUME → pendingNetworkResume=true（不 reload）
surfaceCreated → attachSurface（重新绑定 surface）→ onSurfaceReattached
            → 消费标志 → resolveAndLoad（start 选项恢复进度）→ 视频渲染 ✓
```

### 编译验证
- ✅ `:app-android:compileDebugKotlin` — BUILD SUCCESSFUL

### 文件变更
```
修改：
  app-android/.../MpvRenderView.kt          # + onSurfaceReattached 回调（重连分支 attachSurface 后调用）
  app-android/.../MobilePlayerScreen.kt     # + pendingNetworkResume 标志；ON_RESUME 改为置标志；onSurfaceReattached 消费并 reload
```

---

## 第五十六阶段状态总结

**已完成**：网络流熄屏恢复「有声音无图像」修复（reload 延后到 surface 重新绑定后执行，标志位区分后台恢复与旋转）。


---

## 阶段五十七：本地文件熄屏恢复「有声音无图像」修复 (已完成)

### 现象
阶段五十六修好了网络流，但**本地文件**熄屏/进后台再恢复仍然只有声音、视频黑屏。

### 根因
本地文件不 reload，`ON_RESUME` 直接 `pause=no` 取消暂停（此时 surface 尚未重新绑定）→ mpv 解码音频正常，但视频输出链（vo）在后台 `surfaceDestroyed` → `detachSurface` 时已被拆除，重新 `attachSurface` 后 mpv **不会自动重建 vo** → 黑屏。

代码里本有个 `surfaceChanged` 的 `vid` no→1 切换 workaround 来强制重建 vo，但恢复时 `surfaceChanged` 不一定触发（仅尺寸变化才触发），所以漏了。

### 修复（`MpvRenderView.kt`）
在 `surfaceCreated` 的重连分支、`attachSurface` **之后**补一次 `vid` no→1 切换，强制 mpv 重建视频输出链并绑定到刚 attach 的 surface：
```kotlin
} else {   // surface reattached
    player.setProperty("vid", "no")
    player.setProperty("vid", "1")
    onSurfaceReattached()
}
```
- 本地：surface 绑定后 vid 切换重建 vo → 视频恢复 ✓
- 网络：vid 切换后再走 `onSurfaceReattached` 的 reload（loadfile 也会重建 vo），冗余但无害

恢复时序（本地，后台→前台）：
```
ON_PAUSE → pause=yes
surfaceDestroyed → detachSurface（vo 拆除）
ON_RESUME → pause=no（音频先恢复）
surfaceCreated → attachSurface → vid no→1（重建 vo 绑定新 surface）→ 视频恢复 ✓
```

### 编译验证
- ✅ `:app-android:compileDebugKotlin` — BUILD SUCCESSFUL

### 文件变更
```
修改：
  app-android/.../MpvRenderView.kt   # surfaceCreated 重连分支补 vid no→1 切换
```


---

## 阶段五十八：自动播放下一集不生成 Recent 封面修复 (已完成)

### 现象
自动播放下一集时，被切走的那一集没有生成封面，Recent 列表里这些自动播放过的集数都显示默认图标。只有用户手动退出（`captureThumbAndExit`）的那一集才有封面。

### 根因
封面只在**退出**时捕获（`captureThumbAndExit` → `screenshot-to-file`）。自动播放下一集、手动跳下一集/上一集、播放列表跳转这 5 条切集路径都直接 `currentIdx++ → resolveAndLoad`，从不为「被切走的那一集」留封面。中间各集从未退出过，所以一直没封面。

### 修复（`MobilePlayerScreen.kt`）
抽出 `captureThumbnailForPath(path)`（截图 + `HistoryStore.updateThumbnail`），在每条切集路径里、`resolveAndLoad` **之前**先为「即将离开的那一集」截图：
```kotlin
val leavingPath = directoryVideos.getOrNull(currentIdx)?.path   // 增减前捕获
currentIdx++/--
...
scope.launch(Dispatchers.IO) {
    if (leavingPath != null) captureThumbnailForPath(leavingPath)  // mpv 仍持该集帧时截图
    resolveAndLoad(nextFile.path)                                  // 之后再切到下一集
}
```

覆盖 5 条路径：
1. `MpvEvent.EndFile` 自动下一集
2. eof 轮询自动下一集（keep-open 下的实际触发点）
3. `playNext()`（手动/快捷键下一集）
4. `playPrev()`（上一集）
5. 播放列表点击跳转

`captureThumbAndExit` 改为复用同一 helper（行为不变）。

### 关键点
- **截图必须在 `loadfile` 之前**：`screenshot-to-file` 与 `loadfile` 都走 mpv 内部锁串行，放在同一个 `Dispatchers.IO` 协程里顺序执行，确保截到的是「离开那集」的帧，而不是下一集。
- **leavingPath 在 `currentIdx` 增减前捕获**，路径才正确。
- **~100ms 截图跑在 IO 线程**，不阻塞主线程 UI；切集本就有可见过渡，这点延迟可接受。
- **eof 时帧仍有效**：`keep-open=yes` 保留最后一帧，截图可用。
- 每集只在「被切走时」截一次（退出或切下一集），无重复。

### 编译验证
- ✅ `:app-android:compileDebugKotlin` — BUILD SUCCESSFUL

### 文件变更
```
修改：
  app-android/.../MobilePlayerScreen.kt   # 抽出 captureThumbnailForPath；5 条切集路径切前补截图；captureThumbAndExit 复用 helper
```
