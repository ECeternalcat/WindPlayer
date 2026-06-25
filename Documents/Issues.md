# WindPlayer 已知问题清单

来源：代码审查（2026-06-22），按严重程度分级，含状态追踪。
第二轮深度审查（2026-06-23）的结果记录在 `Documents/Audit-2026-06-23.md`，
本文件末尾「2026-06-23 第二轮修复」一节汇总了实际落地的修复。

状态约定：`[ ]` 未处理 / `[~]` 进行中 / `[x]` 已修复 / `[!]` 暂不处理

---

## 一、Critical（P0，阻断功能 / 必须立即修复）

### P0-1. AndroidManifest 缺少 INTERNET 权限 [x]
- **位置**：`app-android/src/main/AndroidManifest.xml`
- **问题**：整个 manifest 没有任何 `<uses-permission>` 声明，SFTP/WebDAV/FTP 全部串流功能运行时抛 `SocketException`
- **修复**：补 `INTERNET` 与 `ACCESS_NETWORK_STATE`

### P0-2. runBlocking 误用（3 处） [x]
- **位置**：
  - `app-android/.../MpvRenderView.kt:88` — 在 `synchronized(player)` 块内 runBlocking 网络请求
  - `app-android/.../MobilePlayerScreen.kt:80` — 在 `events.collect` 协程内 runBlocking
  - `core-vfs/.../desktopMain/VfsManager.kt:40` — 非 suspend 函数内 runBlocking
- **修复**：改为 suspend 调用 / `launch { }`

### P0-3. core-vfs/commonMain 包含 JVM 专属代码（架构违规） [x]
- **位置**：`SftpClient.kt`、`WebdavClient.kt`、`FtpClient.kt`
- **问题**：直接 import `java.*`、`javax.xml.*`、`net.schmizz.*`、`org.apache.commons.*`、`io.ktor.*.cio.*`
- **影响**：当前因 Android/Desktop 都是 JVM 才能编译，违反 KMP 约定，未来加 non-JVM target 立即崩
- **修复**：移到 desktopMain，或用 jvm() 中间源集

### P0-4. Android 端 EndFile reason 是编造的 [x]
- **位置**：`core-mpv/src/androidMain/.../MpvPlayer.android.kt`
- **问题**：`val r = if (fileLoadedBefore) 0 else 4` 不是 mpv 真实 reason，导致错误判断和自动播放误触发
- **修复**：扩展 JNI 桥让 event 回调带 reason

---

## 二、High（P1）

### P1-1. StreamProxy 资源/内存泄漏（桌面端） [x]
- **位置**：`core-vfs/.../desktopMain/VfsManager.kt` + `StreamProxy.kt`
- **问题**：StreamProxy 从不 `stop()`；`closeSession(id)` 存在但从不被调用 → 每次播放累积 SSH 连接
- **修复**：切换文件/退出时关 session，应用退出钩子 stop

### P1-2. MpvRenderView 在 synchronized(player) 内执行网络 I/O [x]
- **位置**：`app-android/.../MpvRenderView.kt:26-46`
- **问题**：`resolvePath` 内 `runBlocking` 网络请求期间持有 player 锁，阻塞所有 player 调用
- **修复**：URL 解析移出锁

### P1-3. MPVLib 单例 + observer 泄漏风险（Android） [x]
- **位置**：`core-mpv/.../androidMain/is/xyz/mpv/MPVLib.kt`
- **问题**：全局 native handle 单例；observer 不 dispose() 则泄漏到 Composition/Activity
- **修复**：MobileApp 中用 DisposableEffect + onDispose { player.dispose() }

### P1-4. 密码字段明文显示（Android） [x]
- **位置**：`app-android/.../AddServerScreen.kt:126`
- **问题**：`OutlinedTextField` 无 `PasswordVisualTransformation()`
- **修复**：加 `visualTransformation`

### P1-5. 密码明文存储（桌面端） [x]
- **位置**：`core-vfs/.../desktopMain/VfsManager.kt` saveConfig
- **问题**：`~/.windplayer/servers.properties` 直接写明文密码
- **修复**：加密敏感字段

