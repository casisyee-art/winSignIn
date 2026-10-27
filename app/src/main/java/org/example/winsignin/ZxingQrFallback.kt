package org.example.winsignin

import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.Matrix
import androidx.camera.core.ImageProxy
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import java.nio.ByteBuffer

// [P2] ZXing 兜底解码。
//
// 只在 ML Kit 连续失败若干帧后、按间隔调用，不参与主路径。
//
// 关键点：走 YUV 的 Y 平面，不做 Bitmap 转换。
// 之前用 imageProxy.toBitmap() 的写法，1280×720 每次要分配
// ~3.7MB(ARGB Bitmap) + ~3.7MB(旋转副本) + ~3.7MB(IntArray)，
// 单次 50～150ms，正好把 P0 提上来的帧率吃掉。
// 现在只读 Y 平面（约 0.9MB ByteArray），并把旋转直接写在拷贝过程中，
// 分配量和耗时都降到原来的十分之一量级。
// ZXing 只吃灰度，不需要 ARGB，所以这条路径在精度上也没有损失。

private val DECODE_HINTS: Map<DecodeHintType, Any> = mapOf(
    DecodeHintType.TRY_HARDER to true,
    DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
)

/**
 * 从一帧里解二维码；失败返回 null（不抛异常）。
 *
 * [ImageProxy] 由调用方负责 close，这里不会关闭它。
 */
fun decodeQrWithZxing(imageProxy: ImageProxy): String? = try {
    val yPlane = imageProxy.planes.firstOrNull()
    if (yPlane != null && imageProxy.format == ImageFormat.YUV_420_888) {
        decodeQrFromYuvPlane(
            buffer = yPlane.buffer,
            rowStride = yPlane.rowStride,
            pixelStride = yPlane.pixelStride,
            width = imageProxy.width,
            height = imageProxy.height,
            rotationDegrees = imageProxy.imageInfo.rotationDegrees,
        )
    } else {
        // 兜底分支：只有 ImageAnalysis 被改成非 YUV 输出时才会走到，
        // 正常配置下不会执行。
        decodeQrFromBitmap(imageProxy)
    }
} catch (_: Exception) {
    null
}

/**
 * 只读 Y 平面做灰度解码，旋转在拷贝时完成。
 *
 * Y 平面每行有 [rowStride] 字节（可能大于 [width]），像素间隔是 [pixelStride]。
 */
private fun decodeQrFromYuvPlane(
    buffer: ByteBuffer,
    rowStride: Int,
    pixelStride: Int,
    width: Int,
    height: Int,
    rotationDegrees: Int,
): String? {
    if (width <= 0 || height <= 0 || rowStride <= 0 || pixelStride <= 0) return null

    val src = buffer.duplicate()
    val rotated = rotationDegrees % 360
    val uprightWidth = if (rotated % 180 == 0) width else height
    val uprightHeight = if (rotated % 180 == 0) height else width
    val data = ByteArray(uprightWidth * uprightHeight)

    when (rotated) {
        90 -> for (y in 0 until height) {
            val rowStart = y * rowStride
            val dstColumn = height - 1 - y
            for (x in 0 until width) {
                data[x * uprightWidth + dstColumn] = src.get(rowStart + x * pixelStride)
            }
        }

        180 -> for (y in 0 until height) {
            val rowStart = y * rowStride
            val dstRow = (height - 1 - y) * uprightWidth
            for (x in 0 until width) {
                data[dstRow + (width - 1 - x)] = src.get(rowStart + x * pixelStride)
            }
        }

        270 -> for (y in 0 until height) {
            val rowStart = y * rowStride
            for (x in 0 until width) {
                data[(width - 1 - x) * uprightWidth + y] = src.get(rowStart + x * pixelStride)
            }
        }

        else -> for (y in 0 until height) {
            val rowStart = y * rowStride
            val dstRow = y * uprightWidth
            for (x in 0 until width) {
                data[dstRow + x] = src.get(rowStart + x * pixelStride)
            }
        }
    }

    val source = PlanarYUVLuminanceSource(
        data,
        uprightWidth,
        uprightHeight,
        0,
        0,
        uprightWidth,
        uprightHeight,
        false,
    )
    val result = MultiFormatReader().apply { setHints(DECODE_HINTS) }.decode(
        BinaryBitmap(HybridBinarizer(source)),
    )
    return result.text
}

/** 非 YUV 输出时的兜底：Bitmap 路径，成本较高，正常配置不会走到。 */
private fun decodeQrFromBitmap(imageProxy: ImageProxy): String? {
    val bitmap = try {
        imageProxy.toBitmap()
    } catch (_: Exception) {
        return null
    }

    // 用 val 保存旋转副本：捕获中的可变变量无法智能转换，val 才能安全回收。
    val rotated: Bitmap? = try {
        val degrees = imageProxy.imageInfo.rotationDegrees % 360
        if (degrees != 0) {
            Bitmap.createBitmap(
                bitmap,
                0,
                0,
                bitmap.width,
                bitmap.height,
                Matrix().apply { postRotate(degrees.toFloat()) },
                true,
            )
        } else {
            null
        }
    } catch (_: Exception) {
        null
    }

    return try {
        val upright = rotated ?: bitmap
        val width = upright.width
        val height = upright.height
        val pixels = IntArray(width * height)
        upright.getPixels(pixels, 0, width, 0, 0, width, height)

        val binary = BinaryBitmap(HybridBinarizer(RGBLuminanceSource(width, height, pixels)))
        MultiFormatReader().apply { setHints(DECODE_HINTS) }.decode(binary).text
    } catch (_: Exception) {
        null
    } finally {
        if (rotated != null && rotated !== bitmap) rotated.recycle()
        bitmap.recycle()
    }
}
