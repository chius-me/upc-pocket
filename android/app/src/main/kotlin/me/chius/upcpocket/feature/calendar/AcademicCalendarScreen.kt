package me.chius.upcpocket.feature.calendar

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AcademicCalendarScreen(
    uiState: CalendarUiState,
    onBack: () -> Unit,
    onLoadLatest: () -> Unit,
    onRefresh: () -> Unit,
    onSelectArticle: (CalendarArticleSummary) -> Unit,
) {
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        onLoadLatest()
    }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = { Text("官方校历") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = onRefresh, enabled = !uiState.isLoading) {
                        Icon(Icons.Rounded.Refresh, contentDescription = "刷新校历")
                    }
                    IconButton(
                        onClick = {
                            val target = uiState.selectedArticle?.pageUrl ?: CalendarProtocol.LIST_URL
                            runCatching {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(target)))
                            }.onFailure {
                                Toast.makeText(context, "未找到可用浏览器", Toast.LENGTH_SHORT).show()
                            }
                        },
                    ) {
                        Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = "在浏览器中打开")
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .consumeWindowInsets(innerPadding)
                .padding(top = innerPadding.calculateTopPadding()),
        ) {
            if (uiState.isLoading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = 8.dp,
                    bottom = innerPadding.calculateBottomPadding() + 24.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (uiState.articles.isNotEmpty()) {
                    item {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(items = uiState.articles, key = { it.pageUrl }) { article ->
                                FilterChip(
                                    selected = uiState.selectedArticle?.pageUrl == article.pageUrl,
                                    onClick = { onSelectArticle(article) },
                                    label = { Text(article.title) },
                                )
                            }
                        }
                    }
                }

                if (!uiState.errorMessage.isNullOrBlank()) {
                    item {
                        Surface(
                            shape = RoundedCornerShape(20.dp),
                            color = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(uiState.errorMessage, style = MaterialTheme.typography.bodyMedium)
                                Spacer(modifier = Modifier.height(8.dp))
                                Button(onClick = onRefresh) { Text("重试") }
                            }
                        }
                    }
                }

                items(items = uiState.imageUrls, key = { it }) { url ->
                    val localPath = uiState.cachedImagePaths[url]
                    if (localPath != null) {
                        ZoomableCalendarImageCard(filePath = localPath)
                    }
                }
            }
        }
    }
}

@Composable
private fun ZoomableCalendarImageCard(filePath: String) {
    val bitmapState by produceState<ImageBitmap?>(initialValue = null, key1 = filePath) {
        value = withContext(Dispatchers.IO) {
            BitmapFactory.decodeFile(filePath)?.asImageBitmap()
        }
    }

    var scale by remember(filePath) { mutableFloatStateOf(1f) }
    var offset by remember(filePath) { mutableStateOf(Offset.Zero) }

    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        modifier = Modifier
            .fillMaxWidth()
            .clipToBounds(),
    ) {
        val bmp = bitmapState
        if (bmp == null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("正在解码高清校历图…", style = MaterialTheme.typography.bodySmall)
            }
        } else {
            Image(
                bitmap = bmp,
                contentDescription = "官方校历图，双指可缩放，双击可放大",
                contentScale = ContentScale.FillWidth,
                modifier = Modifier
                    .fillMaxWidth()
                    .pointerInput(filePath) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            val nextScale = (scale * zoom).coerceIn(1f, 3.5f)
                            scale = nextScale
                            offset = if (nextScale > 1.01f) {
                                offset + pan
                            } else {
                                Offset.Zero
                            }
                        }
                    }
                    .pointerInput(filePath) {
                        detectTapGestures(
                            onDoubleTap = {
                                if (scale > 1.2f) {
                                    scale = 1f
                                    offset = Offset.Zero
                                } else {
                                    scale = 2.4f
                                }
                            },
                        )
                    }
                    .graphicsLayer(
                        scaleX = scale,
                        scaleY = scale,
                        translationX = offset.x,
                        translationY = offset.y,
                    ),
            )
        }
    }
}