### P1-6. SSH/WebDAV 全程禁用主机验证 [x]
- **位置**：`SftpClient.kt`、`StreamProxy.StreamSession` 用 `PromiscuousVerifier`
- **风险**：MITM 凭据泄漏
- **修复**：首次询问 + 持久化 known_hosts

---

## 三、Medium（架构 / 可维护性）

### A1. Main.kt 单文件 1322 行（巨石） [x]
- **位置**：`app-desktop/src/desktopMain/.../Main.kt`
- **问题**：`main()` 函数约 790 行，50+ bindKey 调用、236 行右键菜单、Win32 API、LayoutManager 全塞一起
- **修复**：拆分为 Win32Api.kt / LayoutManager.kt / DesktopKeyboard.kt / ContextMenu.kt / Persistence.kt / Main.kt

### A2. PlayerScreen 三个 while(true){delay()} 轮询 + observeProperty 半成品 [x]
- **位置**：`ui-compose/.../desktopMain/PlayerScreen.kt` + `MpvPlayer.desktop.kt`
- **问题**：observeProperty API 存在但事件循环缺 `MPV_EVENT_PROPERTY_CHANGE=22` 分支，observer 形同虚设
- **修复**：补齐事件分发用 observer 替代轮询；或删除 observeProperty API

### A3. Android 端 MpvLibrary.kt（JNA）是死代码 [x]
- **位置**：`core-mpv/src/androidMain/.../MpvLibrary.kt`
- **问题**：实际播放用 MPVLib（JNI），此文件无人 import，但 jniLibs 仍打包 libjnidispatch.so
- **修复**：删除文件 + 移除 androidMain JNA 依赖 + 删 libjnidispatch.so

### A4. expect/actual icon 机制对 Android 是空 stub [x]
- **位置**：`ui-compose/src/androidMain/.../Icons.kt` 返回 `ColorPainter(Transparent)`
- **问题**：Android UI 用 material-icons-extended，根本不调 iconPainter
- **修复**：PhosphorIcons 常量 + iconPainter 都下沉到 desktopMain

### A5. mobileMain 中间源集只是空 stub [x]
- **位置**：`ui-compose/src/mobileMain/.../MobileApp.kt`（9 行注释）
- **问题**：徒增复杂度，迫使禁用默认 hierarchy template
- **修复**：除非真加 iOS，否则删除中间层

### A6. 大量重复代码 [x]
- 时间格式化函数重复 4 处
- 目录排序 comparator 重复 5 处
- VIDEO/SUBTITLE_EXTENSIONS 在 commonMain 和 Main.kt 重复
- URL-with-credentials builder 重复 3 处
- **修复**：抽到公共 utils

### A7. App.kt 参数爆炸（22+ lambda 参数） [x]
- **位置**：`ui-compose/.../desktopMain/App.kt`
- **修复**：data class 参数对象 或 ViewModel

---

## 四、Low（细节 / 体验）

| # | 位置 | 问题 | 状态 |
|---|---|---|---|
| L1 | `MainActivity.kt` | 只有 onCreate，无 onPause/onResume/onStop/onDestroy，切后台 mpv 不暂停 | [x] |
| L2 | `MobilePlayerScreen.kt:68` | currentPfd 在 state 中，onDispose 不关闭 → 离屏泄漏 FD | [x] |
| L3 | `MobilePlayerScreen.kt:62,85` | backPressedTime / screenBrightness 声明但从不读取（dead state） | [x] |
| L4 | `MobilePlayerScreen.kt:164-174` | setBrightness 负数范围全映射到 -1f，左半滑块失效 | [x] |
| L5 | `MobilePlayerScreen.kt:94` | systemBarsBehavior = 1 magic number | [x] |
| L6 | `ServerStore.kt` | 加密失败静默回退明文，无提示 | [x] |
| L7 | `ServerBrowseScreen.kt` | 返回键退出整个服务器而非向上导航一级 | [x] |
| L8 | `MobileVfsManager.resolveUrl` | 故意不 disconnect，socket 生命周期不可控 | [x] |
| L9 | `WebdavClient.parsePropfindResponse` | 75 行 4 层嵌套 DOM 手解析 | [x] |
| L10 | `ServerConfig.defaultPort()` | WebDAV `host.startsWith("https")` 误判 https 非 443 | [x] |
| L11 | `FileBrowserScreen.kt:372-377` (desktop) | 缩进结构可疑，疑似编辑事故 | [x] |
| L12 | `MpvPlayer.desktop.kt:69` | 事件循环缺 `MPV_EVENT_PROPERTY_CHANGE=22` 分支 | [x] |
| L13 | `MpvPlayer.desktop.kt` 等 | 大量 println 错误日志，应改 java.util.logging / SLF4J | [x] |
| L14 | 所有 VFS clients | `catch (_: Exception) {}` 静默吞异常 | [x] |
| L15 | 根目录 `run-error.txt` / `run-output.txt` | 残留调试文件，应加 .gitignore | [x] |

