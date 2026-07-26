package com.wmc.mediacenter.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.wmc.mediacenter.RowUiState
import com.wmc.mediacenter.ui.theme.WmcAccentCyan
import com.wmc.mediacenter.ui.theme.WmcTextPrimary
import com.wmc.mediacenter.ui.theme.WmcTileSurface

/**
 * List of the user's rows: OK opens a row to manage its apps; long-press
 * OK opens Rename / Move up / Move down / Delete. "+ Add row" is always
 * last. Every change persists instantly (handled by the ViewModel).
 */
@Composable
fun EditRowsScreen(
    rows: List<RowUiState>,
    onOpenRow: (rowId: String) -> Unit,
    onLongPressRow: (rowId: String, rowName: String, index: Int, rowCount: Int) -> Unit,
    onAddRow: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .wmcBackground()
            .padding(top = 56.dp, start = 48.dp, end = 48.dp)
    ) {
        Text(text = "Edit Rows", modifier = Modifier.padding(bottom = 20.dp))

        LazyColumn(modifier = Modifier.fillMaxWidth()) {
            items(rows, key = { it.id }) { row ->
                EditRowsEntry(
                    label = "${row.name}  ·  ${row.apps.size} app${if (row.apps.size == 1) "" else "s"}",
                    onClick = { onOpenRow(row.id) },
                    onLongClick = {
                        val index = rows.indexOfFirst { it.id == row.id }
                        onLongPressRow(row.id, row.name, index, rows.size)
                    }
                )
            }
            item {
                EditRowsEntry(label = "+ Add row", onClick = onAddRow, onLongClick = null)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EditRowsEntry(label: String, onClick: () -> Unit, onLongClick: (() -> Unit)?) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (isFocused) WmcTileSurface else Color.Transparent)
            .then(
                if (isFocused) Modifier.border(2.dp, WmcAccentCyan, RoundedCornerShape(8.dp)) else Modifier
            )
            .combinedClickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
                onLongClick = onLongClick
            )
            .padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        Text(
            text = label,
            color = if (isFocused) WmcAccentCyan else WmcTextPrimary,
            fontSize = 16.sp
        )
    }
}
