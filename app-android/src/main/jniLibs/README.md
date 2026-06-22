# 获取 libmpv.so

## 方法 1：从 mpv-android APK 提取（推荐）

1. 下载最新 mpv-android APK：
   - https://github.com/mpv-android/mpv-android/releases
   - 选择 `app-universal-debug.apk` 或 `app-arm64-v8a-debug.apk`

2. APK 本质是 ZIP 文件，用解压工具打开

3. 找到 `lib/` 目录，按 ABI 复制 `.so` 文件：
   ```
   lib/arm64-v8a/libmpv.so     → jniLibs/arm64-v8a/
   lib/armeabi-v7a/libmpv.so   → jniLibs/armeabi-v7a/
   lib/x86_64/libmpv.so        → jniLibs/x86_64/
   ```

4. 如果有其他依赖库（如 libffmpeg.so 等），一并复制

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

- `libmpv.so` 是自包含的（静态链接了 ffmpeg 等依赖）
- 仅需要 `libmpv.so`，不需要 `libmpv-full.so`
- 如果加载失败，检查 `.so` 是否匹配设备 ABI
- 模拟器通常使用 x86_64，真机通常使用 arm64-v8a
