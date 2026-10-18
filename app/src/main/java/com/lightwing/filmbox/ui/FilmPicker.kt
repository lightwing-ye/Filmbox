package com.lightwing.filmbox.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import filmmeter.core.DataTier
import filmmeter.core.Film
import filmmeter.core.FilmCatalog

@Composable
fun FilmPicker(current: Film, onDismiss: () -> Unit, onPick: (Film) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
        title = { Text("选择胶片") },
        text = {
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                items(FilmCatalog.films.groupBy { it.brand }.toList()) { (brand, list) ->
                    Column {
                        Text(
                            brand, color = Color(0xFF8A8F98), fontSize = 11.sp,
                            modifier = Modifier.padding(top = 10.dp, bottom = 4.dp),
                        )
                        list.forEach { film ->
                            Row(
                                Modifier.fillMaxWidth()
                                    .clickable { onPick(film) }
                                    .padding(vertical = 8.dp),
                            ) {
                                Text(
                                    film.name,
                                    color = if (film.id == current.id) Color(0xFFE8B44A) else Color(0xFFF2F3F5),
                                    fontWeight = if (film.id == current.id) FontWeight.Medium else FontWeight.Normal,
                                    modifier = Modifier.weight(1f),
                                )
                                Text(
                                    "ISO ${film.iso} · " + tierLabel(film.tier),
                                    color = Color(0xFF8A8F98), fontSize = 11.sp,
                                )
                            }
                        }
                    }
                }
            }
        },
    )
}

private fun tierLabel(tier: DataTier) = when (tier) {
    DataTier.MANUFACTURER -> "厂商"
    DataTier.COMMUNITY -> "社区"
    DataTier.APPROX -> "近似"
}
