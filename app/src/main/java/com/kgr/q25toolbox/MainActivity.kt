package com.kgr.q25toolbox

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.lifecycleScope
import com.kgr.q25toolbox.core.RootShell
import com.kgr.q25toolbox.ui.HomeScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Full Material You (Monet) theming: on Android 12+ the palette is derived from
 * the system wallpaper, following the system light/dark setting. Older versions
 * fall back to the stock Material 3 light/dark baseline schemes.
 */
@Composable
private fun appColorScheme(): ColorScheme {
    val context = LocalContext.current
    val dark = isSystemInDarkTheme()
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else {
        if (dark) darkColorScheme() else lightColorScheme()
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // IMPORTANT: RootShell must be the first thing to touch libsu's Shell
        // class, since RootShell's init block calls Shell.setDefaultBuilder().
        // If anything calls Shell.getShell()/Shell.cmd() first, setDefaultBuilder()
        // throws (libsu requires it to run before any shell is created), which
        // fails RootShell's <clinit> and poisons every later reference to it
        // with NoClassDefFoundError - which is exactly the crash we just saw.
        //
        // Run on a background thread since this blocks on the root grant
        // prompt (FolkPatch/APatch manager) on first launch.
        lifecycleScope.launch(Dispatchers.IO) {
            RootShell.isRootAvailable()
        }

        setContent {
            MaterialTheme(colorScheme = appColorScheme()) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    HomeScreen()
                }
            }
        }
    }
}
