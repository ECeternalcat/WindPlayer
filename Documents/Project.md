開發計畫書：跨平台極客影音終端 (暫定代號: Wind-Player)

0. 專案核心定位與技術選型 (基底)

這不是一個「從零寫解碼器」的專案，而是一個「極致的 UI/UX 與網路調度層」。

UI 渲染層: Compose Multiplatform (CMP) - 涵蓋 Android, Windows, Linux。

業務邏輯與網路層: Kotlin Multiplatform (Coroutine, Ktor Client, SSHJ/Java 原生套件)。

播放內核: libmpv (C 動態連結庫)。

通訊橋樑: Android 透過 JNI (沿用/精簡 mpv-android 的封裝)；桌面端透過 JNA (mpv-client-api)。

絕對禁忌: 絕不在 C/C++ 層寫網路邏輯或 UI 邏輯。

1. 第一階段：MVP 內核打通

目標：證明 Kotlin UI 能無延遲地控制 mpv，且畫面能正常渲染。

Task 1.1: 構建 KMP 骨架與依賴注入

建立 commonMain, androidMain, desktopMain。

定義 expect class MpvController，封裝基礎指令（play, pause, seek, setOption）。

Task 1.2: 桌面端 (Win/Linux) 渲染對接

透過 JNA 載入 libmpv.so / mpv-2.dll。

將 mpv 的渲染上下文 (Render Context) 綁定到 Compose Desktop 的 ComposeWindow 或底層 Skia 畫布上。

Task 1.3: 移動端 (Android) 渲染對接

整合 mpv-android 的 JNI 代碼。

將畫面輸出綁定到 Compose 的 AndroidView (包裝 SurfaceView)。

Task 1.4: 基礎本地播放驗證

硬編碼一個本地 4K 影片路徑，確保畫面輸出、硬解 (MediaCodec/D3D11VA) 正常啟動，且無明顯掉幀。

2. 第二階段：虛擬檔案系統 (VFS) 與網路協議

目標：放棄 SMB，實作 WebDAV/FTP/SFTP 的目錄漫遊與串流交接。

Task 2.1: 實作統一的文件節點模型 (FileNode)

在 commonMain 定義統一的介面，無論是本地還是網路，皆映射為 FileNode(name, path, isDir, size)。

Task 2.2: 網路協議客戶端實作

WebDAV: 使用 Ktor 實作 PROPFIND 解析，生成目錄樹。

FTP/FTPS: 使用 Kotlin Socket 手寫輕量指令，或找純 Java 依賴。

SFTP: 整合 SSHJ 庫，處理密鑰交換與 ls 指令。

Task 2.3: 伺服器管理 UI

開發類似 KOReader 的極簡伺服器列表與登入介面。

實作目錄導覽邏輯（點擊資料夾 -> 請求數據 -> 更新 Compose 列表）。

Task 2.4: 串流與旁路字幕加載邏輯

點擊影片時，將協議路徑 (如 sftp://user:pass@host/file.mkv) 傳給 mpv。

關鍵功能： 在傳遞給 mpv 前，非同步掃描當前目錄，若有同名 .ass/.srt，則將其下載至本地暫存，並透過 --sub-file=<local_cache> 指令掛載，避開 mpv 直接讀取巨大網路字幕的卡頓。

3. 第三階段：UX 打磨與安卓權限適配

目標：對標 MX Player 的手感，處理安卓的生態毒瘤。

Task 3.1: 手勢與交互引擎 (Compose)

手寫一套高精度手勢系統：左半螢幕上下滑動 (亮度)、右半螢幕上下 (音量)、全螢幕左右滑動 (精確到毫秒的 Seek)。

實作鎖定按鈕、硬解切換按鈕。

Task 3.2: 軌道選擇與高階音視訊 UI

呼叫 mpv API 獲取所有 Track 列表 (Video, Audio, Subtitle)，包含編碼格式 (如 TrueHD, HDR10)。

開發優雅的底部抽屜 (Bottom Sheet) 讓用戶切換音軌與字幕。

畫面疊加層：當檢測到 Dolby Vision 或 Atmos 時，顯示 2 秒的優雅徽章動畫。

Task 3.3: 安卓權限策略落地

開發「硬核模式」引導頁。

實作 SAF (儲存空間存取架構) 降級讀取，以及跳轉系統設定申請 MANAGE_EXTERNAL_STORAGE 權限的完整流程。

4. 第四階段：平台特性優化與發佈

目標：消除跨平台帶來的「違和感」。

Task 4.1: 桌面端視窗管理

處理 Windows/Linux 的無邊框全螢幕、滑鼠隱藏、雙擊全螢幕、空白鍵暫停等鍵鼠直覺操作。

Task 4.2: 移動端生命週期

處理 Android 切換後台時的 Audio Focus 丟失、暫停邏輯。

實作子母畫面 (Picture-in-Picture) 切換。

Task 4.3: 效能 Profiling

確保 Compose 在頻繁更新播放進度條時，不會引發無效重組 (Recomposition) 導致 CPU 佔用過高。

⚠️ 專案風險與防禦準則

狀態同步地獄： 不要讓 mpv 每幀回調進度！UI 層應該使用協程 ticker 每 200ms 主動拉取 (getProperty) 播放時間。過度頻繁的跨語言 (JNI/JNA) 通訊會導致 UI 掉幀。

依賴膨脹：
libmpv 編譯出來的 .so 和 .dll 非常大。你需要編寫客製化的 FFmpeg 編譯腳本，剔除不需要的舊時代編碼器 (如 RMVB)，將核心體積控制在 30MB 以內。

UI 平台割裂：
桌面端沒有「亮度調節手勢」的需求，移動端沒有「滑鼠懸停顯示時間」的邏輯。雖然是 KMP，但在視訊播放介面上，務必將桌面版元件與移動版元件分離，不要強求 100% 程式碼共用。