package org.example.winsignin

import android.os.SystemClock
import android.util.Log
import android.util.Size
import androidx.activity.compose.BackHandler
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.ZoomSuggestionOptions
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.delay
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 扫码页：CameraX 出预览，ML Kit 逐帧识别二维码。
 *
 * 这里的优化分三层：
 * - P0 输入质量：1280×720 分析流、后台分析线程、离开页面时解绑相机
 * - P1 对准：点击对焦、手电筒、ML Kit 自动变焦、预览/分析统一 16:9
 * - P2 兜底：ML Kit 失败时每 3 帧用 ZXing 再试一次
 *
 * @param onResult 识别到第一个二维码时回调一次（原文）。
 * @param onCancel 点返回箭头或系统返回键时回调。
 */
@Composable
fun QrScannerScreen(
    onResult: (String) -> Unit,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnResult = rememberUpdatedState(onResult)
    val mainExecutor = remember { ContextCompat.getMainExecutor(context) }

    // [P0] 分析不再占用主线程；单线程串行处理，配合 KEEP_ONLY_LATEST 不会堆积。
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }

    val handled = remember { AtomicBoolean(false) }
    val disposed = remember { AtomicBoolean(false) }
    val lastZoomAt = remember { AtomicLong(0L) }
    val frameCounter = remember { AtomicInteger(0) }
    // ML Kit 连续没解出来的帧数：兜底解码只在连续失败后才启动。
    val mlKitMissStreak = remember { AtomicInteger(0) }
    val cameraRef = remember { AtomicReference<Camera?>(null) }

    var camera by remember { mutableStateOf<Camera?>(null) }
    var previewView by remember { mutableStateOf<PreviewView?>(null) }
    var analysisUseCase by remember { mutableStateOf<ImageAnalysis?>(null) }
    var cameraProvider by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    var hasFlash by remember { mutableStateOf(false) }
    var torchOn by remember { mutableStateOf(false) }
    var focusPoint by remember { mutableStateOf<Offset?>(null) }
    var cameraError by remember { mutableStateOf<String?>(null) }

    val scanner: BarcodeScanner = remember {
        // [P1] 使用 ML Kit 自带的变焦建议：远端二维码太小时自动拉近再试。
        val zoomCallback = object : ZoomSuggestionOptions.ZoomCallback {
            override fun setZoom(zoom: Float): Boolean {
                val cam = cameraRef.get() ?: return false
                val state = cam.cameraInfo.zoomState.value ?: return false
                val target = zoom.coerceIn(state.minZoomRatio, state.maxZoomRatio)
                val now = SystemClock.elapsedRealtime()

                // 250ms 内不重复变焦，避免连续建议导致画面抖动。
                if (now - lastZoomAt.get() < 250L) return false
                if (abs(target - state.zoomRatio) < 0.05f) return true

                lastZoomAt.set(now)
                cam.cameraControl.setZoomRatio(target)
                if (BuildConfig.DEBUG) {
                    Log.d("QrScanner", "ML Kit auto zoom -> ${"%.2f".format(target)}x")
                }
                return true
            }
        }

        BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                .setZoomSuggestionOptions(
                    ZoomSuggestionOptions.Builder(zoomCallback)
                        .setMaxSupportedZoomRatio(8f)
                        .build(),
                )
                .build(),
        )
    }

    DisposableEffect(Unit) {
        onDispose {
            // [P0] 先停分析、解绑相机，再关识别器，避免相机常驻和 ImageProxy 泄漏。
            disposed.set(true)
            cameraRef.set(null)
            camera?.cameraControl?.enableTorch(false)
            analysisUseCase?.clearAnalyzer()
            cameraProvider?.unbindAll()
            analysisExecutor.shutdown()
            scanner.close()
        }
    }

    LaunchedEffect(torchOn, camera) {
        camera?.cameraControl?.enableTorch(torchOn)
    }

    LaunchedEffect(focusPoint) {
        if (focusPoint != null) {
            delay(700)
            focusPoint = null
        }
    }

    BackHandler { onCancel() }

    val density = LocalDensity.current
    val focusRingSize = 48.dp
    val focusRingHalfPx = with(density) { focusRingSize.toPx() / 2f }

    Box(
        modifier = Modifier
            .fillMaxSize()
            // [P1] 双指缩放：按当前比例乘缩放因子，夹在相机支持的最小/最大倍率之间
            .pointerInput(Unit) {
                detectTransformGestures { _, _, zoomChange, _ ->
                    val cam = camera ?: return@detectTransformGestures
                    val state = cam.cameraInfo.zoomState.value ?: return@detectTransformGestures
                    val ratio = (state.zoomRatio * zoomChange)
                        .coerceIn(state.minZoomRatio, state.maxZoomRatio)
                    cam.cameraControl.setZoomRatio(ratio)
                }
            }
            // [P1] 点击对焦：同时锁 AF/AE，3 秒后自动恢复
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    val pv = previewView ?: return@detectTapGestures
                    val cam = camera ?: return@detectTapGestures
                    val point = pv.meteringPointFactory.createPoint(offset.x, offset.y)
                    val action = FocusMeteringAction.Builder(
                        point,
                        FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE,
                    )
                        .setAutoCancelDuration(3, TimeUnit.SECONDS)
                        .build()
                    cam.cameraControl.startFocusAndMetering(action)
                    focusPoint = offset
                }
            },
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                val pv = PreviewView(ctx).apply {
                    // [P1] 预览和分析统一 16:9，避免“预览里看得到、分析帧里被裁掉”。
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                }
                previewView = pv
                val executor = ContextCompat.getMainExecutor(ctx)
                val providerFuture = ProcessCameraProvider.getInstance(ctx)

                providerFuture.addListener({
                    if (!disposed.get()) {
                        try {
                            val provider = providerFuture.get()
                            cameraProvider = provider

                            val preview = Preview.Builder()
                                .setResolutionSelector(
                                    ResolutionSelector.Builder()
                                        .setAspectRatioStrategy(
                                            AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY,
                                        )
                                        .build(),
                                )
                                .build()
                                .also { it.setSurfaceProvider(pv.surfaceProvider) }

                            val analysis = ImageAnalysis.Builder()
                                // [P0] 显式指定分析流分辨率，避免默认 640×480 导致远距离二维码像素不足。
                                .setResolutionSelector(
                                    ResolutionSelector.Builder()
                                        .setAspectRatioStrategy(
                                            AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY,
                                        )
                                        .setResolutionStrategy(
                                            ResolutionStrategy(
                                                Size(1280, 720),
                                                ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                                            ),
                                        )
                                        .build(),
                                )
                                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                .build()
                            analysisUseCase = analysis

                            analysis.setAnalyzer(analysisExecutor) { imageProxy ->
                                if (disposed.get() || handled.get()) {
                                    imageProxy.close()
                                    return@setAnalyzer
                                }
                                try {
                                    val frame = frameCounter.incrementAndGet()
                                    if (BuildConfig.DEBUG && frame == 1) {
                                        Log.d(
                                            "QrScanner",
                                            "analysis resolution ${imageProxy.width}x${imageProxy.height}",
                                        )
                                    }

                                    val mediaImage = imageProxy.image
                                    var decoded: String? = null
                                    var decodedBy = ""

                                    if (mediaImage != null) {
                                        val input = InputImage.fromMediaImage(
                                            mediaImage,
                                            imageProxy.imageInfo.rotationDegrees,
                                        )
                                        // 3 秒超时：ML Kit 异常卡住时不要永久占住分析线程。
                                        val barcodes = Tasks.await(
                                            scanner.process(input),
                                            3L,
                                            TimeUnit.SECONDS,
                                        )
                                        // 画面里可能有多个二维码，取面积最大的那个，避免选到背景里的小码。
                                        decoded = barcodes
                                            .filter { !it.rawValue.isNullOrBlank() }
                                            .maxByOrNull { barcode ->
                                                val box = barcode.boundingBox
                                                (box?.width() ?: 0) * (box?.height() ?: 0)
                                            }
                                            ?.rawValue
                                        if (!decoded.isNullOrBlank()) decodedBy = "ML Kit"
                                    }

                                    if (decoded.isNullOrBlank()) {
                                        val miss = mlKitMissStreak.incrementAndGet()
                                        // [P2] 兜底解码：读 Y 平面，成本很低，
                                        // 但仍然只在「ML Kit 连续失败 3 帧」之后、每 4 帧试一次，
                                        // 避免和 ML Kit 抢同一个分析线程。
                                        if (mediaImage != null && miss >= 3 && frame % 4 == 0) {
                                            decoded = decodeQrWithZxing(imageProxy)
                                            if (!decoded.isNullOrBlank()) decodedBy = "ZXing"
                                        }
                                    } else {
                                        mlKitMissStreak.set(0)
                                    }

                                    val result = decoded?.takeIf { it.isNotBlank() }
                                    if (result != null &&
                                        handled.compareAndSet(false, true)
                                    ) {
                                        if (BuildConfig.DEBUG) {
                                            Log.d(
                                                "QrScanner",
                                                "$decodedBy decoded ${result.length} chars at frame $frame",
                                            )
                                        }
                                        mainExecutor.execute {
                                            if (!disposed.get()) currentOnResult.value(result)
                                        }
                                    }
                                } catch (e: InterruptedException) {
                                    // 不要重新设置中断标志：analyzer 复用一个 worker 线程，
                                    // 标志一旦被留下，后续每一帧的 Tasks.await 都会立刻抛
                                    // InterruptedException，扫码会永久静默失效。
                                    Thread.interrupted()
                                } catch (e: Exception) {
                                    // 单帧失败不终止扫码，继续下一帧。
                                    if (BuildConfig.DEBUG) {
                                        Log.d("QrScanner", "frame skipped: ${e.javaClass.simpleName}")
                                    }
                                } finally {
                                    // 识别成功/失败/异常都必须 close，否则相机不再出帧。
                                    imageProxy.close()
                                }
                            }

                            if (disposed.get()) {
                                analysis.clearAnalyzer()
                                provider.unbindAll()
                            } else {
                                provider.unbindAll()
                                val boundCamera = provider.bindToLifecycle(
                                    lifecycleOwner,
                                    CameraSelector.DEFAULT_BACK_CAMERA,
                                    preview,
                                    analysis,
                                )
                                camera = boundCamera
                                cameraRef.set(boundCamera)
                                hasFlash = boundCamera.cameraInfo.hasFlashUnit()
                                cameraError = null
                                if (BuildConfig.DEBUG) {
                                    Log.d("QrScanner", "camera bound, flash=$hasFlash")
                                }
                            }
                        } catch (e: Exception) {
                            cameraProvider?.unbindAll()
                            cameraError = e.message ?: "相机启动失败"
                            if (BuildConfig.DEBUG) {
                                Log.e("QrScanner", "camera bind failed", e)
                            }
                        }
                    }
                }, executor)

                pv
            },
        )

        // [P1] 点击对焦的视觉反馈
        focusPoint?.let { point ->
            Box(
                modifier = Modifier
                    .offset {
                        IntOffset(
                            (point.x - focusRingHalfPx).roundToInt(),
                            (point.y - focusRingHalfPx).roundToInt(),
                        )
                    }
                    .size(focusRingSize)
                    .border(1.5.dp, Color.White.copy(alpha = 0.9f), CircleShape),
            )
        }

        // [P1] 手电筒开关
        if (hasFlash) {
            IconButton(
                onClick = { torchOn = !torchOn },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .navigationBarsPadding()
                    .padding(24.dp)
                    .background(Color.Black.copy(alpha = 0.35f), CircleShape),
            ) {
                Icon(
                    imageVector = if (torchOn) Icons.Filled.FlashOn else Icons.Filled.FlashOff,
                    contentDescription = if (torchOn) "关闭手电筒" else "打开手电筒",
                    tint = Color.White,
                )
            }
        }

        cameraError?.let { message ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.6f)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = message,
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        // 透明返回栏盖在相机预览上
        ScreenTopBar(
            title = "",
            onBack = onCancel,
            transparent = true,
            inset = true,
            modifier = Modifier.align(Alignment.TopCenter),
        )
    }
}
