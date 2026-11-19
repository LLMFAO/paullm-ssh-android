package org.connectbot.paullm

import android.app.Activity
import android.content.Context
import android.view.WindowManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import org.connectbot.util.PreferenceConstants

object PaullmPrivacy {
    const val PREF_PRIVACY_MODE = "paullm_privacy_mode"

    fun isPrivacyModeEnabled(context: Context): Boolean =
        PreferenceManager.getDefaultSharedPreferences(context).getBoolean(PREF_PRIVACY_MODE, false)

    fun setPrivacyMode(activity: Activity?, enabled: Boolean) {
        activity ?: return
        PreferenceManager.getDefaultSharedPreferences(activity).edit {
            putBoolean(PREF_PRIVACY_MODE, enabled)
        }
        applyWindowPrivacy(activity, enabled)
    }

    fun applyWindowPrivacy(activity: Activity, enabled: Boolean = isPrivacyModeEnabled(activity)) {
        if (enabled) {
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }
}

@Composable
fun PaullmSecurityDialog(
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val prefs = remember { PreferenceManager.getDefaultSharedPreferences(context) }
    var privacyEnabled by remember { mutableStateOf(PaullmPrivacy.isPrivacyModeEnabled(context)) }
    val appLockEnabled = remember { prefs.getBoolean(PreferenceConstants.AUTH_ON_LAUNCH, false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("paullmSSH Privacy & Lock") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Privacy Mode (FLAG_SECURE)", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "Blocks screenshots and blurs app switcher previews.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = privacyEnabled,
                        onCheckedChange = { checked ->
                            privacyEnabled = checked
                            PaullmPrivacy.setPrivacyMode(activity, checked)
                        },
                    )
                }

                Column {
                    Text("Biometric App Lock", style = MaterialTheme.typography.titleSmall)
                    Text(
                        if (appLockEnabled) {
                            "Enabled — requires biometrics when opening paullmSSH."
                        } else {
                            "Disabled — enable Authenticate on Launch inside Settings."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onDismiss()
                    onOpenSettings()
                },
            ) {
                Text("Open Settings")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Done")
            }
        },
    )
}
