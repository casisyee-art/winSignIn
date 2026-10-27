# 更新日志

## Unreleased — 扫码管线优化（2026-10-27）

> 当前状态：**代码已修改，尚未构建成功，暂无 APK。**
>
> 构建阻塞原因：本机缺少 Android SDK、Rust、cargo-ndk 和 NDK，`assembleDebug` 无法完成。

### 新增

- 新增 `app/src/main/java/org/example/winsignin/ZxingQrFallback.kt`
  - ML Kit 未识别出二维码时，用 ZXing 做兜底解码。
  - 只识别二维码，使用 `TRY_HARDER`。
  - 支持按 `ImageProxy.imageInfo.rotationDegrees` 旋转后再解码。
  - 解码完成后回收 Bitmap，避免内存泄漏。

### 修改

#### `QrScannerScreen.kt`

**P0：输入质量与生命周期**

- `ImageAnalysis` 显式指定 1280×720 分析分辨率，不再使用 CameraX 默认约 640×480。
- Preview 与 ImageAnalysis 统一使用 16:9，减少预览画面和分析帧的取景差异。
- analyzer 从主线程 executor 改为单线程后台 executor，避免主线程卡顿导致掉帧。
- 离开扫码页时：
  - 设置 `disposed` 标记
  - 关闭手电筒
  - `clearAnalyzer()`
  - `unbindAll()`
  - 关闭 executor
  - 关闭 ML Kit scanner
- 单帧识别增加 3 秒超时，避免 ML Kit 异常时永久占住分析线程。
- 相机绑定失败时显示错误提示，不再直接崩溃。

**P1：对焦、变焦、手电筒**

- 新增点击对焦：
  - 使用 `PreviewView.meteringPointFactory` 把点击坐标转换为相机坐标。
  - 同时锁定 AF/AE。
  - 3 秒后自动恢复。
  - 界面上显示白色对焦圈。
- 保留双指缩放。
- 接入 ML Kit `ZoomSuggestionOptions`：
  - 远距离二维码过小时，ML Kit 建议 zoom ratio。
  - 自动调用 CameraX `setZoomRatio`。
  - 最大支持 8x，实际受设备上限限制。
  - 250ms 内不重复变焦，减少画面抖动。
- 新增手电筒开关：
  - 只有摄像头支持闪光灯时才显示。
  - 位于扫码页右下角。
  - 离开页面时自动关闭。

**P2：兜底解码与调试**

- ML Kit 没有解出二维码时，每 3 帧调用一次 ZXing 兜底。
- 画面里有多个二维码时，取面积最大的一个，避免选到背景里的小码。
- debug 构建增加日志：
  - 实际分析分辨率
  - ML Kit 解码成功
  - ZXing 兜底解码成功
  - ML Kit 自动变焦
  - 相机绑定与单帧异常
- 日志只记录二维码长度，不记录二维码原文。

#### `app/build.gradle.kts`

- 新增依赖：

```kotlin
implementation("com.google.zxing:core:3.5.3")
```

用于 ML Kit 失败时的二维码兜底解码。

### 复审修复（同日）

审阅第一版改动后修掉两处「必须修」：

1. **analyzer 线程中断标志未清 → 扫码会永久静默失效**
   - 原写法在 `InterruptedException` 里调用 `Thread.currentThread().interrupt()`。
   - analyzer 复用一个 worker 线程，标志一旦留下，后续每帧的 `Tasks.await`
     都立刻抛 `InterruptedException`，扫码彻底停止且没有任何提示。
   - 改为 `Thread.interrupted()` 清掉标志后正常返回。

