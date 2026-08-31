package app.niix.ui

import com.journeyapps.barcodescanner.CaptureActivity
import com.journeyapps.barcodescanner.DecoratedBarcodeView
import com.journeyapps.barcodescanner.camera.CameraSettings

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
