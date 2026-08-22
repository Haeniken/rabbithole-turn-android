/*
 * Copyright © 2026 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.activity

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import com.google.zxing.client.android.Intents
import com.google.zxing.qrcode.QRCodeReader
import com.journeyapps.barcodescanner.CaptureActivity
import com.journeyapps.barcodescanner.DecoratedBarcodeView
import com.wireguard.android.Application
import com.wireguard.android.R
import com.wireguard.android.util.ErrorMessages
import com.wireguard.android.util.QrCodeFromFileScanner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Camera QR scanner with an image picker available directly from the capture screen. */
class QrCaptureActivity : CaptureActivity() {
    private val scanScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var scannerView: DecoratedBarcodeView
    private var scanningFile = false

    override fun initializeContent(): DecoratedBarcodeView {
        setContentView(R.layout.qr_capture_activity)
        scannerView = findViewById(R.id.zxing_barcode_scanner)
        findViewById<android.view.View>(R.id.select_qr_image).setOnClickListener {
            if (!scanningFile) openImagePicker()
        }
        return scannerView
    }

    private fun openImagePicker() {
        scanningFile = true
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
        }
        startActivityForResult(intent, REQUEST_SELECT_QR_IMAGE)
    }

    override fun onResume() {
        super.onResume()
        if (scanningFile) scannerView.pause()
    }

    @Deprecated("Deprecated in Android API; required by CaptureActivity's Activity base class")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_SELECT_QR_IMAGE) return

        val uri = data?.data
        if (resultCode != Activity.RESULT_OK || uri == null) {
            scanningFile = false
            scannerView.resume()
            return
        }

        scannerView.pause()
        scanScope.launch {
            try {
                val result = QrCodeFromFileScanner(contentResolver, QRCodeReader()).scan(uri)
                val resultIntent = Intent(Intents.Scan.ACTION).apply {
                    putExtra(Intents.Scan.RESULT, result.text)
                    putExtra(Intents.Scan.RESULT_FORMAT, result.barcodeFormat.toString())
                    putExtra(Intents.Scan.RESULT_BYTES, result.rawBytes)
                }
                setResult(Activity.RESULT_OK, resultIntent)
                finish()
            } catch (e: Exception) {
                val error = ErrorMessages[e]
                Toast.makeText(
                    this@QrCaptureActivity,
                    Application.get().resources.getString(R.string.import_error, error),
                    Toast.LENGTH_LONG,
                ).show()
                scanningFile = false
                scannerView.resume()
            }
        }
    }

    override fun onDestroy() {
        scanScope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val REQUEST_SELECT_QR_IMAGE = 8102
    }
}
