package com.example.sulu_read.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp

@Composable
fun SyllableChip(
    text: String,
    selected: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        color = if (selected) Color.White else MaterialTheme.colorScheme.onSurface,
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primaryContainer)
            .alpha(if (enabled) 1f else 0.42f)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 12.dp)
    )
}
