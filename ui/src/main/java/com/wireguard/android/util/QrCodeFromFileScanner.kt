/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.wireguard.android.util

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.NotFoundException
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.Reader
import com.google.zxing.Result
import com.google.zxing.common.HybridBinarizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Encapsulates the logic of scanning a barcode from a file,
 * @property contentResolver - Resolver to read the incoming data
 * @property reader - An instance of zxing's [Reader] class to parse the image
 */
class QrCodeFromFileScanner(
    private val contentResolver: ContentResolver,
    private val reader: Reader,
) {
    private fun scanBitmapForResult(source: Bitmap): Result {
        val width = source.width
        val height = source.height
        val pixels = IntArray(width * height)
        source.getPixels(pixels, 0, width, 0, 0, width, height)

        val bBitmap = BinaryBitmap(HybridBinarizer(RGBLuminanceSource(width, height, pixels)))
        return try {
            reader.decode(
                bBitmap,
                mapOf(DecodeHintType.TRY_HARDER to true),
            )
        } finally {
            reader.reset()
        }
    }

    private fun doScan(data: Uri): Result {
        Log.d(TAG, "Starting to scan an image: $data")
        var firstException: Throwable? = null
        for (sampleSize in arrayOf(1, 2, 4, 8, 16, 32, 64, 128)) {
            var bitmap: Bitmap? = null
            try {
                val decoded = contentResolver.openInputStream(data).use { inputStream ->
                    if (inputStream == null) throw IllegalArgumentException("Can't open image stream")
                    BitmapFactory.decodeStream(
                        inputStream,
                        null,
                        BitmapFactory.Options().apply { inSampleSize = sampleSize },
                    ) ?: throw IllegalArgumentException("Can't decode stream for bitmap")
                }
                bitmap = decoded
                return scanBitmapForResult(decoded)
            } catch (e: Throwable) {
                Log.e(TAG, "Image scan at scale factor $sampleSize finished with error: $e")
                if (firstException == null) firstException = e
            } finally {
                bitmap?.recycle()
            }
        }
        throw Exception(firstException)
    }

    /**
     * Attempts to parse incoming data
     * @return result of the decoding operation
     * @throws NotFoundException when parser didn't find QR code in the image
     */
    suspend fun scan(data: Uri) = withContext(Dispatchers.Default) { doScan(data) }

    companion object {
        private const val TAG = "QrCodeFromFileScanner"

        /**
         * Given a reference to a file, check if this file could be parsed by this class
         * @return true if the file can be parsed, false if not
         */
        fun validContentType(contentResolver: ContentResolver, data: Uri): Boolean {
            return contentResolver.getType(data)?.startsWith("image/") == true
        }
    }
}
