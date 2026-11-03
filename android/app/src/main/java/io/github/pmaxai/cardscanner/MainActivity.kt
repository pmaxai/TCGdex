package io.github.pmaxai.cardscanner

import android.content.Context
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import io.github.pmaxai.cardscanner.ui.App
import io.github.pmaxai.cardscanner.ui.CardScannerTheme
import io.github.pmaxai.cardscanner.ui.orientationOf
import org.opencv.android.OpenCVLoader

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val openCvReady = OpenCVLoader.initLocal()
        // the orientation setting (auto / portrait / landscape) right away, so the app doesn't turn twice
        val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        requestedOrientation = orientationOf(prefs.getString("orientation", null)
            ?: if (prefs.getBoolean("youtube", false)) "auto" else "portrait")
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)   // scanning at a market stall
        setContent {
            CardScannerTheme {
                App(openCvReady)
            }
        }
    }
}
