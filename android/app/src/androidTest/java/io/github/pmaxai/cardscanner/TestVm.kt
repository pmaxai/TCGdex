package io.github.pmaxai.cardscanner

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import io.github.pmaxai.cardscanner.ui.ScannerViewModel

/**
 * One view model for the screenshot tests: each one loads the card data (tens of MB), and the
 * tests share one process – a view model per test ran it out of memory for the tests after.
 */
object TestVm {
    val shared: ScannerViewModel by lazy {
        lateinit var vm: ScannerViewModel
        InstrumentationRegistry.getInstrumentation().runOnMainSync { vm = ScannerViewModel(ApplicationProvider.getApplicationContext<Application>()) }
        vm
    }
}
