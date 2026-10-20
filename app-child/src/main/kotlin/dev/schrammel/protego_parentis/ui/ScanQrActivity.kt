package dev.schrammel.protego_parentis.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import dev.schrammel.protego_parentis.R
import dev.schrammel.protego_parentis.sync.core.FamilyTokens
import java.util.concurrent.Executors

/**
 * Points the camera at a family code and hands back what it read.
 *
 * The camera is open for exactly as long as this screen is, frames are examined in
 * memory and dropped, and nothing is written anywhere — there is no capture, no
 * file, no gallery entry. The permission is requested here rather than at install
 * so that a family who never uses a code is never asked for it.
 *
 * **Only a valid family code is accepted.** The decoder will happily read a URL
 * off a poster; this screen checks with [FamilyTokens.decode] before returning and
 * keeps scanning otherwise. Without that, "scan a code" is an invitation to paste
 * an arbitrary attacker-chosen string into the field that decides which server
 * this phone talks to.
 */
class ScanQrActivity : DementorActivity() {

    private lateinit var status: TextView
    private lateinit var preview: PreviewView
    private lateinit var openSettings: Button

    /** One frame at a time, off the main thread. Shut down in [onDestroy]. */
    private val decoding = Executors.newSingleThreadExecutor()

    /**
     * Set the moment a good code is read.
     *
     * Frames keep arriving for a moment after the result is delivered — analysis
     * runs on its own thread and does not stop on request — and without this the
     * screen would finish twice and the caller would be handed the same code again
     * on a dead activity.
     */
    @Volatile
    private var finished = false

    private val reader = MultiFormatReader().apply {
        setHints(
            mapOf(
                // QR only. Letting it try every symbology it knows costs time on
                // every frame to look for barcodes a family will never present.
                DecodeHintType.POSSIBLE_FORMATS to listOf(com.google.zxing.BarcodeFormat.QR_CODE),
            ),
        )
    }

    private val askForCamera = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) start() else refuse()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        preview = PreviewView(this).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH, 0, 1f)
        }
        status = TextView(this).apply {
            setText(R.string.scan_hint)
            styleAs(R.style.TextAppearance_Dementor_Body)
            gravity = Gravity.CENTER
            val pad = dimen(R.dimen.screen_padding)
            setPadding(pad, pad, pad, dimen(R.dimen.space_md))
        }

        setContentView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = ViewGroup.LayoutParams(MATCH, MATCH)
                // The back affordance, above the title: this screen lays out
                // its own root rather than a content column, so the row brings
                // its own margin.
                addView(backRow(sidePadding = dimen(R.dimen.screen_padding)))
                addView(screenTitle(getString(R.string.scan_title)).apply {
                    val pad = dimen(R.dimen.screen_padding)
                    setPadding(pad, dimen(R.dimen.space_sm), pad, dimen(R.dimen.space_sm))
                })
                addView(preview)
                addView(status)
                addView(
                    Button(context).apply {
                        setText(R.string.scan_open_settings)
                        layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply {
                            val pad = dimen(R.dimen.screen_padding)
                            setMargins(pad, 0, pad, dimen(R.dimen.space_sm))
                        }
                        visibility = View.GONE
                        setOnClickListener {
                            startActivity(
                                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                                    .setData(Uri.fromParts("package", packageName, null)),
                            )
                        }
                        openSettings = this
                    },
                )
                addView(
                    Button(context).apply {
                        setText(R.string.scan_type_instead)
                        layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply {
                            val pad = dimen(R.dimen.screen_padding)
                            setMargins(pad, 0, pad, dimen(R.dimen.space_xl))
                        }
                        setOnClickListener { finish() }
                    },
                )
            },
        )

        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) start() else askForCamera.launch(Manifest.permission.CAMERA)
    }

    private fun refuse() {
        status.setText(R.string.scan_no_permission)
        openSettings.visibility = View.VISIBLE
    }

    private fun start() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = runCatching { future.get() }.getOrNull()
            if (provider == null) {
                status.setText(R.string.scan_no_camera)
                return@addListener
            }

            val feed = Preview.Builder().build()
                .also { it.setSurfaceProvider(preview.surfaceProvider) }

            val analysis = ImageAnalysis.Builder()
                // Keep only the newest frame. Queueing them means decoding a
                // backlog of pictures of where the phone used to be pointing.
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { it.setAnalyzer(decoding, ::examine) }

            runCatching {
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, feed, analysis)
            }.onFailure {
                Log.w(TAG, "Could not open the camera", it)
                status.setText(R.string.scan_no_camera)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    /**
     * Looks at one frame.
     *
     * Only the luminance plane is read, which is all a QR decoder wants and means
     * no colour conversion per frame. The image must be closed whatever happens,
     * or the analyser stops receiving frames after a handful and the screen
     * silently stops scanning.
     */
    private fun examine(image: ImageProxy) {
        try {
            if (finished) return

            val plane = image.planes.firstOrNull() ?: return
            val bytes = ByteArray(plane.buffer.remaining())
            plane.buffer.get(bytes)

            val source = PlanarYUVLuminanceSource(
                bytes,
                plane.rowStride,
                image.height,
                0,
                0,
                minOf(plane.rowStride, image.width),
                image.height,
                false,
            )

            val text = runCatching {
                reader.decodeWithState(BinaryBitmap(HybridBinarizer(source)))
            }.getOrNull()?.text ?: return

            // A QR code is not a family code just because it scanned. Anything
            // else is ignored and scanning continues, rather than handing the
            // caller a string that came off a poster.
            if (FamilyTokens.decode(text) == null) {
                runOnUiThread { status.setText(R.string.scan_not_a_family_code) }
                return
            }

            finished = true
            runOnUiThread {
                setResult(RESULT_OK, Intent().putExtra(EXTRA_CODE, text))
                finish()
            }
        } finally {
            image.close()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        decoding.shutdown()
    }

    companion object {
        private const val TAG = "ScanQr"

        const val EXTRA_CODE = "code"

        fun intent(context: Context) = Intent(context, ScanQrActivity::class.java)
    }
}
