package com.soundvision.audio_encoder

import android.content.res.AssetManager
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.view.View
import com.soundvision.audio_encoder.databinding.ActivityMainBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : AppCompatActivity() {

    private val TAG  = "MainActivity"
    private lateinit var binding: ActivityMainBinding
    private val encoder: AudioEncoder by lazy { AudioEncoder() }
    private val decoder: AudioDecoder by lazy { AudioDecoder() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Example of a call to a native method
        context = application
    }

    private external fun nativeEncode(assetManager: AssetManager, dest: String): Int
    private external fun nativeDecode(src: String, dest: String):Int
    companion object {
        // Used to load the 'audio_encoder' library on application startup.
        init {
            System.loadLibrary("audio_encoder")
        }
    }

    fun convertToAcc(view: View) {
       CoroutineScope (Dispatchers.Default).launch {
            encoder.convertPcmToAac()
        }
    }

    fun convertToPcm(view: View) {
        CoroutineScope (Dispatchers.Default).launch {
            decoder.convertAacToPcm()
        }
    }

    fun nativeToAAC(view: View) {
        CoroutineScope (Dispatchers.Default).launch {
            val file = File(application.filesDir, "native_haidao.aac")
            nativeEncode(assets, file.path)
        }
    }

    fun nativeToPcm(view: View) {
        CoroutineScope (Dispatchers.Default).launch {
            val src = File(application.filesDir, "native_haidao.aac")
            val dest = File(application.filesDir, "native_haidao.pcm")
            nativeDecode(src.path, dest.path)
        }
    }

}