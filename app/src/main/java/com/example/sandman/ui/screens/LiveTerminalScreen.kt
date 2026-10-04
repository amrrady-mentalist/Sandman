package com.example.sandman.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.sandman.inspector.VirtualLogBus
import com.example.sandman.model.HookCategory
import com.example.sandman.model.HookLogEntry
import com.example.sandman.ui.theme.*

@Composable
fun LiveTerminalScreen() {
    val logs by VirtualLogBus.logsFlow.collectAsState()
    var selectedCategory by remember { mutableStateOf<HookCategory?>(null) }
    var selectedEntryForInspection by remember { mutableStateOf<HookLogEntry?>(null) }
    var searchQuery by remember { mutableStateOf("") }

    val filteredLogs = remember(logs, selectedCategory, searchQuery) {
        logs.filter { entry ->
            val matchesCategory = selectedCategory == null || entry.category == selectedCategory
            val matchesSearch = searchQuery.isBlank() ||
                    entry.method.contains(searchQuery, ignoreCase = true) ||
                    entry.targetClass.contains(searchQuery, ignoreCase = true) ||
                    entry.spoofedResult.contains(searchQuery, ignoreCase = true)
            matchesCategory && matchesSearch
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            .padding(top = 16.dp)
    ) {
        // Top Terminal Bar
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(NeonGreen)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "AOSP INTERCEPTOR STREAM",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Black,
                letterSpacing = 1.sp,
                color = Color.White
            )
            Spacer(modifier = Modifier.weight(1f))

            FilledTonalIconButton(
                onClick = { VirtualLogBus.clear() },
                modifier = Modifier.size(36.dp),
                shape = RoundedCornerShape(8.dp)
            ) {
                Icon(Icons.Default.DeleteSweep, contentDescription = "Clear Logs", tint = NeonPink, modifier = Modifier.size(18.dp))
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Search Bar
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = { Text("Filter intercepted calls...", fontSize = 12.sp) },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp)) },
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { searchQuery = "" }) {
                        Icon(Icons.Default.Clear, contentDescription = "Clear", modifier = Modifier.size(16.dp))
                    }
                }
            },
            modifier = Modifier.fillMaxWidth().height(50.dp),
            singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = CyberSurfaceVariant,
                unfocusedContainerColor = CyberSurfaceVariant,
                focusedBorderColor = NeonCyan,
                unfocusedBorderColor = CyberBorder
            ),
            shape = RoundedCornerShape(10.dp)
        )

        Spacer(modifier = Modifier.height(10.dp))

        // Category Filter Chips
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            item {
                CategoryChip(
                    title = "ALL (${logs.size})",
                    selected = selectedCategory == null,
                    color = NeonCyan,
                    onClick = { selectedCategory = null }
                )
            }
            items(HookCategory.values()) { cat ->
                val count = logs.count { it.category == cat }
                val color = getCategoryColor(cat)
                CategoryChip(
                    title = "${cat.name} ($count)",
                    selected = selectedCategory == cat,
                    color = color,
                    onClick = { selectedCategory = if (selectedCategory == cat) null else cat }
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Logs terminal list
        if (filteredLogs.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = 80.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.Terminal, contentDescription = null, tint = Color(0xFF415A77), modifier = Modifier.size(48.dp))
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("No intercepted binder events matching filter", color = Color(0xFF64748B), fontSize = 13.sp)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("Launch a sandboxed app or probe telemetry to capture calls", color = Color(0xFF415A77), fontSize = 11.sp)
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 96.dp)
            ) {
                items(filteredLogs, key = { it.id }) { entry ->
                    LogEntryCard(
                        entry = entry,
                        onClick = { selectedEntryForInspection = entry }
                    )
                }
            }
        }
    }

    // Detail Modal
    if (selectedEntryForInspection != null) {
        val entry = selectedEntryForInspection!!
        val catColor = getCategoryColor(entry.category)
        AlertDialog(
            onDismissRequest = { selectedEntryForInspection = null },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("[${entry.category.name}]", color = catColor, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(entry.method, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Timestamp: ${entry.formattedTime}", fontSize = 11.sp, color = Color.Gray)
                    Text("Target Interface / Class:", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    Text(entry.targetClass, fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = NeonCyan)

                    Text("Original Intercepted Request:", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(CyberNavyBg, RoundedCornerShape(6.dp))
                            .padding(8.dp)
                    ) {
                        Text(entry.interceptedPayload, fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = Color.LightGray)
                    }

                    Text("Spoofed Sandbox Response:", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(CyberNavyBg, RoundedCornerShape(6.dp))
                            .padding(8.dp)
                    ) {
                        Text(entry.spoofedResult, fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = catColor)
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = { selectedEntryForInspection = null },
                    colors = ButtonDefaults.buttonColors(containerColor = NeonCyan)
                ) {
                    Text("Close", color = Color(0xFF080F1A))
                }
            },
            containerColor = Color(0xFF142438),
            shape = RoundedCornerShape(16.dp)
        )
    }
}

@Composable
fun LogEntryCard(
    entry: HookLogEntry,
    onClick: () -> Unit
) {
    val tagColor = getCategoryColor(entry.category)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(CyberSurfaceVariant)
            .border(1.dp, CyberBorder, RoundedCornerShape(10.dp))
            .clickable { onClick() }
            .padding(12.dp)
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    color = tagColor.copy(alpha = 0.2f),
                    shape = RoundedCornerShape(4.dp)
                ) {
                    Text(
                        text = entry.category.name,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        color = tagColor,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                Text(
                    text = entry.method,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    color = Color.White,
                    modifier = Modifier.weight(1f)
                )

                Text(
                    text = entry.formattedTime,
                    fontSize = 9.sp,
                    color = Color(0xFF64748B)
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = "Target: ${entry.targetClass}",
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                color = Color(0xFF94A3B8)
            )

            Spacer(modifier = Modifier.height(2.dp))

            Text(
                text = "-> ${entry.spoofedResult}",
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                color = tagColor
            )
        }
    }
}

@Composable
fun CategoryChip(
    title: String,
    selected: Boolean,
    color: Color,
    onClick: () -> Unit
) {
    Surface(
        color = if (selected) color.copy(alpha = 0.25f) else Color(0xFF0D1B2A),
        shape = RoundedCornerShape(8.dp),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (selected) color else CyberBorder
        ),
        modifier = Modifier.clickable { onClick() }
    ) {
        Text(
            text = title,
            fontSize = 10.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = if (selected) color else Color(0xFF94A3B8),
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}

fun getCategoryColor(category: HookCategory): Color {
    return when (category) {
        HookCategory.LOCATION -> NeonCyan
        HookCategory.TIME -> NeonGreen
        HookCategory.TELEPHONY -> NeonAmber
        HookCategory.STORAGE -> Color(0xFF48CAE4)
        HookCategory.CLASSLOADER -> NeonPurple
        HookCategory.LIFECYCLE -> NeonPink
        HookCategory.AOSP_BINDER -> Color(0xFF7209B7)
    }
}
