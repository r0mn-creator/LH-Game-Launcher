// Copyright 2026 r0mn-creator
// SPDX-License-Identifier: Apache-2.0

package org.lighthouse.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.lighthouse.theme.LocalTheme

/**
 * Typing on a handheld, from a controller.
 *
 * The system IME is a touch keyboard - nothing in it answers to a d-pad - so
 * relying on it for these few fields would have been the one place the "works
 * from the sofa, docked to a TV" promise broke. The keyboard here is drawn by
 * us and driven by the same explicit cursor every other screen uses, docked to
 * the bottom of the panel with the field it is filling anchored directly above
 * it, rather than a text box floating somewhere else on screen with the
 * keyboard appearing separately below.
 */
@Composable
fun TextPromptOverlay(
    title: String,
    hint: String?,
    text: String,
    symbols: Boolean,
    shift: Boolean,
    cursorRow: Int,
    cursorCol: Int,
    onKeyTap: (row: Int, col: Int) -> Unit,
    onCancel: () -> Unit,
) {
    val theme = LocalTheme.current

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.72f)),
    ) {
        // Anchored to the bottom, not centred: this is meant to read as a
        // keyboard panel sliding up from the edge of the screen, the same
        // shape a console's own on-screen keyboard takes, with the field it
        // fills pushed up to sit right above the keys as they appear.
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                .background(theme.surface)
        ) {
            Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 6.dp)) {
                Text(title, color = theme.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                hint?.let {
                    Spacer(Modifier.height(4.dp))
                    Text(it, color = theme.textSecondary, fontSize = 12.sp)
                }
                Spacer(Modifier.height(10.dp))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(theme.surfaceVariant)
                        .padding(horizontal = 12.dp, vertical = 12.dp),
                ) {
                    Text(
                        text.ifEmpty { " " },
                        color = theme.textPrimary, fontSize = 16.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.weight(1f),
                    )
                    // A static caret rather than a blinking one: this is not a
                    // real text field with IME focus, and pretending it is
                    // would invite tapping it expecting the system keyboard.
                    Text("│", color = theme.primary, fontSize = 16.sp, fontFamily = FontFamily.Monospace)
                }
            }
            KeyboardPanel(
                symbols = symbols,
                shift = shift,
                cursorRow = cursorRow,
                cursorCol = cursorCol,
                onTap = onKeyTap,
            )
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.25f))
                    .padding(horizontal = 20.dp, vertical = 12.dp),
            ) {
                PadHint("B", "Cancel", onCancel)
                Spacer(Modifier.weight(1f))
                Text("D-pad to move · A to press a key", color = theme.textSecondary, fontSize = 12.sp)
            }
        }
    }
}

/**
 * The Android-shelf app picker, pad-navigable.
 *
 * Same explicit-cursor model as every other list, so it is driven with the
 * d-pad and A rather than being the one screen that needs a finger.
 */
@Composable
fun AppPickerScreen(
    apps: List<AppChoice>,
    cursor: Int,
    onSelect: (Int) -> Unit,
    onToggle: (String, Boolean) -> Unit,
    onClose: () -> Unit,
) {
    val theme = LocalTheme.current
    val state = rememberLazyListState()
    LaunchedEffect(cursor) { state.animateScrollToItem((cursor - 3).coerceAtLeast(0)) }

    Column(
        Modifier
            .fillMaxSize()
            .background(theme.background)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = 30.dp, end = 30.dp, top = 22.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Choose apps", color = theme.textPrimary, fontSize = 26.sp,
                fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(14.dp))
            Text("${apps.count { it.chosen }} on the shelf",
                color = theme.textSecondary, fontSize = 13.sp)
        }
        LazyColumn(
            state = state,
            contentPadding = PaddingValues(start = 30.dp, end = 30.dp, bottom = 20.dp),
            modifier = Modifier.weight(1f),
        ) {
            itemsIndexed(apps) { i, a ->
                val focused = i == cursor
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 3.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(
                            if (focused) theme.primary.copy(alpha = 0.16f) else Color.Transparent
                        )
                        .then(
                            if (focused) Modifier.border(1.dp, theme.primary.copy(alpha = 0.75f),
                                RoundedCornerShape(10.dp)) else Modifier
                        )
                        .clickable { onSelect(i); onToggle(a.pkg, !a.chosen) }
                        .padding(horizontal = 14.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(a.label, color = theme.textPrimary, fontSize = 15.sp)
                        Text(a.pkg, color = theme.textSecondary, fontSize = 11.sp)
                    }
                    if (a.chosen) {
                        Text("on the shelf", color = theme.accent, fontSize = 12.sp,
                            fontWeight = FontWeight.Medium)
                    }
                }
            }
        }
        Row(
            Modifier
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.55f))
                .padding(horizontal = 30.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PadHint("B", "Done", onClose)
            Spacer(Modifier.weight(1f))
            val a = apps.getOrNull(cursor)
            PadHint(
                "A",
                if (a == null) "—" else if (a.chosen) "Remove from shelf" else "Add to shelf",
                { a?.let { onToggle(it.pkg, !it.chosen) } },
                dim = a == null,
            )
        }
    }
}
