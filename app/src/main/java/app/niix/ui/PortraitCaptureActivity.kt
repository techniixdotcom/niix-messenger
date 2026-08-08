package app.niix.ui

import com.journeyapps.barcodescanner.CaptureActivity
import com.journeyapps.barcodescanner.DecoratedBarcodeView
import com.journeyapps.barcodescanner.camera.CameraSettings

/**
 * A portrait-locked scanner (the library default is landscape/fullscreen) tuned for much
 * faster, more reliable detection than the library's stock camera settings.
 *
 * The library defaults to single-shot autofocus -- it focuses once and only refocuses when
 * something explicitly triggers it -- which is exactly the "move it in and out, tilt it, wait"
 * behavior this fixes: continuous autofocus keeps the lens actively hunting for a sharp focus
 * the whole time instead of settling once and going stale as the phone moves. Metering and
 * barcode scene mode let the camera bias its exposure toward what's inside the scan box (a QR
 * code -- high-contrast black and white) rather than the average brightness of the whole frame,
 * which is what usually makes a code wash out or go too dark to decode.
 */
class PortraitCaptureActivity : CaptureActivity() {

    override fun initializeContent(): DecoratedBarcodeView {
        val view = DecoratedBarcodeView(this)
        view.barcodeView.setCameraSettings(
            CameraSettings().apply {
                setAutoFocusEnabled(true)
                setContinuousFocusEnabled(true)
                setMeteringEnabled(true)
                setExposureEnabled(true)
                setBarcodeSceneModeEnabled(true)
            },
        )
        setContentView(view)
        return view
    }
}
