package com.bhu.campus.data

import android.graphics.Bitmap
import android.graphics.Matrix
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * 本机二维码识别。
 *
 * 场景：饮水机屏幕上贴的是动态码，用户平时是「拍照 → 支付宝从相册识别」。
 * 这里把同样的活干了：拿到相册图片后先在本地解出内容，再把内容交给支付宝。
 *
 * 用 MLKit 的离线条码识别（模型打包在 APK 里，不需要 GMS、不联网）。
 * 用户拍的往往是暗光下的屏幕，二维码只占画面一小块，所以要走「整图 → 分块放大」多轮，
 * 否则 MLKit 在整图上根本发现不了那个小码。
 */
object QrDecoder {

    private val options = BarcodeScannerOptions.Builder()
        .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
        .build()

    /** 解不出来返回 null。 */
    suspend fun decode(bitmap: Bitmap): String? {
        val base = scaleLongSide(bitmap, 2400)
        // 第一轮：整图 + 2x2 分块，覆盖大部分「码在画面中间」的情况
        for (candidate in buildList {
            add(scaleLongSide(base, 1600))
            addAll(tiles(base, 2))
        }) {
            scanOne(candidate)?.let { return it }
        }
        // 第二轮：3x3 / 4x4 再切细，二维码只占一小块时靠这个
        for (grid in intArrayOf(3, 4)) {
            for (tile in tiles(base, grid)) {
                val up = scaleLongSide(tile, 1100)
                scanOne(up)?.let { return it }
                rotate90(up)?.let { rotated -> scanOne(rotated)?.let { return it } }
            }
        }
        return null
    }

    private suspend fun scanOne(bitmap: Bitmap): String? = suspendCancellableCoroutine { cont ->
        val image = InputImage.fromBitmap(bitmap, 0)
        BarcodeScanning.getClient(options)
            .process(image)
            .addOnSuccessListener { barcodes ->
                val text = barcodes
                    .asSequence()
                    .mapNotNull { it.rawValue }
                    .map { it.trim() }
                    .firstOrNull { it.isNotEmpty() }
                if (cont.isActive) cont.resume(text)
            }
            .addOnFailureListener {
                if (cont.isActive) cont.resume(null)
            }
            .addOnCanceledListener {
                if (cont.isActive) cont.resume(null)
            }
    }

    private fun scaleLongSide(src: Bitmap, target: Int): Bitmap {
        val longSide = maxOf(src.width, src.height)
        if (longSide <= target) return src
        val ratio = target.toFloat() / longSide
        return Bitmap.createScaledBitmap(
            src,
            maxOf(1, (src.width * ratio).toInt()),
            maxOf(1, (src.height * ratio).toInt()),
            true,
        )
    }

    private fun rotate90(src: Bitmap): Bitmap? = runCatching {
        val m = Matrix().apply { postRotate(90f) }
        Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
    }.getOrNull()

    /** 切成 grid x grid 块，带 12% 重叠，避免二维码正好被切线劈开。 */
    private fun tiles(src: Bitmap, grid: Int): List<Bitmap> {
        val result = ArrayList<Bitmap>(grid * grid)
        val cellW = src.width / grid
        val cellH = src.height / grid
        val padW = (cellW * 0.12f).toInt()
        val padH = (cellH * 0.12f).toInt()
        for (row in 0 until grid) {
            for (col in 0 until grid) {
                val left = maxOf(0, col * cellW - padW)
                val top = maxOf(0, row * cellH - padH)
                val right = minOf(src.width, (col + 1) * cellW + padW)
                val bottom = minOf(src.height, (row + 1) * cellH + padH)
                if (right - left < 60 || bottom - top < 60) continue
                runCatching {
                    result.add(Bitmap.createBitmap(src, left, top, right - left, bottom - top))
                }
            }
        }
        return result
    }
}
