package com.cosmo.butler

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import com.cosmo.butler.ui.theme.CosmoTheme
import com.cosmo.butler.ui.theme.NormalEmerald

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            CosmoTheme {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(com.cosmo.butler.ui.theme.JarvisBackground),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "SYSTEM ONLINE, SIR.",
                        color = NormalEmerald,
                        fontSize = 20.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
    }
}
