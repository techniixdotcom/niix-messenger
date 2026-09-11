package app.niix.ui

import com.journeyapps.barcodescanner.CaptureActivity
import com.journeyapps.barcodescanner.DecoratedBarcodeView
import com.journeyapps.barcodescanner.camera.CameraSettings

class PortraitCaptureActivity : CaptureActivity() {

    override fun onResume() {
        super.onResume()
        // The scanner shows a live camera feed and the contact code being scanned.
        applyScreenshotPolicy()
    }

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
