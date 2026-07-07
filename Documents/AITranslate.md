KMP 开源播放器：本地 ASR 与 AI 翻译引擎架构设计

1. 核心设计哲学

在复杂系统环境与 LLM 不可预测输出的夹击下，本模块遵循以下三大铁律：

引擎与数据彻底物理隔离：底层 C++ 引擎（AAR 或 DLL/SO）绝不耦合特定模型，模型文件永远走云端下发（直连 Hugging Face）、本地沙盒挂载。

对 LLM 抱有极致的不信任：绝不盲目相信大模型能原样返回格式。每一次 LLM 交互必须经过“字数防截断”、“严格 JSON 校验”和“ID 1:1 强映射”三重校验。

任务生命周期优先：长耗时任务必须脱离 UI 线程。Android 必须与前台服务（Foreground Service）强绑定抗击系统杀后台；Desktop 端需确保后台线程池的资源隔离。

2. 系统分层架构 (KMP 视角)

砍掉 iOS 后，架构向 Android 和泛桌面端（JVM/Native）收拢，维护成本大幅降低。

[ UI 层 (Compose Multiplatform) ]
   ↑ 订阅 StateFlow (任务进度、状态)
   ↓ 触发翻译/提取指令
---------------------------------------------------------
[ 领域层 Domain (commonMain) ]
 - TranslationManager：全局任务调度，控制串行队列
 - ChunkingStrategy：切块算法与上下文滑动窗口管理
 - SubtitleMergeEngine：JSON -> SRT/VTT 强校验重组
---------------------------------------------------------
[ 数据与驱动层 Data (commonMain) ]
 - WhisperLocalSource (expect/actual 绑定底层 C++)
 - ModelFetcher (对接 HF 官方仓库，支持断点续传下载)
 - LLMRemoteSource (Ktor Client + 动态配置 BaseURL)
 - LocalCacheSource (SQLite/文件系统，存储切片断点)
---------------------------------------------------------
[ 平台实现层 (androidMain / desktopMain) ]
 - Android: ForegroundService + Notification (系统级保活)
            Universal Whisper.aar (纯净引擎)
 - Desktop: JNI/JNA 调用系统级动态库 (.dll / .so)
            (Windows / Linux 共享桌面端逻辑，无后台限制)


3. 本地 ASR 管线规范 (Whisper Fallback)

本管线仅在视频无任何内置/外挂字幕时作为最终兜底触发。

3.1 引擎依赖与云端直连规范

纯净引擎：Android 侧编译期输出纯净的 whisper-android-engine-release.aar（小于 10MB）；Windows/Linux 侧分发编译好的纯净 .dll 或 .so 动态库。

模型降维打击：线上仅允许使用 tiny-q8_0, base-q8_0, large-v3-turbo-q5_0 三档 GGML 模型。绝不提供未量化的高内存占用版本。

HF 官方库直连 (Model Fetcher)：
不在私有服务器囤积模型。客户端内部维护白名单映射表，直接拼装官方仓库 URL：
https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-{model_name}.bin
(必须在客户端实现基于 Range 头部的 HTTP 断点续传，防止大文件下载中途中断抛错。)

3.2 音频数据获取 (零临时文件方案)

禁止引入 FFmpeg 库：严禁为了提取音频额外在 KMP 引入冗余的 FFmpeg。

利用 mpv 钩子：在 libmpv 启动时下发参数 --af=lavfi=[aresample=osr=16000:oscl=mono],format=f32le。拦截内存流直接转 FloatArray 喂给 ASR 队列。

4. LLM 结构化翻译管线 (核心防抖)

这是系统最容易崩溃的环节，必须采用严格的受限重叠滑动窗口机制。

4.1 动态切块策略 (Chunking)

严禁写死“按 N 句切分”。必须采用二维限制：

单块最大行数：默认 40 行。

单块最大字符数：1500 字符。

逻辑：一旦到达 40 行，或累计字符突破 1500 字，立刻切断作为独立 Chunk。防止 LLM 返回时超 max_tokens 导致 JSON 截断。

4.2 强一致性 Prompt 协议与结构

在发送请求时，利用上一个 Chunk 的末尾翻译作为“只读上下文”，以统一前后名词。

请求 JSON Payload 结构示例：

{
  "context_reference": [
    {"id": 39, "text": "之前的翻译句子，仅供参考上下文专有名词，无需翻译"}
  ],
  "to_translate": [
    {"id": 40, "source": "Here comes the Enterprise!"},
    {"id": 41, "source": "Shields up."}
  ]
}


响应 JSON 结构约束：

必须在第一个 Chunk 强制要求 LLM 返回 detected_source_language。

translations 数组的长度必须等于 to_translate 的长度，id 必须一一对应。

5. 容错与状态机设计 (防范网络波动与解析崩溃)

在 commonMain 中，所有的结果处理坚决抛弃原生的 try-catch Exception 滥用，必须使用 Kotlin 的 Sealed Class 构建明确的状态机。

5.1 领域层状态定义

sealed interface TaskState {
    data object Queued : TaskState
    data class Transcribing(val progress: Float) : TaskState
    data class TranslatingChunk(val currentChunk: Int, val totalChunks: Int) : TaskState
    data class Completed(val srtFilePath: String) : TaskState
    sealed class Failed : TaskState {
        data class NetworkError(val retryCount: Int) : Failed()
        data class FormatCorrupted(val chunkId: Int, val rawJson: String) : Failed()
        data class IdMismatch(val expectedCount: Int, val actualCount: Int) : Failed()
    }
}


