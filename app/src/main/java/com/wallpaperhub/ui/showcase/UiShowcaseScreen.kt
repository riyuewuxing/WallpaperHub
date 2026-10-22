package com.wallpaperhub.ui.showcase

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wallpaperhub.playback.model.PlaybackPhase
import com.wallpaperhub.ui.designsystem.PlaybackStatusPillV2
import com.wallpaperhub.ui.designsystem.WallpaperTokens

/** Debug-only visual fixture for Round-3 Ambient Gallery QA. */
@Composable
fun UiShowcaseScreen(onBack: () -> Unit) {
    var actionMessage by remember { mutableStateOf<String?>(null) }
    val onAction: (String) -> Unit = { actionMessage = it }

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            item {
                Row(
                    Modifier.fillMaxWidth().padding(top = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                    Column(Modifier.weight(1f)) {
                        Text("UI Showroom", style = MaterialTheme.typography.headlineLarge)
                        Text(
                            "Round 3 · Ambient Gallery",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    Icon(Icons.Default.CheckCircle, contentDescription = null, tint = WallpaperTokens.Success)
                }
            }

            actionMessage?.let { message ->
                item {
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.primary,
                        shape = WallpaperTokens.ShapeMd,
                    ) {
                        Text(message, modifier = Modifier.fillMaxWidth().padding(11.dp), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            item { ShowcaseSectionTitle("01 · Gallery / 图库") }
            item { ShowcaseLibraryHero(onAction) }
            item { ShowcaseSegmentedTabs(onAction) }
            item { ShowcaseTileRow() }

            item { ShowcaseSectionTitle("02 · Detail / 详情") }
            item { ShowcaseDetail(onAction) }

            item { ShowcaseSectionTitle("03 · Editor / 构图") }
            item { ShowcaseEditor(onAction) }

            item { ShowcaseSectionTitle("04 · Carousel / 轮播") }
            item { ShowcaseCarousel(onAction) }

            item { ShowcaseSectionTitle("05 · Settings / 设置") }
            item { ShowcaseSettings(onAction) }
        }
    }
}

@Composable
private fun ShowcaseSectionTitle(title: String) {
    Text(
        title,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.labelLarge,
    )
}

@Composable
private fun ShowcaseLibraryHero(onAction: (String) -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(252.dp)
            .clip(WallpaperTokens.HeroShape)
            .background(
                Brush.linearGradient(
                    listOf(Color(0xFF214C55), Color(0xFF182633), Color(0xFF090B0F)),
                ),
            ),
    ) {
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(listOf(Color.Transparent, Color.Transparent, Color.Black.copy(alpha = 0.82f))),
            ),
        )
        PlaybackStatusPillV2(
            PlaybackPhase.APPLIED,
            modifier = Modifier.align(Alignment.TopStart).padding(14.dp),
        )
        Column(Modifier.align(Alignment.BottomStart).padding(start = 16.dp, end = 92.dp, bottom = 16.dp)) {
            Text("Aurora Drift", style = MaterialTheme.typography.headlineSmall, color = Color.White)
            Text("视频 · 1080×1920 · 下一张 Moonlit Coast", color = Color.White.copy(alpha = 0.72f))
        }
        Surface(
            onClick = { onAction("打开当前壁纸详情") },
            modifier = Modifier.align(Alignment.BottomEnd).padding(14.dp).size(52.dp),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            shadowElevation = 5.dp,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Edit, contentDescription = "查看与编辑当前壁纸")
            }
        }
    }
}

@Composable
private fun ShowcaseSegmentedTabs(onAction: (String) -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f),
        shape = CircleShape,
    ) {
        Row(Modifier.padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf("全部" to true, "收藏" to false, "轮播" to false).forEach { (label, selected) ->
                Surface(
                    onClick = { onAction("切换到 $label") },
                    modifier = Modifier.weight(1f),
                    color = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                    contentColor = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    shape = CircleShape,
                ) {
                    Box(Modifier.padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
                        Text(label, style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
    }
}

@Composable
private fun ShowcaseTileRow() {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        listOf(
            "Rain Glass" to Color(0xFF274A63),
            "Neon Bloom" to Color(0xFF6F3D54),
        ).forEachIndexed { index, (name, color) ->
            Box(
                Modifier
                    .weight(1f)
                    .height(220.dp)
                    .clip(WallpaperTokens.ShapeMd)
                    .background(Brush.verticalGradient(listOf(color, Color.Black))),
            ) {
                if (index == 0) {
                    Surface(
                        modifier = Modifier.align(Alignment.TopStart).padding(8.dp),
                        shape = CircleShape,
                        color = WallpaperTokens.NavigationChrome,
                        border = BorderStroke(1.dp, WallpaperTokens.StrokeSubtle),
                    ) {
                        Text("桌面", modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp), color = MaterialTheme.colorScheme.primary)
                    }
                }
                Text(
                    name,
                    modifier = Modifier.align(Alignment.BottomStart).padding(10.dp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = Color.White,
                )
            }
        }
    }
}

@Composable
private fun ShowcaseDetail(onAction: (String) -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(WallpaperTokens.ShapeXl)
            .background(Brush.verticalGradient(listOf(Color(0xFF273D48), MaterialTheme.colorScheme.surface))),
    ) {
        Spacer(Modifier.height(130.dp))
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Moonlit Coast", style = MaterialTheme.typography.headlineMedium)
            Text("图片 · 1440×2560 · 8.2 MB", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DetailFixtureStatus("当前桌面", true, Modifier.weight(1f))
                DetailFixtureStatus("已加入轮播", true, Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = { onAction("打开编辑并应用") },
                    modifier = Modifier.weight(1f),
                ) { Text("编辑并应用") }
                FixtureRoundAction("收藏") { Icon(Icons.Default.FavoriteBorder, contentDescription = null) }
            }
        }
    }
}

@Composable
private fun DetailFixtureStatus(label: String, active: Boolean, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = CircleShape,
        color = if (active) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
        contentColor = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        border = BorderStroke(1.dp, WallpaperTokens.StrokeSubtle),
    ) {
        Box(Modifier.padding(8.dp), contentAlignment = Alignment.Center) { Text(label, style = MaterialTheme.typography.labelMedium) }
    }
}

