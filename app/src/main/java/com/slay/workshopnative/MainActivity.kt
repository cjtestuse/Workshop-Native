package com.slay.workshopnative

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.slay.workshopnative.ui.WorkshopNativeRoot
import com.slay.workshopnative.ui.theme.WorkshopNativeTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            WorkshopNativeTheme {
                WorkshopNativeRoot()
            }
        }
    }
}
