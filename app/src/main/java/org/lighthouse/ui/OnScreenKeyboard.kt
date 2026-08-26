// Copyright 2026 r0mn-creator
// SPDX-License-Identifier: Apache-2.0

package org.lighthouse.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.lighthouse.theme.LocalTheme

/**
 * An on-screen keyboard a d-pad can drive.
 *
 * The system IME is a touch keyboard: nothing in it responds to a d-pad, so
 * relying on it broke the one promise the rest of the launcher keeps - that
 * everything works from a controller on the sofa with the device docked to a
 * TV. Every key here is a row/column position a cursor can reach, the same
 * explicit-index model every other list in this app already uses.
 */
sealed interface Key {
    data class Letter(val lower: Char) : Key
    data object Shift : Key
    data object Backspace : Key
    data object Space : Key
    data object Symbols : Key
    data object Done : Key
}

/** Rows are independent lengths on purpose - a keyboard is not a uniform grid. */
private val LETTER_ROWS: List<List<Key>> = listOf(
    "qwertyuiop".map { Key.Letter(it) },
    "asdfghjkl".map { Key.Letter(it) },
    listOf(Key.Shift) + "zxcvbnm".map { Key.Letter(it) } + listOf(Key.Backspace),
    listOf(Key.Symbols, Key.Space, Key.Done),
)

private val SYMBOL_ROWS: List<List<Key>> = listOf(
    "1234567890".map { Key.Letter(it) },
    "-:;'\"(),.!".map { Key.Letter(it) },
    listOf(Key.Shift) + "&_/@#\$%*+?".map { Key.Letter(it) } + listOf(Key.Backspace),
    listOf(Key.Symbols, Key.Space, Key.Done),
)

fun keyboardRows(symbols: Boolean): List<List<Key>> = if (symbols) SYMBOL_ROWS else LETTER_ROWS

/** What pressing a key does to the buffer. Pure, so it is trivial to test. */
fun applyKey(key: Key, text: String, shift: Boolean): String = when (key) {
    is Key.Letter -> text + if (shift) key.lower.uppercaseChar() else key.lower
    Key.Space -> text + " "
    Key.Backspace -> text.dropLast(1)
    Key.Shift, Key.Symbols, Key.Done -> text
}

@Composable
fun KeyboardPanel(
    symbols: Boolean,
    shift: Boolean,
    cursorRow: Int,
    cursorCol: Int,
    onTap: (row: Int, col: Int) -> Unit,
) {
    val rows = keyboardRows(symbols)
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        rows.forEachIndexed { r, row ->
            Row(
                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                row.forEachIndexed { c, key ->
                    KeyCap(
                        key = key,
                        shift = shift,
                        symbols = symbols,
                        focused = r == cursorRow && c == cursorCol,
                        weight = weightOf(key),
                        onClick = { onTap(r, c) },
                    )
                }
            }
        }
    }
}

private fun weightOf(key: Key): Float = when (key) {
    Key.Space -> 5f
    Key.Done, Key.Symbols -> 2f
    Key.Shift, Key.Backspace -> 1.5f
    else -> 1f
}

private fun labelOf(key: Key, shift: Boolean, symbols: Boolean): String = when (key) {
    is Key.Letter -> (if (shift) key.lower.uppercaseChar() else key.lower).toString()
    Key.Shift -> "⇧"
    Key.Backspace -> "⌫"
    Key.Space -> "space"
    Key.Symbols -> if (symbols) "ABC" else "123"
    Key.Done -> "Done"
}

/** A RowScope extension so `Modifier.weight` - which only exists on that
 *  receiver - can size each key relative to its neighbours in the same row. */
@Composable
private fun RowScope.KeyCap(
    key: Key,
    shift: Boolean,
    symbols: Boolean,
    focused: Boolean,
    weight: Float,
    onClick: () -> Unit,
) {
    val theme = LocalTheme.current
    val active = key == Key.Shift && shift
    Box(
        Modifier
            .weight(weight)
            .height(46.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(
                when {
                    focused -> theme.primary.copy(alpha = 0.85f)
                    active -> theme.primary.copy(alpha = 0.35f)
                    key == Key.Done -> theme.primary.copy(alpha = 0.18f)
                    else -> theme.surfaceVariant
                }
            )
            .then(
                if (focused) Modifier.border(2.dp, theme.primary, RoundedCornerShape(8.dp))
                else Modifier
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            labelOf(key, shift, symbols),
            color = if (focused) theme.background else theme.textPrimary,
            fontSize = if (key is Key.Letter) 17.sp else 13.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}
