package io.github.pmaxai.cardscanner

import android.content.Context
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import io.github.pmaxai.cardscanner.ui.App
import io.github.pmaxai.cardscanner.ui.CardScannerTheme
import org.opencv.android.OpenCVLoader

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val openCvReady = OpenCVLoader.initLocal()
        // portrait for scanning; YouTube mode follows the phone (tripod) - the UI switches it later too
        requestedOrientation = if (getSharedPreferences("settings", Context.MODE_PRIVATE).getBoolean("youtube", false))
            ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)   // scanning at a market stall
        setContent {
            CardScannerTheme {
                App(openCvReady)
            }
        }
    }
}
