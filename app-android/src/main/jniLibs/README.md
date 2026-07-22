# mpv-android native libraries

## 当前版本来源

当前 `arm64-v8a` 目录中的 10 个 `.so` 从 mpv-android 官方 Release APK
原样提取，逐文件 SHA-256 已与 APK 内容核对一致：

- Release：`2026-04-25`
- Release 页面：<https://github.com/mpv-android/mpv-android/releases/tag/2026-04-25>
- 资产：`app-default-arm64-v8a-release.apk`
- 官方资产 SHA-256：`4400bcba6be9cec1128e24d1eba153d8727384926b0639fa7fe44d4e36b04f81`
- mpv-android commit：`3018d47277d5b3ca02acdd96466f261c1d23ee08`
- libmpv commit：`9ce79bcaa0132660a2e45b6bfc1fb0c199665277`
- FFmpeg commit：`fc4960b155aa33b9a08cf26c5e0a0530f0545f24`
- Android NDK：r29

完整依赖版本由该 Release 页面列出。本仓库的逐文件哈希记录在
`Documents/Native-Assets-SHA256.txt`。

mpv-android 官方明确说明它不发布可导入的 AAR/library。官方 Release
发布 APK，因此从固定 Release APK 提取 native libraries 是本项目采用的
上游分发方式，不要求 WindPlayer 维护一套独立的 mpv/FFmpeg 编译链。

## 方法 1：从 mpv-android APK 提取（推荐）

1. 下载上面记录的固定 Release APK。升级时选择新的 ABI-specific release
   APK，并同时更新本文件中的 release、commit 和 digest，不要使用未记录的
   “latest” 构建。

2. APK 本质是 ZIP 文件，用解压工具打开

3. 找到 `lib/` 目录，按 ABI 复制 `.so` 文件：
   ```
   lib/arm64-v8a/libmpv.so     → jniLibs/arm64-v8a/
   lib/armeabi-v7a/libmpv.so   → jniLibs/armeabi-v7a/
   lib/x86_64/libmpv.so        → jniLibs/x86_64/
   ```

4. 将该 ABI 目录中的全部 `.so` 一并复制，不能只复制 `libmpv.so`；当前
   mpv-android 构建还依赖 FFmpeg、`libplayer.so` 和 `libc++_shared.so`。

## 方法 2：从源码交叉编译

需要 Linux 环境（WSL / Docker）：

```bash
git clone https://github.com/mpv-android/mpv-android.git
cd mpv-android
./buildscripts/include.sh
./buildscripts/build.sh
```

构建产物在 `app/src/main/jniLibs/{abi}/` 目录。

## 注意事项

- 不要假定 `libmpv.so` 自包含全部依赖，以实际 APK 的 ABI 目录为准。
- 如果加载失败，检查 `.so` 是否匹配设备 ABI
- 模拟器通常使用 x86_64，真机通常使用 arm64-v8a
