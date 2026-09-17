package uk.co.cgunner.meetingcoach

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.*
import android.os.Bundle
import android.view.WindowManager
import android.webkit.*
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.webkit.WebViewAssetLoader
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import org.json.JSONArray
import org.json.JSONObject
import kotlin.concurrent.thread
import kotlin.math.*

class MainActivity : ComponentActivity() {
    private lateinit var webView: WebView
    @Volatile private var audioRecord: AudioRecord? = null
    @Volatile private var recording = false
    @Volatile private var currentLevel = 0.0
    @Volatile private var leftLevel = 0.0
    @Volatile private var rightLevel = 0.0
    @Volatile private var actualChannels = 1
    @Volatile private var audioError: String? = null
    @Volatile private var enrollMode = false
    @Volatile private var classification = "NOT ENROLLED"
    @Volatile private var chrisScore = 0.0
    @Volatile private var threshold = 0.55
    @Volatile private var modelReady = false
    private var extractor: SpeakerEmbeddingExtractor? = null
    private var chrisEmbedding: FloatArray? = null
    private val enrollmentEmbeddings = mutableListOf<FloatArray>()
    private val speakerBuffer = FloatArray(24000) // 1.5 s at 16 kHz
    private var speakerBufferPos = 0

    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startNativeAudio() else audioError = "Microphone permission denied"
    }

    inner class AudioBridge {
        @JavascriptInterface fun start(): Boolean { runOnUiThread { ensureAudioStarted() }; return hasMicPermission() }
        @JavascriptInterface fun level(): Double = currentLevel
        @JavascriptInterface fun left(): Double = leftLevel
        @JavascriptInterface fun right(): Double = rightLevel
        @JavascriptInterface fun channels(): Int = actualChannels
        @JavascriptInterface fun error(): String = audioError ?: ""
        @JavascriptInterface fun devices(): String = deviceJson()
        @JavascriptInterface fun status(): String = JSONObject().apply {
            put("source", "VOICE_RECOGNITION"); put("sampleRate", 16000); put("channels", actualChannels)
            put("recording", recording); put("modelReady", modelReady); put("enrolled", chrisEmbedding != null)
            put("enrollSamples", synchronized(enrollmentEmbeddings){enrollmentEmbeddings.size})
            put("classification", classification); put("chrisScore", chrisScore); put("threshold", threshold)
        }.toString()
        @JavascriptInterface fun beginEnroll(who: String) {
            if (who != "chris") return
            synchronized(enrollmentEmbeddings){ enrollmentEmbeddings.clear() }
            enrollMode = true; classification = "ENROLLING CHRIS"
        }
        @JavascriptInterface fun endEnroll() { finishEnrollment() }
        @JavascriptInterface fun clearEnroll() {
            enrollMode=false; chrisEmbedding=null; synchronized(enrollmentEmbeddings){enrollmentEmbeddings.clear()}
            getPreferences(MODE_PRIVATE).edit().remove("chrisEmbedding").apply(); classification="NOT ENROLLED"; chrisScore=0.0
        }
        @JavascriptInterface fun setSpeakerThreshold(v: Double) { threshold=v.coerceIn(0.2,0.9) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        loadEmbedding()
        thread(name="SpeakerModelInit",isDaemon=true) {
            try {
                extractor = SpeakerEmbeddingExtractor(
                    assetManager = assets,
                    config = SpeakerEmbeddingExtractorConfig(
                        model="3dspeaker_speech_eres2net_base_sv_zh-cn_3dspeaker_16k.onnx",
                        numThreads=2, debug=false, provider="cpu"
                    )
                )
                modelReady=true
            } catch(e:Exception) { audioError="Speaker model error: ${e.javaClass.simpleName}: ${e.message ?: "unknown"}" }
        }
        webView = WebView(this); setContentView(webView)
        webView.settings.apply { javaScriptEnabled=true; domStorageEnabled=true; mediaPlaybackRequiresUserGesture=false; cacheMode=WebSettings.LOAD_NO_CACHE }
        webView.addJavascriptInterface(AudioBridge(), "NativeAudio")
        val loader=WebViewAssetLoader.Builder().addPathHandler("/assets/",WebViewAssetLoader.AssetsPathHandler(this)).build()
        webView.webViewClient=object:WebViewClient(){override fun shouldInterceptRequest(view:WebView,request:WebResourceRequest):WebResourceResponse?=loader.shouldInterceptRequest(request.url)}
        webView.loadUrl("https://appassets.androidplatform.net/assets/index.html")
    }

    private fun hasMicPermission()=ContextCompat.checkSelfPermission(this,Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED
    private fun ensureAudioStarted(){if(recording)return;if(!hasMicPermission())micPermission.launch(Manifest.permission.RECORD_AUDIO) else startNativeAudio()}

    private fun deviceJson(): String {
        val am=getSystemService(Context.AUDIO_SERVICE) as AudioManager; val arr=JSONArray()
        for(d in am.getDevices(AudioManager.GET_DEVICES_INPUTS)) arr.put(JSONObject().apply {
            put("id",d.id); put("type",audioType(d.type)); put("product",d.productName.toString())
            put("channels",JSONArray(d.channelCounts.toList())); put("rates",JSONArray(d.sampleRates.toList()))
        }); return arr.toString()
    }
    private fun audioType(t:Int)=when(t){AudioDeviceInfo.TYPE_BUILTIN_MIC->"BUILT_IN_MIC";AudioDeviceInfo.TYPE_BLUETOOTH_SCO->"BLUETOOTH_SCO";AudioDeviceInfo.TYPE_USB_DEVICE->"USB";AudioDeviceInfo.TYPE_USB_HEADSET->"USB_HEADSET";AudioDeviceInfo.TYPE_WIRED_HEADSET->"WIRED_HEADSET";else->"TYPE_$t"}

    private fun startNativeAudio(){
        if(recording)return; audioError=null; val sr=16000
        var mask=AudioFormat.CHANNEL_IN_STEREO; var min=AudioRecord.getMinBufferSize(sr,mask,AudioFormat.ENCODING_PCM_16BIT)
        if(min<=0){mask=AudioFormat.CHANNEL_IN_MONO;min=AudioRecord.getMinBufferSize(sr,mask,AudioFormat.ENCODING_PCM_16BIT)}
        if(min<=0){audioError="No supported 16 kHz microphone configuration";return}
        try{
            var r=AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION,sr,mask,AudioFormat.ENCODING_PCM_16BIT,maxOf(min,4096))
            if(r.state!=AudioRecord.STATE_INITIALIZED && mask==AudioFormat.CHANNEL_IN_STEREO){r.release();mask=AudioFormat.CHANNEL_IN_MONO;min=AudioRecord.getMinBufferSize(sr,mask,AudioFormat.ENCODING_PCM_16BIT);r=AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION,sr,mask,AudioFormat.ENCODING_PCM_16BIT,maxOf(min,4096))}
            if(r.state!=AudioRecord.STATE_INITIALIZED){r.release();audioError="Android could not initialise microphone";return}
            actualChannels=r.channelCount.coerceAtLeast(1);r.startRecording();if(r.recordingState!=AudioRecord.RECORDSTATE_RECORDING){r.release();audioError="Android could not start microphone";return}
            audioRecord=r;recording=true
            thread(name="MeetingCoachAudio",isDaemon=true){val buf=ShortArray(2048);while(recording){val n=r.read(buf,0,buf.size);if(n>0)process(buf,n,sr) else if(n<0)audioError="Microphone read failed ($n)"}}
        }catch(e:Exception){audioError="Microphone error: ${e.javaClass.simpleName}: ${e.message?:"unknown"}"}
    }

    private fun process(b:ShortArray,n:Int,sr:Int){
        var ls=0.0;var rs=0.0;var lc=0;var rc=0
        if(actualChannels>=2){var i=0;while(i+1<n){val l=b[i]/32768.0;val r=b[i+1]/32768.0;ls+=l*l;rs+=r*r;lc++;rc++;i+=2}}
        else{for(i in 0 until n){val x=b[i]/32768.0;ls+=x*x;lc++};rs=ls;rc=lc}
        fun mapped(sum:Double,c:Int):Double{val rms=sqrt(sum/maxOf(c,1));val db=20*log10(max(rms,0.00001));return((db+60)*1.65).coerceIn(0.0,100.0)}
        val l=mapped(ls,lc); val rr=mapped(rs,rc); leftLevel=leftLevel*.72+l*.28;rightLevel=rightLevel*.72+rr*.28;currentLevel=(leftLevel+rightLevel)/2
        var i=0
        while(i<n){var s=0f;for(c in 0 until actualChannels){if(i+c<n)s += b[i+c]/32768f};speakerBuffer[speakerBufferPos++]=s/actualChannels;i+=actualChannels;if(speakerBufferPos==speakerBuffer.size){val chunk=speakerBuffer.copyOf();speakerBufferPos=0;if(modelReady) thread(name="SpeakerEmbed",isDaemon=true){processSpeakerChunk(chunk,sr)}}}
    }

    private fun processSpeakerChunk(samples:FloatArray,sr:Int){
        // Ignore near-silence. We only need a rough conversational coach, not forensic diarisation.
        var power=0.0;for(x in samples)power+=x*x;val rms=sqrt(power/samples.size);if(rms<0.006)return
        try{
            val ex=extractor?:return;val stream=ex.createStream();stream.acceptWaveform(samples,sr);stream.inputFinished();if(!ex.isReady(stream))return
            val emb=ex.compute(stream);stream.release()
            if(enrollMode){synchronized(enrollmentEmbeddings){if(enrollmentEmbeddings.size<30)enrollmentEmbeddings.add(emb)};return}
            val ref=chrisEmbedding?:run{classification="NOT ENROLLED";return};val sim=cosine(ref,emb);chrisScore=sim.toDouble();classification=when{sim>=threshold+0.05->"CHRIS";sim>=threshold-0.04->"UNCERTAIN";else->"NOT CHRIS"}
        }catch(e:Exception){audioError="Speaker check error: ${e.javaClass.simpleName}: ${e.message ?: "unknown"}"}
    }

    private fun finishEnrollment(){
        enrollMode=false;val list=synchronized(enrollmentEmbeddings){enrollmentEmbeddings.toList()};if(list.isEmpty()){classification="ENROLMENT FAILED - SPEAK LONGER";return}
        val avg=FloatArray(list[0].size);for(e in list)for(i in avg.indices)avg[i]+=e[i];for(i in avg.indices)avg[i]/=list.size
        normalise(avg);chrisEmbedding=avg;saveEmbedding(avg);classification="CHRIS ENROLLED"
    }
    private fun cosine(a:FloatArray,b:FloatArray):Float{var dot=0.0;var aa=0.0;var bb=0.0;val n=min(a.size,b.size);for(i in 0 until n){dot+=a[i]*b[i];aa+=a[i]*a[i];bb+=b[i]*b[i]};return if(aa==0.0||bb==0.0)0f else (dot/sqrt(aa*bb)).toFloat()}
    private fun normalise(a:FloatArray){var s=0.0;for(x in a)s+=x*x;val d=sqrt(s).toFloat();if(d>0)for(i in a.indices)a[i]/=d}
    private fun saveEmbedding(a:FloatArray){getPreferences(MODE_PRIVATE).edit().putString("chrisEmbedding",a.joinToString(",")).apply()}
    private fun loadEmbedding(){val s=getPreferences(MODE_PRIVATE).getString("chrisEmbedding",null)?:return;try{chrisEmbedding=s.split(',').map{it.toFloat()}.toFloatArray()}catch(_:Exception){}}

    private fun stopNativeAudio(){recording=false;audioRecord?.let{try{it.stop()}catch(_:Exception){};it.release()};audioRecord=null;extractor?.release();extractor=null}
    override fun onDestroy(){stopNativeAudio();webView.destroy();super.onDestroy()}
}
