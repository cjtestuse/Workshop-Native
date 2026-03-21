package com.slay.workshopnative

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.slay.workshopnative.core.logging.MainActivityRuntimeTracker
import com.slay.workshopnative.ui.MainViewModel
import com.slay.workshopnative.ui.WorkshopNativeRoot
import com.slay.workshopnative.ui.theme.WorkshopNativeTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val instanceId: String = Integer.toHexString(System.identityHashCode(this))
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MainActivityRuntimeTracker.onCreated(
            instanceId = instanceId,
            taskId = taskId,
            intentFlags = intent?.flags,
            hadSavedState = savedInstanceState != null,
        )
        enableEdgeToEdge()
        setContent {
            val themeMode = viewModel.themeMode.collectAsStateWithLifecycle().value
            WorkshopNativeTheme(themeMode = themeMode) {
                WorkshopNativeRoot(viewModel = viewModel)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        MainActivityRuntimeTracker.onNewIntent(
            instanceId = instanceId,
            taskId = taskId,
            intentFlags = intent.flags,
        )
    }

    override fun onResume() {
        super.onResume()
        MainActivityRuntimeTracker.onResumed(instanceId = instanceId, taskId = taskId)
    }

    override fun onStop() {
        MainActivityRuntimeTracker.onStopped(instanceId = instanceId, taskId = taskId)
        super.onStop()
    }

    override fun onDestroy() {
        MainActivityRuntimeTracker.onDestroyed(
            instanceId = instanceId,
            taskId = taskId,
            changingConfigurations = isChangingConfigurations,
        )
        super.onDestroy()
    }
}