2. **ZXing 兜底的开销抵消了 P0 的收益**
   - 原写法走 `imageProxy.toBitmap()`：1280×720 每次约 3.7MB Bitmap
     + 3.7MB 旋转副本 + 3.7MB `IntArray`，单次 50～150ms，
     还和 ML Kit 抢同一个单线程，等于把提上来的帧率又吃掉。
   - 改为直接读 YUV 的 Y 平面（约 0.9MB `ByteArray`），旋转在拷贝时完成，
     走 `PlanarYUVLuminanceSource`，不再生成 Bitmap。
   - 触发条件也从「每 3 帧」收紧为「ML Kit 连续失败 3 帧后，每 4 帧一次」。

顺带修的编译问题：

- `decodeQrFromBitmap` 里 `var rotated` 被 lambda 捕获，无法智能转换，
  `rotated.recycle()` 编译不过；改为 `val` 局部变量。
- 顶层说明改成 `//` 文件注释，避免 KDoc 挂到 `DECODE_HINTS` 上。

### 行为变化

- 扫码页新增点击对焦和对焦圈。
- 扫码页新增手电筒按钮。
- 远距离二维码会自动尝试拉近。
- ML Kit 失败时会用 ZXing 再试一次。
- 离开扫码页后相机和识别器会被释放。
- 相机启动失败时会显示错误提示，不再直接崩溃。

### 测试与验证

- 用 Kotlin 2.2.10 + Compose 编译器插件，对 `QrScannerScreen.kt`、
  `ZxingQrFallback.kt`、`ScreenHeader.kt` 做了**完整类型检查编译**，
  真实 classpath 包含 android-34 / CameraX 1.4.2 / ML Kit 17.3.0 /
  ZXing 3.5.3 / Compose 1.10.4 / material3 1.4.0：**0 error、0 warning**。
- 用脚本验证了 Y 平面旋转索引在 0/90/180/270 下与标准顺时针旋转一致，
  且是双射（4x3、3x2、1280x720、720x1280 全部通过）。
- 已用 CameraX 1.4.2、ML Kit 17.3.0、ZXing 3.5.3 的 AAR/JAR 校验关键 API：
  - `ResolutionSelector` / `ResolutionStrategy`
  - `FocusMeteringAction`
  - `ZoomSuggestionOptions`
  - `ImageProxy.toBitmap()`
  - `Tasks.await(...)`
  - ZXing `RGBLuminanceSource` / `HybridBinarizer` / `MultiFormatReader`
- **尚未完成完整编译和真机测试。**

### 构建阻塞

当前环境缺少：

- Android SDK 36
- JDK 21（系统默认是 JDK 25；IntelliJ 自带 JDK 21 可用）
- Rust / cargo
- cargo-ndk
- Android NDK 27.0.12077973
- `app/src/main/jniLibs/arm64-v8a/libwinsignin.so`

Gradle 实际报错：

```text
SDK location not found. Define a valid SDK location with an
ANDROID_HOME environment variable or by setting the sdk.dir path
in local.properties at .../winSignIn/local.properties
```

因此没有生成：

```text
app/build/outputs/apk/debug/app-debug.apk
```

### 已知限制

- 还没有真机验证远距离、斜角度、低光下的实际提升。
- ZXing 兜底每 3 帧执行一次，1280×720 的 Bitmap 转换和 `TRY_HARDER` 会增加 CPU 开销；如果实测掉帧明显，可以改成每 5 帧一次或移除。
- ML Kit 自动变焦可能覆盖手动双指缩放，这是当前实现的预期行为。
- 没有直接替换 ML Kit，ZXing 只作为兜底；ML Kit 仍负责主要识别和自动变焦。
- 当前没有修改版本号，`versionName` 仍为 `1.2`。

### 回滚方式

1. 恢复 `QrScannerScreen.kt` 到修改前版本。
2. 删除 `ZxingQrFallback.kt`。
3. 从 `app/build.gradle.kts` 移除：

```kotlin
implementation("com.google.zxing:core:3.5.3")
```

### 相关文档

- `scannerTest/README.md`：扫码效果 A/B 试验（简版）
- `scannerTest/optimization-plan.md`：扫码优化方案与优先级