---

## 五、文档同步问题

| # | 问题 | 状态 |
|---|---|---|
| D1 | `Android-Architecture.md` §3.1 仍写「Render API + EGL」实际是 libplayer.so + attachSurface | [ ] |
| D2 | `Tec.md` §3.1 PropertyChange 未实现，需说明全部走轮询 | [ ] |
| D3 | `external-media-track-matching-and-scheduling.md` §4 字幕内容嗅探（CJK/ASCII）未实现 | [ ] |

---

## 修复顺序（从简单到复杂）

**第一批（一行级修复）**：L3、L5、L15、P1-4、P0-1
**第二批（小范围重构）**：L4、L10、L13、A3
**第三批（资源管理）**：L1、L2、P1-1、P1-3
**第四批（架构整理）**：P0-3、A4、A5、A6
**第五批（复杂修复）**：P0-2、P1-2、P0-4、A1、A2、A7
**第六批（安全 & 文档）**：P1-5、P1-6、D1、D2、D3

---

## 六、2026-06-23 第二轮修复（深度审计）

来源：`Documents/Audit-2026-06-23.md`。本轮共发现 50+ 个新问题（P0/P1/M/L）。
按优先级分 4 波修复，每波后均通过 `:app-desktop:compileKotlinDesktop` +
`:app-android:compileDebugKotlin` + `:core-vfs:allTests`（66 tests, 0 failures）验证。

### 第一波：one-liner 高 ROI（已全部修复）
- **A-2026-C1** 桌面端 `MpvPlayer` 加 `lock` + 所有 mpv 调用 `synchronized(lock)`，与 Android 端对齐
- **A-2026-C2** `handle`/`running` 加 `@Volatile`（防止事件线程读不到 dispose 写）
- **A-2026-C3** `dispose()` 先 `eventThread.join(2000)` 再 `mpv_terminate_destroy`（防 UAF）
- **A-2026-C4** `MPVLib.event/eventProperty` 改为「锁内 toList → 锁外 dispatch」，消除 `inferEndFileReason` 再入 JNI 的死锁风险
- **A-2026-C5** `StreamProxy.StreamSession.close()` 加 `@Synchronized`，与 `open/read` 同锁
- **A-2026-H1** 删除 `Main.kt:234` 的 `Thread.currentThread().join()`（自 join 抛 IAE）
- **A-2026-H3** `DesktopShortcutContext.skipNextCallback` 加 `@Volatile`
- **A-2026-H4** `_events` SharedFlow 改 `extraBufferCapacity=256 + DROP_OLDEST`，避免 FileLoaded/EndFile 被静默丢弃
- **A-2026-M3** `startEventLoop` 加幂等守卫，防二次 `initialize()` 孤立线程
- **A-2026-M4** Android `dispose()` 顺序：先 `removeObserver` 后 `destroy`
- **A-2026-L1** 删除 `MpvRenderView.kt` 死的 `import runBlocking`

