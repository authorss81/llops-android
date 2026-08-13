package com.authorss81.noteflow.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.authorss81.noteflow.data.model.NotePageEntity
import com.authorss81.noteflow.ui.viewmodel.NoteflowViewModel
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

@Composable
fun CalendarView(
    pages: List<NotePageEntity>,
    viewModel: NoteflowViewModel,
    onOpenPage: (NotePageEntity) -> Unit,
    modifier: Modifier = Modifier
) {
    val scheme = MaterialTheme.colorScheme
    var currentCalendar by remember { mutableStateOf(Calendar.getInstance()) }
    var selectedDateKey by remember { mutableStateOf(getTodayDateKey()) }

    // Map date string "YYYY-MM-DD" -> list of pages created/updated on that day
    val pagesByDate = remember(pages) {
        val map = mutableMapOf<String, MutableList<NotePageEntity>>()
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        for (page in pages) {
            val dateKey = sdf.format(Date(page.updatedAt))
            map.getOrPut(dateKey) { mutableListOf() }.add(page)
        }
        map
    }

    val currentMonthFormat = remember { SimpleDateFormat("MMMM yyyy", Locale.getDefault()) }

    Column(modifier = modifier.fillMaxSize().padding(12.dp)) {
        // Calendar Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Event, contentDescription = null, tint = scheme.primary)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = currentMonthFormat.format(currentCalendar.time),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            }

            Row {
                IconButton(
                    onClick = {
                        val newCal = currentCalendar.clone() as Calendar
                        newCal.add(Calendar.MONTH, -1)
                        currentCalendar = newCal
                    }
                ) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Previous Month")
                }
                IconButton(
                    onClick = {
                        val newCal = currentCalendar.clone() as Calendar
                        newCal.add(Calendar.MONTH, 1)
                        currentCalendar = newCal
                    }
                ) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowForward, contentDescription = "Next Month")
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Days of week header
        val daysOfWeek = remember { listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat") }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
            daysOfWeek.forEach { day ->
                Text(
                    text = day,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = scheme.outline,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Month Grid Days
        val daysInMonth = remember(currentCalendar) {
            val cal = currentCalendar.clone() as Calendar
            cal.set(Calendar.DAY_OF_MONTH, 1)
            val firstDayOfWeek = cal.get(Calendar.DAY_OF_WEEK) - 1 // 0-based
            val maxDays = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
            val list = mutableListOf<String?>()
            for (i in 0 until firstDayOfWeek) {
                list.add(null)
            }
            val yearMonthSdf = SimpleDateFormat("yyyy-MM", Locale.getDefault())
            val ym = yearMonthSdf.format(cal.time)
            for (day in 1..maxDays) {
                val dayStr = if (day < 10) "0$day" else "$day"
                list.add("$ym-$dayStr")
            }
            list
        }

        LazyVerticalGrid(
            columns = GridCells.Fixed(7),
            modifier = Modifier.height(260.dp)
        ) {
            items(daysInMonth) { dateKey ->
                if (dateKey == null) {
                    Box(modifier = Modifier.padding(4.dp).height(36.dp))
                } else {
                    val pageCount = pagesByDate[dateKey]?.size ?: 0
                    val isSelected = dateKey == selectedDateKey
                    val isToday = dateKey == getTodayDateKey()
                    val dayNum = dateKey.split("-").last().toInt().toString()

                    Surface(
                        onClick = { selectedDateKey = dateKey },
                        shape = RoundedCornerShape(8.dp),
                        color = when {
                            isSelected -> scheme.primaryContainer
                            isToday -> scheme.surfaceVariant
                            else -> Color.Transparent
                        },
                        modifier = Modifier.padding(2.dp).height(36.dp)
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Text(
                                text = dayNum,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (isToday || isSelected) FontWeight.Bold else FontWeight.Normal,
                                color = if (isSelected) scheme.onPrimaryContainer else scheme.onSurface
                            )
                            if (pageCount > 0) {
                                Box(
                                    modifier = Modifier
                                        .size(4.dp)
                                        .clip(CircleShape)
                                        .background(if (isSelected) scheme.primary else scheme.secondary)
                                )
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Selected Date Details & Pages List
        val selectedPages = pagesByDate[selectedDateKey] ?: emptyList()
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Notes for $selectedDateKey (${selectedPages.size})",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            Button(
                onClick = {
                    viewModel.addPage("Journal $selectedDateKey", tags = "calendar,#date:$selectedDateKey", onCreated = onOpenPage)
                },
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("New Note for Date")
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        if (selectedPages.isEmpty()) {
            Box(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No notes recorded on this date.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.outline
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(selectedPages) { page ->
                    Card(
                        modifier = Modifier.fillMaxWidth().clickable { onOpenPage(page) },
                        colors = CardDefaults.cardColors(containerColor = scheme.surfaceVariant.copy(alpha = 0.5f))
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                text = page.title,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold
                            )
                            if (!page.extractedText.isNullOrBlank()) {
                                Text(
                                    text = page.extractedText,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = scheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun getTodayDateKey(): String {
    val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    return sdf.format(Date())
}