5.2 灾难恢复与降级策略

断点续传：每个 Chunk 翻译成功后，立即序列化为 .json 碎片文件存入沙盒。若遇到 HTTP 429 (Rate Limit) 或应用被系统强杀，下次重启翻译时读取碎片文件，直接从缺失的 Chunk 开始续传。

格式损坏隔离：如果正则清洗后仍无法解析（FormatCorrupted），对该 Chunk 进行最多 2 次重试。如果仍然失败，执行降级融合：该 Chunk 保留原文，标记警告日志，继续下一个 Chunk。绝对不能让局部的 JSON 崩溃导致整个视频的字幕罢工。

ID 校验防线：如果在合并阶段发现 IdMismatch，直接丢弃该 LLM 返回，使用原文降级。坚决维护时间轴的 1:1 映射。

6. 平台生命周期与环境管控

脱离 iOS 泥潭后，重点只需放在对抗 Android 系统的极度杀进程机制，以及桌面端的高性能调度。

6.1 Android 端 (Foreground Service 铁血保活)

用户在文件管理器长按点击“生成字幕”后，在 androidMain 侧立刻拉起 ForegroundService。

必须附带一条不可滑除的前台 Notification，UI 如下：

标题：视频《xxx》字幕处理中

内容：正在翻译 (45%) | 剩余约 2 分钟

生命周期：Service 的存活与 TaskState 严格绑定，进入 Completed 或终态 Failed 后调用 stopSelf() 销毁。

6.2 Desktop 端 (Windows / Linux)

桌面操作系统没有移动端“熄屏挂起”或“切后台限制 CPU”的逻辑。不需要专门做保活。

核心精力放在多线程分配上。利用 Dispatchers.Default 将推理和网络请求彻底压在后台线程池，防止占满 CPU 导致主窗口拖拽卡顿。

提供针对操作系统的优雅退出机制：若用户在生成期间关闭软件主窗口，需拦截关闭事件，弹窗警告“正在生成字幕，是否强行中断退出”。

7. 最终挂载机制 (Hot Reload)

所有 Chunk 处理完毕后，SubtitleMergeEngine 将 JSON 碎片重组为标准的 .srt 或 .vtt 文件。

将文件写入到缓存目录 cache/subtitles/video_hash_translated.srt。

通知 libmpv，调用挂载本地字幕的 C API，完成轨道的静默无感切换。

8. 字幕解析与时间轴对齐方案 (Timeline Integrity)

时间轴错乱是 ASR + LLM 管线中最致命的 Bug。为了保证时间轴的绝对精准，本系统采用“时文物理分离与 ID 锚定（Time-Text Separation & ID Anchoring）”策略。

8.1 严禁 LLM 接触时间戳

绝对不允许将任何时间戳（如 00:01:23,000）发送给 LLM。 大模型不具备精确的时序逻辑计算能力，发送时间戳不仅浪费 Token，还会引发时间轴的合并、篡改甚至错乱。

8.2 Whisper 底层时间轴提取规范

当 C++ 底层推理完成后，在 JNI / cinterop 胶水层，必须严格按照以下流程提取数据：

片段遍历：调用 whisper_full_n_segments(ctx) 获取总片段数。

单位转换 (极大深坑)：

whisper_full_get_segment_t0() 和 t1() 返回的整型值单位是 10 毫秒 (10ms)。

必须在 Kotlin 层将其乘以 10 转换为毫秒 (ms)。

然后严格格式化为 SRT 的时间码规范：HH:mm:ss,SSS (注意毫秒分隔符必须是逗号)。

抗幻觉清洗 (Anti-Hallucination)：
Whisper 在遇到纯音乐、长静音或噪音时，会产生“复读机”幻觉，或输出类似 [MUSIC], (silence) 的标记。

拦截规则：在 Kotlin 层获取到 whisper_full_get_segment_text() 后，使用正则过滤掉被 [] 或 () 包裹的无意义标签。如果过滤后文本为空字符串，则直接丢弃该 Segment，不计入后续的 ID 映射。

8.3 时文分离映射逻辑

在 KMP 的 Domain 层维护一张本地时间轴映射表 (Timeline Map)。

内存结构示例：

data class SubtitleSegment(
    val id: Int, 
    val startTime: String, 
    val endTime: String, 
    val originalText: String
)

// 字典表，仅存在于设备本地内存/SQLite中
val timelineMap: Map<Int, SubtitleSegment> = ... 


组装 LLM 请求：
只提取 id 和 originalText 组装成 JSON Chunk 发送给大模型，彻底剥离时间属性。

8.4 强制对齐合并算法 (Merge Strategy)

当 LLM 翻译完毕返回 TranslatedChunk 后，由 SubtitleMergeEngine 执行拼装：

遍历本地 timelineMap。

通过 id 去大模型返回的 JSON 中查找 translated_text。

安全降级：若 LLM 发生截断或漏翻（找不到对应的 id），则使用 timelineMap 中保存的 originalText 补位。

格式化输出：拼装为标准 SRT 块并写入文件。

// SRT 块拼装格式要求
${id}
${startTime} --> ${endTime}
${translated_text_or_fallback}
\n


通过此策略，即便大模型的输出彻底崩溃，视频的时间轴骨架依然坚不可摧，最坏的情况仅仅是局部字幕退化为外语原文。