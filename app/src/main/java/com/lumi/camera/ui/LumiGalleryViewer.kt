package com.lumi.camera.ui

import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.lumi.camera.gallery.GalleryPhotoItem
import com.lumi.camera.gallery.LumiGalleryStore
import com.lumi.camera.gallery.PhotoExifInfo
import com.lumi.camera.vision.PostShotRecrop
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun LumiGalleryViewerDialog(
    photos: List<GalleryPhotoItem>,
    initialIndex: Int = 0,
    onOpenRecrop: (GalleryPhotoItem) -> Unit,
    onDeletePhoto: (GalleryPhotoItem) -> Unit,
    onDismiss: () -> Unit,
) {
    if (photos.isEmpty()) {
        LaunchedEffect(Unit) { onDismiss() }
        return
    }
    val context = LocalContext.current
    val safeInitial = initialIndex.coerceIn(0, photos.lastIndex)
    val pagerState = rememberPagerState(initialPage = safeInitial, pageCount = { photos.size })
    var showExif by remember { mutableStateOf(true) }
    var pendingDelete by remember { mutableStateOf<GalleryPhotoItem?>(null) }
    var pageZoomed by remember { mutableStateOf(false) }

    val currentItem = photos.getOrNull(pagerState.currentPage) ?: photos.first()
    val exifInfo by produceState<PhotoExifInfo?>(null, currentItem.uri) {
        value = withContext(Dispatchers.IO) {
            LumiGalleryStore.readExif(context, currentItem)
        }
    }

    LaunchedEffect(pagerState.currentPage) {
        pageZoomed = false
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(LumiColors.Obsidian)
                .safeDrawingPadding(),
        ) {
            HorizontalPager(
                state = pagerState,
                userScrollEnabled = !pageZoomed,
                modifier = Modifier.fillMaxSize(),
            ) { page ->
                val item = photos[page]
                ZoomableGalleryPage(
                    item = item,
                    onZoomedChange = { zoomed ->
                        if (page == pagerState.currentPage) pageZoomed = zoomed
                    },
                )
            }

            // Top editorial bar
            Row(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .background(Color(0xB309090B))
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(
                    onClick = onDismiss,
                    shape = RoundedCornerShape(16.dp),
                    color = LumiColors.GlassSubtle,
                    border = BorderStroke(1.dp, LumiColors.Hairline),
                ) {
                    Text(
                        text = "‹ 返回",
                        color = LumiColors.TextPrimary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }

                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "${pagerState.currentPage + 1} / ${photos.size}",
                        color = LumiColors.TextPrimary,
                        fontSize = 13.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Medium,
                    )
                    val dateText = currentItem.formattedDate()
                    if (dateText.isNotBlank()) {
                        Text(
                            text = dateText,
                            color = LumiColors.TextSecondary,
                            fontSize = 10.sp,
                        )
                    }
                }

                Surface(
                    onClick = { showExif = !showExif },
                    shape = RoundedCornerShape(16.dp),
                    color = if (showExif) LumiColors.IvoryMuted else LumiColors.GlassSubtle,
                    border = BorderStroke(1.dp, if (showExif) LumiColors.Ivory else LumiColors.Hairline),
                ) {
                    Text(
                        text = "参数",
                        color = if (showExif) LumiColors.Ivory else LumiColors.TextSecondary,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
            }

            // Bottom EXIF + action bar
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Color(0xCC09090B))
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (showExif) {
                    Text(
                        text = exifInfo?.summaryLine() ?: "读取拍摄参数…",
                        color = LumiColors.TextSecondary,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    GalleryActionChip(
                        label = "精裁 / 复盘",
                        primary = true,
                        onClick = { onOpenRecrop(currentItem) },
                    )
                    GalleryActionChip(
                        label = "分享",
                        onClick = {
                            val share = Intent(Intent.ACTION_SEND).apply {
                                type = "image/jpeg"
                                putExtra(Intent.EXTRA_STREAM, currentItem.uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(Intent.createChooser(share, "分享照片"))
                        },
                    )
                    GalleryActionChip(
                        label = "删除",
                        danger = true,
                        onClick = { pendingDelete = currentItem },
                    )
                }
            }
        }
    }

    val toDelete = pendingDelete
    if (toDelete != null) {
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            containerColor = LumiColors.SurfaceDark,
            title = { Text("删除这张照片？", color = LumiColors.TextPrimary, fontSize = 16.sp) },
            text = {
                Text(
                    "将从系统相册 Pictures/Lumi 中移除，删除后无法恢复。",
                    color = LumiColors.TextSecondary,
                    fontSize = 13.sp,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingDelete = null
                        onDeletePhoto(toDelete)
                    },
                ) {
                    Text("确认删除", color = LumiColors.Danger, fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text("取消", color = LumiColors.TextSecondary)
                }
            },
        )
    }
}

@Composable
private fun ZoomableGalleryPage(
    item: GalleryPhotoItem,
    onZoomedChange: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val bitmap by produceState<Bitmap?>(null, item.uri) {
        value = withContext(Dispatchers.IO) {
            PostShotRecrop.loadPreviewBitmap(context, item.uri, maxEdge = 1600)
        }
    }
    var scale by remember(item.uri) { mutableFloatStateOf(1f) }
    var offset by remember(item.uri) { mutableStateOf(Offset.Zero) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(item.uri) {
                detectTapGestures(
                    onDoubleTap = {
                        if (scale > 1.05f) {
                            scale = 1f
                            offset = Offset.Zero
                            onZoomedChange(false)
                        } else {
                            scale = 2.5f
                            onZoomedChange(true)
                        }
                    },
                )
            }
            .then(
                if (scale > 1.02f) {
                    Modifier.pointerInput(item.uri) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            val nextScale = (scale * zoom).coerceIn(1f, 4f)
                            scale = nextScale
                            if (nextScale <= 1.02f) {
                                offset = Offset.Zero
                                onZoomedChange(false)
                            } else {
                                val maxOffsetX = (size.width * (nextScale - 1f)) / 2f
                                val maxOffsetY = (size.height * (nextScale - 1f)) / 2f
                                offset = Offset(
                                    (offset.x + pan.x).coerceIn(-maxOffsetX, maxOffsetX),
                                    (offset.y + pan.y).coerceIn(-maxOffsetY, maxOffsetY),
                                )
                                onZoomedChange(true)
                            }
                        }
                    }
                } else {
                    Modifier
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        val bmp = bitmap
        if (bmp != null) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = item.displayName,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offset.x
                        translationY = offset.y
                    },
            )
        } else {
            Spacer(Modifier.height(1.dp))
            Text("加载照片中…", color = LumiColors.TextMuted, fontSize = 12.sp)
        }
    }
}

@Composable
private fun GalleryActionChip(
    label: String,
    primary: Boolean = false,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    val bg = when {
        primary -> LumiColors.Ivory
        else -> LumiColors.GlassSubtle
    }
    val fg = when {
        primary -> LumiColors.IvoryInk
        danger -> LumiColors.Danger
        else -> LumiColors.TextPrimary
    }
    val border = when {
        primary -> LumiColors.Ivory
        danger -> LumiColors.Danger.copy(alpha = 0.5f)
        else -> LumiColors.HairlineStrong
    }
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(18.dp),
        color = bg,
        border = BorderStroke(1.dp, border),
    ) {
        Text(
            text = label,
            color = fg,
            fontSize = 12.sp,
            fontWeight = if (primary) FontWeight.SemiBold else FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}
