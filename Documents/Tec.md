Wind-Player 核心技術架構與 API 規範

本文件定義 Wind-Player 專案的軟體架構、模組依賴關係，以及 Kotlin 與 C 語言底層 (libmpv) 的通訊契約。

1. 模組拆分策略 (KMP Module Structure)

為了避免依賴污染，專案必須嚴格劃分為以下模組：

core-mpv (純邏輯與 FFI 層)

commonMain: 定義 expect class MpvPlayer 介面，以及所有的播放狀態資料類別 (Data Classes)。

androidMain: 實作 actual class MpvPlayer，包含 mpv-android 核心 JNI 程式碼 (mpv.kt, JNI.kt)。

desktopMain: 實作 actual class MpvPlayer，使用 JNA 綁定 mpv-client-api。

禁忌：此模組內絕對不可包含任何 Compose UI 程式碼。

core-vfs (虛擬檔案系統層)

負責處理 WebDAV、SFTP、FTP 協定。

輸出統一的 FileNode 樹狀結構。

實作旁路字幕 (Sidecar Subtitles) 的背景下載邏輯。

ui-compose (共用 UI 層)

依賴 core-mpv 與 core-vfs。

包含播放控制器、進度條、伺服器管理清單。

負責狀態提升 (State Hoisting)。

app-android / app-desktop (平台外殼)

平台進入點 (Activity / Main.kt)。

處理平台專屬邏輯 (如 Android 的權限申請、Windows 的視窗裝飾)。

2. 渲染管線對接 (Render Pipeline)

libmpv 本身不負責繪製視窗，它需要宿主（我們的 Kotlin App）提供一個繪圖表面控制代碼 (Window ID / wid)。這是專案中最核心的技術挑戰。

Android 渲染流

在 Compose 中使用 AndroidView 包裝一個原生的 SurfaceView。

監聽 SurfaceHolder.Callback。

當 surfaceCreated 觸發時，取得底層的 Surface 物件。

透過 JNI 將此 Surface 傳遞給 mpv 引擎：

// 概念碼
mpv.setOptionString("wid", surface.nativeHandle.toString()) 
// 或透過 mpv-android 特有的 android-surface API


Desktop (Windows/Linux) 渲染流

不能使用 Compose 的純 Canvas 繪製影片（效能極差且無法硬解）。必須嵌入原生作業系統視窗。

使用 Compose Desktop 的 SwingPanel 嵌入一個 AWT Canvas 物件。

取得該 AWT Canvas 在作業系統層級的 Handle (HWND 或 X11 Window ID)。

透過 JNA 呼叫 mpv API：

// 概念碼 (Windows)
val hwnd = Native.getComponentPointer(awtCanvas)
mpv.setOptionLong("wid", Pointer.nativeValue(hwnd))


3. Kotlin <-> C 通訊契約 (FFI Design)

跨語言通訊的開銷是 UI 掉幀的主因。必須遵守以下通訊契約：

3.1 狀態輪詢 (Polling) vs. 事件回調 (Event Callback)

UI 進度條更新 (禁止使用回調)： 不要讓 mpv 每幀透過 JNI/JNA 呼叫 Kotlin 來更新進度。應在 Kotlin 層使用 Coroutine delay(200) 定期發送 mpv_get_property_async("time-pos") 請求。

核心事件 (必須使用回調)： 對於 MPV_EVENT_FILE_LOADED (檔案加載完成)、MPV_EVENT_EOF (播放結束)、MPV_EVENT_ERROR，必須在 C 層建立監聽執行緒，並透過 JNI/JNA 將事件推播給 Kotlin 的 SharedFlow。

3.2 字串記憶體管理

mpv API 大量使用 const char*。

在 desktopMain (JNA) 中，傳入字串給 mpv 後，確保 JNA 正確釋放記憶體。

接收 mpv 返回的字串 (如 mpv_get_property_string) 時，必須呼叫 mpv_free() 釋放 C 層記憶體，否則會造成嚴重的記憶體外洩。

4. 旁路字幕 (Sidecar Subtitle) 調度邏輯

網路播放的核心痛點是巨大字幕檔會導致影片串流卡頓。

標準處理流程：

使用者在 VFS 介面點擊 sftp://host/movie.mkv。

core-vfs 攔截播放請求，執行同目錄掃描。

發現 movie.ass (15MB)。

啟動背景 Coroutine，將 movie.ass 寫入平台的暫存目錄 (如 Android 的 context.cacheDir)。

在 Kotlin 建構 mpv 啟動參數：

val args = arrayOf(
    "loadfile", 
    "sftp://host/movie.mkv", 
    "append-play",
    "sub-file=/data/user/0/com.app/cache/movie.ass" // 絕對本地路徑
)
mpv.command(args)


5. 打包與發布規範 (Build System)

Android .so 瘦身：
預設的 FFmpeg 包含幾百種編碼器。必須編寫自訂的 build.sh，停用無用解碼器 (如 disable-decoder=rmvb, disable-demuxer=avi)。目標是將 Android 的 ABI (arm64-v8a) 核心庫體積控制在 25MB 以內。

Desktop 動態連結：
在 Windows/Linux 上，不建議將幾十 MB 的 mpv-2.dll 或 libmpv.so 打包進 jar 檔中。應在應用程式啟動時，檢查系統路徑或安裝目錄，若無核心庫則觸發線上下載。這能保持應用程式本體極小 (約 10MB 以內)。