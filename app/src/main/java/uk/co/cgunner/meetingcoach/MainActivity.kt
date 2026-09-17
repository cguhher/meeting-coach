package uk.co.cgunner.meetingcoach

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.webkit.WebViewAssetLoader
import kotlin.concurrent.thread
import kotlin.math.log10
import kotlin.math.sqrt

class MainActivity : ComponentActivity() {
    private lateinit var webView: WebView
    @Volatile private var audioRecord: AudioRecord? = null
    @Volatile private var recording = false
    @Volatile private var currentLevel = 0.0
    @Volatile private var audioError: String? = null

    private val micPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startNativeAudio()
        else audioError = "Microphone permission denied"
    }

    inner class AudioBridge {
        @JavascriptInterface
        fun start(): Boolean {
            runOnUiThread { ensureAudioStarted() }
            return ContextCompat.checkSelfPermission(
                this@MainActivity, Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        }

        @JavascriptInterface
        fun level(): Double = currentLevel

        @JavascriptInterface
        fun error(): String = audioError ?: ""
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        webView = WebView(this)
        setContentView(webView)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            cacheMode = WebSettings.LOAD_NO_CACHE
        }
        webView.addJavascriptInterface(AudioBridge(), "NativeAudio")

        val assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? = assetLoader.shouldInterceptRequest(request.url)
        }

        webView.loadUrl("https://appassets.androidplatform.net/assets/index.html")
    }

    private fun ensureAudioStarted() {
        if (recording) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
        } else {
            startNativeAudio()
        }
    }

    private fun startNativeAudio() {
        if (recording) return
        audioError = null
        val sampleRate = 16000
        val minBuffer = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuffer <= 0) {
            audioError = "Android could not determine a microphone buffer size"
            return
        }

        try {
            val recorder = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuffer, 2048)
            )
            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                recorder.release()
                audioError = "Android could not initialise the microphone"
                return
            }
            recorder.startRecording()
            if (recorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                recorder.release()
                audioError = "Android could not start microphone recording"
                return
            }
            audioRecord = recorder
            recording = true
            thread(name = "MeetingCoachAudio", isDaemon = true) {
                val buffer = ShortArray(1024)
                while (recording) {
                    val n = recorder.read(buffer, 0, buffer.size)
                    if (n > 0) {
                        var sum = 0.0
                        for (i in 0 until n) {
                            val x = buffer[i] / 32768.0
                            sum += x * x
                        }
                        val rms = sqrt(sum / n)
                        val db = 20.0 * log10(maxOf(rms, 0.00001))
                        val mapped = ((db + 60.0) * 1.65).coerceIn(0.0, 100.0)
                        currentLevel = currentLevel * 0.72 + mapped * 0.28
                    } else if (n < 0) {
                        audioError = "Microphone read failed ($n)"
                    }
                }
            }
        } catch (e: SecurityException) {
            audioError = "Microphone permission error: ${e.message ?: "unknown"}"
        } catch (e: Exception) {
            audioError = "Microphone error: ${e.javaClass.simpleName}: ${e.message ?: "unknown"}"
        }
    }

    private fun stopNativeAudio() {
        recording = false
        audioRecord?.let {
            try { it.stop() } catch (_: Exception) {}
            it.release()
        }
        audioRecord = null
    }

    override fun onDestroy() {
        stopNativeAudio()
        webView.destroy()
        super.onDestroy()
    }
}