### 第二波：锁 & 资源生命周期（已全部修复）
- **A-2026-H2** `LayoutManager` 所有公共方法包 `onEdt { }`（EDT 上 inline 零开销，非 EDT 时 `invokeAndWait`）；`applyLayout` 保留内部 `invokeLater` 防重入
- **A-2026-H5** `VfsManager.clients`→`ConcurrentHashMap`，`_servers`→`CopyOnWriteArrayList`
- **A-2026-H6** `MpvRenderView.surfaceCreated` 加 `AtomicBoolean surfaceValid`，在 IO 协程 await 前后检查
- **A-2026-H7** 删除 `MpvRenderView` 的 `synchronized(player)` 外层锁；改用专用 `surfaceLock` 只保护 pfd 字段
- **A-2026-H11** `AndroidView(onRelease = { it.release() })` 在 MobilePlayerScreen 退出时取消 SupervisorJob + 注销 SurfaceHolder.Callback
- **A-2026-H12** PFD 双开泄漏 — `MpvRenderView` 不再自己开 pfd，改用 `onSurfaceReady` 回调；`MobilePlayerScreen.resolveAndLoad` 成为 pfd 的唯一所有者（初始加载和自动播放下一首都走它）
- **A-2026-H13** `MobilePlayerScreen.resolveAndLoad` 体改 `withContext(Dispatchers.IO)`，避免 PFD/SSH 在主线程
- **A-2026-H14** `MobileApp.onBack` 和 `DisposableEffect.onDispose` 的 `player.dispose()` 改 `scope.launch(Dispatchers.IO)`
- **A-2026-H15** `ServerStore.prefs()` 用 `@Volatile cachedPrefs + cacheLock` 双检锁，避免每次操作重建 MasterKey；改用 `applicationContext`

### 第三波：安全 fail-closed（已全部修复）
- **A-2026-C6** `WebdavClient.parsePropfindResponse` 禁用 DOCTYPE / 外部实体 / 外部 DTD / 扩展实体（XXE 加固）
- **A-2026-H8** `VfsUtils.redactUrl()` 助手，剥 `userinfo@`；`VfsManager` 日志改用 redact
- **A-2026-H9** `KnownHostsManager` 失败分支改为 `RejectAllHostKeyVerifier`（恒 false），不再降级到 `PromiscuousVerifier`；新增 0600 权限设置
- **A-2026-H10** `ServerStore` 明文 fallback 改用独立文件名 `windplayer_servers_plain`，避免后续 crypto 恢复时读取格式错误
- **A-2026-H17** `StreamProxy.createStreamUrl` 用完整 UUID（122 位）替代 `take(8)`（32 位）
- **A-2026-H18** FTP FTPS 支持 — `FtpClient` 当 `useTls=true` 用 `FTPSClient`+`PROT P`；`WebdavClient` 设 `followRedirects=false` 防 `Authorization` 头泄漏；两端 AddServer UI 新增 TLS Switch（默认 ON）和 cleartext 警告；`useTls` 持久化到 desktop/Android 配置
- **A-2026-M13** `MobilePlayerScreen.screenshot` 加 `<flags>` 参数 `"subtitles"`
- **A-2026-M18** `VfsManager.downloadSubtitle` 用 `sanitizeCacheName` 白名单字符，防 `../../` 路径穿越
- **Manifest** `android:allowBackup="false"`（防 `adb backup` 导出加密 prefs）
- **M2** `Main.windowClosing` 改 try/finally，异常时仍执行 `player.dispose()`

### 第四波：文档同步
- **AGENTS.md**：PromiscuousVerifier 一节改写为「fail-closed + RejectAllHostKeyVerifier」；测试章节列出 4 个测试文件；新增「mpv cross-thread access」一节记录内部 lock 不变量
- **Audit-2026-06-23.md**：本审计报告，含 50+ 问题分级与修复进度

### 暂未修复（需后续单独处理）

| ID | 原因 |
|----|------|
| A-2026-M9/M10/M12 | DocumentFile.listFiles 主线程、Slider save 风暴、Android 端轮询未迁 observer — 性能优化，非阻塞 |
| A-2026-M15 | 完整 `networkSecurityConfig.xml`（per-host cleartext）需配合 UI（用户为单服务器显式开启 cleartext）|
| A-2026-M21 | Compose BOM 与 CMP 1.9.0 对齐 — 当前 BOM 仅作软约束，运行时已用 CMP 1.9.0，需联网验证可用 BOM 版本 |
| A-2026-M22/M23/M24/M25 | Release 版本号穿透 / 签名 APK / 安装包 / AGENTS.md distZip 文档漂移 — 需整体规划 release pipeline |
| A-2026-M26 | core-mpv/ui-compose 测试基础设施 + VFS 协议集成测试（Testcontainers）— 大工作量 |
| D1/D2/D3/D5 | 历史文档同步（Android-Architecture / Tec / track-matching 三份过时）— 写作任务 |
