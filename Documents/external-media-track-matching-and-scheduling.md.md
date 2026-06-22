外置媒體軌道（字幕/音訊）匹配與調度策略

1. 核心設計原則與「輕重流」分級

在 Wind-Player 架構下（尤其是針對 SFTP/WebDAV 等網路虛擬檔案系統 VFS），外置軌道的匹配必須根據檔案的物理特性進行分級處理，否則會導致嚴重的 I/O 阻塞。

輕量流 (Lightweight Stream): 字幕檔 (.srt, .ass, .vtt 等)。體積極小 (< 20MB)。

重量流 (Heavyweight Stream): 外置音訊 (.mka, .flac, .dts, .ac3, .wav)。體積龐大 (100MB ~ 數 GB)。

⚠️ 視訊軌道排除聲明：
外置視訊軌（如 .mkv, .mp4）極易與同目錄下的其他劇集或花絮發生副檔名衝突（例如將 EP02 誤認為 EP01 的第二視角）。且現代 MKV 容器已原生支援封裝多視訊軌。故本系統「徹底放棄」視訊軌的自動匹配，僅在 UI 層保留「手動掛載外部視訊」的功能。

匹配與調度鐵律：

零網路 I/O 探測： 匹配過程只能基於已經抓取到的 FileNode 樹狀目錄（包含檔名、大小、副檔名），絕對禁止讀取遠端檔案內容。

禁止盲目遞迴掃描 (No Recursive Scan)： 網路 I/O 極其昂貴，絕對不允許對所在目錄進行深度遞迴。若需處理外掛字幕資料夾，僅允許「定向單層探測」（例如硬編碼僅檢查同級的 Subs/、Subtitles/ 目錄），禁止掃描其他無關的子目錄。

音軌禁止快取： 匹配到外置音軌時，絕對不允許使用背景下載。必須將網路協定 URL 直接餵給 mpv 進行雙路串流。

音軌防誤判機制： 對於音軌，關閉模糊匹配，採用極度嚴格的排他性校驗，避免將 BGM 原聲帶等獨立檔案誤認為電影音軌。

2. 匹配優先級與責任鏈 (Chain of Responsibility)

當使用者點擊播放主影片 videoFile 時，演算法會遍歷同目錄（含定向探測的 Subs/ 目錄）下的檔案。根據檔案的副檔名分類，並套用以下匹配級別。一旦任一 Level 命中，即刻中斷後續匹配。

Level 1: 歷史綁定匹配 (Hash / Local DB Lookup)

適用對象： 字幕、音訊。

邏輯： 檢查本機 SQLite 資料庫中是否有該 videoFile 的手動綁定記錄。

命中率： 低，但準確率 100%。

Level 2: 精確擴展匹配 (Exact Name + Track Extension)

適用對象： 字幕、音訊。

邏輯： 附屬檔名去除軌道/語言標籤後，與主影片檔名完全一致。

範例命中：

主影片：Concert.2024.1080p.mkv

外置音訊：Concert.2024.1080p.FLAC.mka (命中，作為音軌掛載)

外置字幕：Concert.2024.1080p.cht.ass (命中，作為字幕掛載)

Level 3: 結構化特徵匹配 (Structured Feature Regex)

適用對象： 字幕、音訊。

邏輯：

提取主影片的劇集特徵（如 S01E05、- 05）。

過濾目錄下包含同樣特徵的附屬檔案。

阻斷聲明： 只要成功提取出 SxxExx 或集數特徵，即使本層沒有找到對應字幕，也絕對禁止進入 Level 4，以防模糊匹配將其他集數的字幕誤抓。

範例命中：

主影片：[Group] Anime - 12 [1080p].mkv

外置音軌：[Group] Anime - 12 [Commentary Track].mka (特徵 - 12 一致，命中為評論音軌)

主影片：Show.S01E01.mkv，將同時匹配並掛載 Show.S01E01[eng].srt 與 Show.S01E01[sc].srt。

Level 4: 模糊字串匹配 (Fuzzy Levenshtein)

適用對象： 僅限字幕。（音訊嚴格禁用此層，避免將同目錄下的導演訪談錄音誤認為外置音軌）。

邏輯： 針對無明確集數特徵的孤立影片，去除噪音字眼後計算編輯距離，相似度 >= 85% 即命中。此為防禦性降級策略，僅在 Level 2 與 Level 3 皆未觸發時執行。

3. 多軌道掛載與 MPV 指令建構 (Command Injection)

當匹配演算法收集到附屬檔案後，必須針對「輕量流」和「重量流」採取截然不同的 mpv 掛載策略。

3.1 輕量流（字幕）的旁路下載

若匹配到 .ass/.srt，且來源為網路 (SFTP/WebDAV)：

啟動背景 Coroutine 下載至 /cache/。

傳遞本地路徑給 mpv：--sub-file=/cache/sub1.ass

原因：避免高複雜度的 ASS 字幕在網路波動時導致 mpv 渲染執行緒阻塞。

3.2 重量流（音訊）的原生串流直通

若匹配到外置音軌 (.mka, .flac 等)：

禁止下載。

將原始的網路 URL 直接傳遞給 mpv 對應的參數：
--audio-file=sftp://user:pass@host/audio.mka

強制修改 mpv 網路快取策略：
因為此時 mpv 同時在拉取兩條巨大的網路串流，必須在 Kotlin 層啟動播放前注入以下 mpv 設定，否則極易出現音畫不同步 (A/V Desync)：

--cache=yes
--demuxer-max-bytes=500M  // 分配更大的解複用器記憶體
--demuxer-max-back-bytes=100M



4. 語言與軌道標籤解析 (Track Tagging)

在 UI 上，必須清楚標示這些外置軌道，避免使用者切換時感到困惑。

音訊解析： 從外置音訊檔名中提取關鍵字，如 Commentary (評論音軌), FLAC, TrueHD, BGM。如果沒有標籤，則預設命名為 External Audio Track 1。

字幕解析與無標籤處理 (Tagless Fallback)：

基於檔名萃取： 優先根據副檔名或後綴（如 .chs, .cht, .eng）解析語言。

本地內容嗅探 (Content Sniffing)： 如果檔名中沒有任何語言標籤（例如 Movie.srt），且該字幕已經依照 3.1 節規則被下載到本機 /cache/ 目錄，則啟動嗅探：

讀取該文字檔的前 2KB（約前 50 行對話）。

字元集判定： 統計 Unicode 區間。若包含大量 CJK 統一表意文字，判定為 中文/日文（可進一步依據平假名/片假名區分日文）；若全為 ASCII 且符合英文構詞，判定為 English。

這種在記憶體中進行的正則/編碼探測耗時不到 1 毫秒，卻能將原本顯示為 Unknown 的字幕精準標記為 中文 (自動偵測)。

終極降級： 若嗅探失敗或無法辨識，則按掛載順序命名為 External Subtitle 1, External Subtitle 2。