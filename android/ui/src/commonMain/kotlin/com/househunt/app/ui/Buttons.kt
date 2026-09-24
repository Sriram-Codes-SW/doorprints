package com.househunt.app.ui

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow

/** The web's `--duration`: how long options and the action bar take to appear, disappear or resize. */
const val ANIMATION_MS = 150

/** A button label that wraps to two centred lines instead of being clipped (Tamil and Telugu at 200% font). */
@Composable
fun ButtonLabel(text: String) {
    Text(text, maxLines = BUTTON_LABEL_MAX_LINES, textAlign = TextAlign.Center, overflow = TextOverflow.Ellipsis)
}

/** How many lines a [ButtonLabel] wraps to before it is ellipsised; the ActionBar (in :app) stacks its buttons beyond it. */
const val BUTTON_LABEL_MAX_LINES = 2
