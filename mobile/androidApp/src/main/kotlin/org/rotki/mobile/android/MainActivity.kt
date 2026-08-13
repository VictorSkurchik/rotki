package org.rotki.mobile.android

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.fragment.app.FragmentActivity
import org.rotki.mobile.android.security.AndroidBiometricPromptCopy
import org.rotki.mobile.core.ports.ApplicationVisibilityState

class MainActivity : FragmentActivity() {
    private lateinit var securityComposition: AndroidSecurityComposition

    override fun onCreate(savedInstanceState: Bundle?): Unit {
        super.onCreate(savedInstanceState)
        securityComposition = AndroidSecurityComposition.get(applicationContext)
        securityComposition.attachActivity(
            activity = this,
            promptCopy = AndroidBiometricPromptCopy(
                title = getString(R.string.biometric_snapshot_title),
                subtitle = getString(R.string.biometric_snapshot_subtitle),
                cancel = getString(R.string.biometric_cancel),
            ),
        )
        setContent {
            val status = securityComposition.facade.status.collectAsState().value
            val visibility = securityComposition.visibility.state.collectAsState().value
            val privacyCovered =
                visibility == ApplicationVisibilityState.BACKGROUND_OR_LOCKED
            RotkiCompanionApp(
                stateCode = status.rootState.code,
                privacyCovered = privacyCovered,
            )
        }
    }

    override fun onResume(): Unit {
        super.onResume()
        securityComposition.lifecycleController.onResume()
    }

    override fun onPause(): Unit {
        securityComposition.lifecycleController.onPause()
        super.onPause()
    }

    override fun onStop(): Unit {
        if (!isChangingConfigurations) {
            securityComposition.lifecycleController.onBackgroundOrSystemLock()
        }
        super.onStop()
    }

    override fun onDestroy(): Unit {
        if (::securityComposition.isInitialized) {
            securityComposition.detachActivity(this)
        }
        super.onDestroy()
    }
}

@Composable
private fun RotkiCompanionApp(
    stateCode: String,
    privacyCovered: Boolean,
): Unit {
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = "Rotki Companion",
                    style = MaterialTheme.typography.headlineMedium,
                )
                Text(
                    text = if (privacyCovered) "Locked" else "State: $stateCode",
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun RotkiCompanionAppPreview(): Unit = RotkiCompanionApp(
    stateCode = "unpaired",
    privacyCovered = false,
)
