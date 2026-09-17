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
    @Volatile private var enrollMode = ""
    @Volatile private var classification = "UNTRAINED"
    @Volatile private var chrisScore = 0.0
    @Volatile private var otherScore = 0.0
    private val chrisFeatures = mutableListOf<DoubleArray>()
    private val otherFeatures = mutableListOf<DoubleArray>()

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
            put("recording", recording); put("chrisSamples", synchronized(chrisFeatures){chrisFeatures.size})
            put("otherSamples", synchronized(otherFeatures){otherFeatures.size}); put("classification", classification)
            put("chrisScore", chrisScore); put("otherScore", otherScore)
        }.toString()
        @JavascriptInterface fun beginEnroll(who: String) { enrollMode = if (who == "chris") "chris" else "other" }
        @JavascriptInterface fun endEnroll() { enrollMode = "" }
        @JavascriptInterface fun clearEnroll() { synchronized(chrisFeatures){chrisFeatures.clear()}; synchronized(otherFeatures){otherFeatures.clear()}; classification="UNTRAINED" }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
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
        })
        return arr.toString()
    }
    private fun audioType(t:Int)=when(t){AudioDeviceInfo.TYPE_BUILTIN_MIC->"BUILT_IN_MIC";AudioDeviceInfo.TYPE_BLUETOOTH_SCO->"BLUETOOTH_SCO";AudioDeviceInfo.TYPE_USB_DEVICE->"USB";AudioDeviceInfo.TYPE_USB_HEADSET->"USB_HEADSET";AudioDeviceInfo.TYPE_WIRED_HEADSET->"WIRED_HEADSET";else->"TYPE_$t"}

    private fun startNativeAudio(){
        if(recording)return; audioError=null; val sr=16000
        var mask=AudioFormat.CHANNEL_IN_STEREO
        var min=AudioRecord.getMinBufferSize(sr,mask,AudioFormat.ENCODING_PCM_16BIT)
        if(min<=0){mask=AudioFormat.CHANNEL_IN_MONO;min=AudioRecord.getMinBufferSize(sr,mask,AudioFormat.ENCODING_PCM_16BIT)}
        if(min<=0){audioError="No supported 16 kHz microphone configuration";return}
        try{
            var r=AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION,sr,mask,AudioFormat.ENCODING_PCM_16BIT,maxOf(min,4096))
            if(r.state!=AudioRecord.STATE_INITIALIZED && mask==AudioFormat.CHANNEL_IN_STEREO){r.release();mask=AudioFormat.CHANNEL_IN_MONO;min=AudioRecord.getMinBufferSize(sr,mask,AudioFormat.ENCODING_PCM_16BIT);r=AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION,sr,mask,AudioFormat.ENCODING_PCM_16BIT,maxOf(min,4096))}
            if(r.state!=AudioRecord.STATE_INITIALIZED){r.release();audioError="Android could not initialise microphone";return}
            actualChannels=r.channelCount.coerceAtLeast(1);r.startRecording();if(r.recordingState!=AudioRecord.RECORDSTATE_RECORDING){r.release();audioError="Android could not start microphone";return}
            audioRecord=r;recording=true
            thread(name="MeetingCoachAudio",isDaemon=true){
                val buf=ShortArray(2048)
                while(recording){val n=r.read(buf,0,buf.size);if(n>0)process(buf,n,sr) else if(n<0)audioError="Microphone read failed ($n)"}
            }
        }catch(e:Exception){audioError="Microphone error: ${e.javaClass.simpleName}: ${e.message?:"unknown"}"}
    }

    private fun process(b:ShortArray,n:Int,sr:Int){
        var ls=0.0;var rs=0.0;var lc=0;var rc=0
        if(actualChannels>=2){var i=0;while(i+1<n){val l=b[i]/32768.0;val r=b[i+1]/32768.0;ls+=l*l;rs+=r*r;lc++;rc++;i+=2}}
        else{for(i in 0 until n){val x=b[i]/32768.0;ls+=x*x;lc++};rs=ls;rc=lc}
        fun mapped(sum:Double,c:Int):Double{val rms=sqrt(sum/maxOf(c,1));val db=20*log10(max(rms,0.00001));return((db+60)*1.65).coerceIn(0.0,100.0)}
        val l = mapped(ls,lc)
        val rr = mapped(rs,rc)
        leftLevel=leftLevel*.72+l*.28;rightLevel=rightLevel*.72+rr*.28;currentLevel=(leftLevel+rightLevel)/2
        if(currentLevel>8){val mono=DoubleArray(min(n/actualChannels,512));for(i in mono.indices){var s=0.0;for(c in 0 until actualChannels)s+=b[i*actualChannels+c]/32768.0;mono[i]=s/actualChannels};val f=features(mono,sr)
            when(enrollMode){"chris"->synchronized(chrisFeatures){if(chrisFeatures.size<500)chrisFeatures.add(f)};"other"->synchronized(otherFeatures){if(otherFeatures.size<500)otherFeatures.add(f)}}
            classify(f)
        }
    }

    // Lightweight acoustic fingerprint for diagnostics, not a biometric speaker-ID model.
    private fun features(x:DoubleArray,sr:Int):DoubleArray{
        val freqs=doubleArrayOf(180.0,300.0,500.0,800.0,1200.0,1800.0,2600.0,3600.0);val out=DoubleArray(freqs.size+1)
        var z=0;for(i in 1 until x.size)if((x[i]>=0)!=(x[i-1]>=0))z++;out[0]=z.toDouble()/x.size
        for(k in freqs.indices){val w=2*PI*freqs[k]/sr;val coeff=2*cos(w);var q0:Double;var q1=0.0;var q2=0.0;for(v in x){q0=coeff*q1-q2+v;q2=q1;q1=q0};val p=max(1e-12,q1*q1+q2*q2-coeff*q1*q2);out[k+1]=ln(p)}
        val mean=out.drop(1).average();for(i in 1 until out.size)out[i]-=mean;return out
    }
    private fun centroid(a:List<DoubleArray>):DoubleArray?{if(a.size<8)return null;val c=DoubleArray(a[0].size);for(v in a)for(i in c.indices)c[i]+=v[i];for(i in c.indices)c[i]/=a.size;return c}
    private fun dist(a:DoubleArray,b:DoubleArray)=sqrt(a.indices.sumOf{(a[it]-b[it]).pow(2)})
    private fun classify(f:DoubleArray){val c=synchronized(chrisFeatures){centroid(chrisFeatures)};val o=synchronized(otherFeatures){centroid(otherFeatures)};if(c==null||o==null){classification="UNTRAINED";return};val dc=dist(f,c);val dO=dist(f,o);val sum=max(dc+dO,1e-6);chrisScore=(dO/sum).coerceIn(0.0,1.0);otherScore=(dc/sum).coerceIn(0.0,1.0);classification=if(chrisScore>otherScore)"CHRIS" else "OTHER"}

    private fun stopNativeAudio(){recording=false;audioRecord?.let{try{it.stop()}catch(_:Exception){};it.release()};audioRecord=null}
    override fun onDestroy(){stopNativeAudio();webView.destroy();super.onDestroy()}
}
