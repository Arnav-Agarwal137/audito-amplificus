package com.arnavagarwal.sound.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * A drawn picture of the maker's battery switch: three small screens, the row to tap lit up on each, a tap mark
 * on it, and an arrow to the next. Drawn by the app rather than copied off the internet: no one else's image,
 * and it follows light / dark like everything else.
 */
@Composable
fun BatteryPicture() {
    val a = LocalAurora.current
    Row(
        Modifier.fillMaxWidth().semantics { contentDescription = Setup.MAKER_SWITCH_PATH.joinToString(", then ") },
        horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top, // tops level, whatever the caption length
    ) {
        Setup.MAKER_SWITCH_SCREENS.forEachIndexed { i, screen ->
            if (i > 0) Text("\u2192", color = a.dim, fontSize = 18.sp, modifier = Modifier.padding(top = 52.dp))
            Column(Modifier.width(92.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Canvas(Modifier.width(78.dp).height(128.dp)) {
                    val w = size.width; val r = CornerRadius(10.dp.toPx())
                    drawRoundRect(a.glass, Offset.Zero, size, r)
                    drawRoundRect(a.edge, Offset.Zero, size, r, style = Stroke(1.5.dp.toPx()))
                    // rows of a settings list; the one to tap is lit
                    val rowH = 13.dp.toPx(); val gap = 5.dp.toPx(); val pad = 8.dp.toPx()
                    for (k in 0 until 6) {
                        val y = 16.dp.toPx() + k * (rowH + gap)
                        val lit = k == screen.litRow
                        drawRoundRect(if (lit) a.good.copy(alpha = .35f) else a.edge.copy(alpha = .6f),
                            Offset(pad, y), Size(w - 2 * pad, rowH), CornerRadius(4.dp.toPx()))
                        if (lit && screen.switchOn) { // the "Allow" switch, turned on
                            val sw = 18.dp.toPx(); val sh = 9.dp.toPx(); val sx = w - pad - sw - 3.dp.toPx(); val sy = y + (rowH - sh) / 2
                            drawRoundRect(a.good, Offset(sx, sy), Size(sw, sh), CornerRadius(sh / 2))
                            drawCircle(a.bg, sh / 2 - 1.dp.toPx(), Offset(sx + sw - sh / 2, sy + sh / 2))
                        }
                        if (lit) drawCircle(a.ink.copy(alpha = .8f), 7.dp.toPx(), Offset(w * .38f, y + rowH / 2), style = Stroke(2.dp.toPx()))
                    }
                }
                Text(screen.caption, color = a.ink, fontSize = 11.sp, lineHeight = 14.sp, textAlign = TextAlign.Center)
            }
        }
    }
}
