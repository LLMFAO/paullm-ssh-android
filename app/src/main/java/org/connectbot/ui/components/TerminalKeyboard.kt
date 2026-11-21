/*
 * ConnectBot: simple, powerful, open-source SSH client for Android
 * Copyright 2025-2026 Kenny Root
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.connectbot.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import org.connectbot.data.keyboard.KeyboardDefaults
import org.connectbot.data.keyboard.KeyboardLayout
import org.connectbot.service.TerminalBridge
import org.connectbot.ui.theme.ConnectBotTheme
import org.connectbot.util.TerminalKeyModifiers

/** Configured terminal input surface. Its parent owns visibility and the auto-hide timer. */
@Composable
fun TerminalKeyboard(
    bridge: TerminalBridge,
    onInteraction: () -> Unit,
    modifier: Modifier = Modifier,
    onHideIme: () -> Unit = {},
    onShowIme: () -> Unit = {},
    onOpenTextInput: () -> Unit = {},
    onPasteRequest: () -> Unit = {},
    onScrollInProgressChange: (Boolean) -> Unit = {},
    imeVisible: Boolean = false,
    playAnimation: Boolean = false,
    showImeToggleKey: Boolean = true,
    isComposeModeActive: Boolean = false,
    onToggleComposeMode: () -> Unit = {},
    onShortcutModifierChange: () -> Unit = {},
) {
    Column(modifier = modifier) {
        ShiftArrowKeys(bridge = bridge, onInteraction = onInteraction)
        ConfigurableTerminalKeyboard(
            bridge = bridge, onInteraction = onInteraction,
            onHideIme = onHideIme, onShowIme = onShowIme, onOpenTextInput = onOpenTextInput, onPasteRequest = onPasteRequest,
            onScrollInProgressChange = onScrollInProgressChange, imeVisible = imeVisible,
            playAnimation = playAnimation, showImeToggleKey = showImeToggleKey,
            isComposeModeActive = isComposeModeActive, onToggleComposeMode = onToggleComposeMode,
            onShortcutModifierChange = onShortcutModifierChange,
        )
    }
}

@Composable
private fun ShiftArrowKeys(bridge: TerminalBridge, onInteraction: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        listOf("arrow_left" to "⇧←", "arrow_right" to "⇧→").forEach { (target, label) ->
            Surface(
                onClick = {
                    onInteraction()
                    bridge.sendModifiedKey(target, TerminalKeyModifiers.SHIFT)
                    bridge.tryKeyVibrate()
                },
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.secondaryContainer,
            ) {
                Text(
                    text = label,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelLarge,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

@Preview
@Composable
private fun TerminalKeyboardPreview() {
    val layout = KeyboardLayout(id = KeyboardDefaults.layoutId, name = "Classic CB")
    ConnectBotTheme {
        KeyboardGrid(layout = layout, items = KeyboardDefaults.items(layout.id), macros = emptyList(), onPress = {})
    }
}
