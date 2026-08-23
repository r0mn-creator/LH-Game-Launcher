package org.lighthouse.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.lighthouse.theme.LocalTheme

/**
 * Typing on a handheld.
 *
 * A pad cannot enter free text, so the few fields that need it open this and
 * let the on-screen keyboard do the work. Keeping text entry in one overlay is
 * what allows every other screen to stay pad-only.
 */
@Composable
fun TextPromptOverlay(
    title: String,
    hint: String?,
    initial: String,
    onDone: (String) -> Unit,
    onCancel: () -> Unit,
) {
    val theme = LocalTheme.current
    var value by remember(initial) { mutableStateOf(initial) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.72f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .fillMaxWidth(0.7f)
                .clip(RoundedCornerShape(14.dp))
                .background(theme.surface)
                .padding(24.dp)
        ) {
            Text(title, color = theme.textPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            hint?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, color = theme.textSecondary, fontSize = 12.sp)
            }
            Spacer(Modifier.height(14.dp))
            BasicTextField(
                value = value,
                onValueChange = { value = it },
                singleLine = true,
                textStyle = TextStyle(color = theme.textPrimary, fontSize = 16.sp,
                    fontFamily = FontFamily.Monospace),
                cursorBrush = SolidColor(theme.primary),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focus)
                    .clip(RoundedCornerShape(8.dp))
                    .background(theme.surfaceVariant)
                    .padding(horizontal = 12.dp, vertical = 12.dp),
            )
            Spacer(Modifier.height(18.dp))
            Row {
                PromptButton("Save") { onDone(value.trim()) }
                Spacer(Modifier.width(12.dp))
                PromptButton("Clear") { value = "" }
                Spacer(Modifier.weight(1f))
                PromptButton("Cancel", onCancel)
            }
        }
    }
}

@Composable
private fun PromptButton(label: String, onClick: () -> Unit) {
    val theme = LocalTheme.current
    Box(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, theme.primary.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Text(label, color = theme.primary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
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
