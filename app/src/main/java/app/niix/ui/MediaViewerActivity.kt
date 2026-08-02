package app.niix.ui

import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.MediaController
import android.widget.VideoView
import app.niix.R
import java.io.File

class MediaViewerActivity : SecureActivity() {

    private var mediaPath: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_media_viewer)
        val path = intent.getStringExtra(EXTRA_PATH)
        val mime = intent.getStringExtra(EXTRA_MIME).orEmpty()
        if (path == null) { finish(); return }
        mediaPath = path

        val image = findViewById<ImageView>(R.id.viewer_image)
        val video = findViewById<VideoView>(R.id.viewer_video)

        if (mime.startsWith("image/")) {
            image.visibility = View.VISIBLE
            video.visibility = View.GONE
            image.setImageBitmap(BitmapFactory.decodeFile(path))
        } else {
            image.visibility = View.GONE
            video.visibility = View.VISIBLE
            val controller = MediaController(this)
            controller.setAnchorView(video)
            video.setMediaController(controller)
            video.setVideoURI(Uri.fromFile(File(path)))
            video.setOnPreparedListener { it.start() }
        }
    }

    override fun onDestroy() {
        // This is a decrypted plaintext copy made only so the system player/viewer can read it;
        // it must not outlive the screen that's showing it.
        mediaPath?.let { runCatching { File(it).delete() } }
        super.onDestroy()
    }

    companion object {
        const val EXTRA_PATH = "path"
        const val EXTRA_MIME = "mime"
    }
}
