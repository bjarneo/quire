package app.quire.notes

import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import app.quire.notes.state.QuireViewModel
import app.quire.notes.ui.QuireApp

class MainActivity : ComponentActivity() {
    private val vm: QuireViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) window.isNavigationBarContrastEnforced = false
        setContent { QuireApp(vm) }
    }

    override fun onResume() {
        super.onResume()
        vm.onResume()
    }

    override fun onPause() {
        // Save unsaved edits as soon as Quire leaves the screen.
        vm.onPause()
        super.onPause()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean = vm.onKey(event) || super.dispatchKeyEvent(event)
}
