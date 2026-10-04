package com.retrovika.app

import android.content.Intent
import android.os.Bundle
import com.retrovika.app.ui.share.toIncoming
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.retrovika.app.core.settings.Languages
import com.retrovika.app.ui.navigation.RetrovikaNavHost
import com.retrovika.app.ui.theme.RetrovikaTheme

class MainActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: android.content.Context) = super.attachBaseContext(Languages.wrap(newBase))

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        // Só na primeira criação: girar a tela recria a Activity com o mesmo Intent, e o estado seria recebido de novo.
        if (savedInstanceState == null) intent?.toIncoming()?.let { container.incoming.value = it }
        setContent { RetrovikaTheme { RetrovikaNavHost() } }
    }

    override fun onStart() {
        super.onStart()
        container.setUiInBackground(false)
    }

    // Também quando um jogo abre por cima (o GameActivity tem a própria tarefa): a interface fica escondida.
    override fun onStop() {
        super.onStop()
        container.setUiInBackground(true)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.toIncoming()?.let { container.incoming.value = it }
    }
}