@Composable
private fun FixtureRoundAction(label: String, content: @Composable () -> Unit) {
    Surface(
        onClick = {},
        modifier = Modifier.size(52.dp),
        shape = CircleShape,
        color = WallpaperTokens.NavigationChrome,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, WallpaperTokens.StrokeSubtle),
    ) {
        Box(contentAlignment = Alignment.Center) { content() }
    }
}

@Composable
private fun ShowcaseEditor(onAction: (String) -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(WallpaperTokens.ShapeXl).background(MaterialTheme.colorScheme.surface),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier.fillMaxWidth().height(260.dp).background(
                Brush.linearGradient(listOf(Color(0xFF1E4050), Color(0xFF0A1820))),
            ),
        ) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.28f)))
            Surface(
                modifier = Modifier.align(Alignment.Center).width(138.dp).height(238.dp),
                color = Color.Transparent,
                border = BorderStroke(2.dp, MaterialTheme.colorScheme.primary),
                shape = WallpaperTokens.ShapeSm,
            ) {}
        }
        Text("完整原图可见 · 选区外压暗", modifier = Modifier.padding(horizontal = 14.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.padding(horizontal = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Surface(
                onClick = { onAction("切换填充") },
                modifier = Modifier.weight(1f),
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.primary,
                shape = WallpaperTokens.ShapeMd,
            ) { Box(Modifier.padding(10.dp), contentAlignment = Alignment.Center) { Text("填充") } }
            Surface(
                onClick = { onAction("切换适应") },
                modifier = Modifier.weight(1f),
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = WallpaperTokens.ShapeMd,
            ) { Box(Modifier.padding(10.dp), contentAlignment = Alignment.Center) { Text("适应") } }
        }
        Button(
            onClick = { onAction("保存并应用") },
            modifier = Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, bottom = 14.dp),
        ) { Text("保存并应用") }
    }
}

@Composable
private fun ShowcaseCarousel(onAction: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("当前 · Aurora Drift", style = MaterialTheme.typography.titleLarge)
        Text("下一张 · Moonlit Coast", color = MaterialTheme.colorScheme.primary)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("1" to true, "2" to false, "3" to false).forEach { (index, active) ->
                Surface(
                    onClick = { onAction("浏览轮播第 $index 项") },
                    modifier = Modifier.weight(1f).height(112.dp),
                    shape = WallpaperTokens.ShapeMd,
                    color = if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, if (active) MaterialTheme.colorScheme.primary else WallpaperTokens.StrokeSubtle),
                ) {
                    Box(contentAlignment = Alignment.Center) { Text(index, style = MaterialTheme.typography.headlineMedium) }
                }
            }
        }
        Text("队列顺序始终可读，不用无序图片墙替代", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun ShowcaseSettings(onAction: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        listOf(
            Triple("转场效果", "Crossfade", Icons.Default.PlayArrow),
            Triple("存储与缓存", "运行缓存 620 MB", Icons.Default.Settings),
        ).forEach { (title, value, icon) ->
            Surface(
                onClick = { onAction("打开 $title") },
                color = MaterialTheme.colorScheme.surface,
                shape = WallpaperTokens.ShapeMd,
                border = BorderStroke(1.dp, WallpaperTokens.StrokeSubtle),
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Surface(
                        modifier = Modifier.size(48.dp),
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.primary,
                    ) {
                        Box(contentAlignment = Alignment.Center) { Icon(icon, contentDescription = null) }
                    }
                    Column(Modifier.weight(1f)) {
                        Text(title, style = MaterialTheme.typography.titleMedium)
                        Text(value, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}
