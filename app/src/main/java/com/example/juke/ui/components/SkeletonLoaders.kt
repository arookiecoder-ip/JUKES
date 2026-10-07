package com.example.juke.ui.components

import com.example.juke.ui.components.stableStatusBarsPadding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

private val titleWidths = listOf(0.72f, 0.58f, 0.81f, 0.66f, 0.76f, 0.63f)
private val subtitleWidths = listOf(0.36f, 0.49f, 0.42f, 0.31f, 0.53f, 0.39f)

@Composable
private fun ShapedSkeletonBlock(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(12.dp)
) {
    Box(
        modifier = modifier
            .clip(shape)
            .shimmerEffect()
    )
}

@Composable
private fun TrackRowSkeleton(index: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ShapedSkeletonBlock(
            modifier = Modifier.size(48.dp),
            shape = RoundedCornerShape(8.dp)
        )
        Spacer(modifier = Modifier.width(14.dp))
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            ShapedSkeletonBlock(
                modifier = Modifier
                    .fillMaxWidth(titleWidths[index % titleWidths.size])
                    .height(18.dp),
                shape = RoundedCornerShape(6.dp)
            )
            ShapedSkeletonBlock(
                modifier = Modifier
                    .fillMaxWidth(subtitleWidths[index % subtitleWidths.size])
                    .height(12.dp),
                shape = RoundedCornerShape(6.dp)
            )
        }
        Spacer(modifier = Modifier.width(16.dp))
        ShapedSkeletonBlock(
            modifier = Modifier.width(28.dp).height(10.dp),
            shape = RoundedCornerShape(5.dp)
        )
    }
}

@Composable
fun TrackListSkeleton(
    count: Int = 8,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(top = 16.dp, bottom = 100.dp)
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        items(count) { index ->
            TrackRowSkeleton(index = index)
        }
    }
}

@Composable
fun TrackRowsSkeleton(count: Int = 6, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth()) { repeat(count) { TrackRowSkeleton(it) } }
}

@Composable
fun MediaGridSkeleton(modifier: Modifier = Modifier, contentPadding: PaddingValues = PaddingValues(20.dp)) {
    LazyVerticalGrid(GridCells.Adaptive(132.dp), modifier.fillMaxSize(), contentPadding = contentPadding,
        horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        items(12) { MediaCardSkeleton() }
    }
}

@Composable
fun MediaCardSkeleton() {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ShapedSkeletonBlock(Modifier.fillMaxWidth().aspectRatio(1f), androidx.compose.ui.graphics.RectangleShape)
        ShapedSkeletonBlock(Modifier.fillMaxWidth(0.8f).height(16.dp))
        ShapedSkeletonBlock(Modifier.fillMaxWidth(0.55f).height(12.dp))
    }
}

@Composable
private fun DetailHeroSkeleton(modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        ShapedSkeletonBlock(Modifier.width(220.dp).aspectRatio(1f), androidx.compose.ui.graphics.RectangleShape)
        ShapedSkeletonBlock(Modifier.fillMaxWidth(0.7f).height(28.dp))
        ShapedSkeletonBlock(Modifier.fillMaxWidth(0.45f).height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            repeat(4) { ShapedSkeletonBlock(Modifier.size(40.dp), CircleShape) }
        }
    }
}

@Composable
fun MediaDetailSkeleton(count: Int = 6, modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(20.dp), bottomPadding: Dp = 0.dp) {
    val landscape = LocalConfiguration.current.smallestScreenWidthDp >= 600 && LocalConfiguration.current.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    if (landscape) Row(modifier.fillMaxSize().padding(bottom = bottomPadding)) {
        Box(Modifier.weight(0.42f).fillMaxHeight(), contentAlignment = Alignment.Center) { DetailHeroSkeleton() }
        TrackListSkeleton(count, Modifier.weight(0.58f), contentPadding)
    } else LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(top = 16.dp, bottom = bottomPadding + 24.dp)) {
        item { DetailHeroSkeleton() }
        items(count) { TrackRowSkeleton(it) }
    }
}

@Composable
fun PlayerSkeleton(modifier: Modifier = Modifier) {
    com.example.juke.ui.components.player.ResponsivePlayerLayout(modifier = modifier, artwork = {
        ShapedSkeletonBlock(Modifier.fillMaxSize(), androidx.compose.ui.graphics.RectangleShape)
    }, controls = {
        Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            ShapedSkeletonBlock(Modifier.fillMaxWidth(0.65f).height(24.dp))
            ShapedSkeletonBlock(Modifier.fillMaxWidth().height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(28.dp), verticalAlignment = Alignment.CenterVertically) {
                repeat(3) { ShapedSkeletonBlock(Modifier.size(if (it == 1) 56.dp else 36.dp), CircleShape) }
            }
        }
    }, queue = { TrackListSkeleton() })
}

@Composable
fun HomeSkeleton(
    modifier: Modifier = Modifier,
    bottomPadding: Dp = 0.dp
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 24.dp,
            top = 0.dp,
            end = 24.dp,
            bottom = 24.dp + bottomPadding
        ),
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .stableStatusBarsPadding()
                    .padding(top = 24.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    ShapedSkeletonBlock(
                        modifier = Modifier
                            .width(56.dp)
                            .height(12.dp),
                        shape = RoundedCornerShape(6.dp)
                    )
                    ShapedSkeletonBlock(
                        modifier = Modifier
                            .fillMaxWidth(0.64f)
                            .height(34.dp),
                        shape = RoundedCornerShape(10.dp)
                    )
                }
                Spacer(modifier = Modifier.width(16.dp))
                ShapedSkeletonBlock(
                    modifier = Modifier.size(48.dp),
                    shape = CircleShape
                )
            }
        }

        item { HomeSkeletonSection() }
        item { TrackRowsSkeleton(4) }
        item { HomeSkeletonSection() }
    }
}

@Composable
private fun HomeSkeletonSection() {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            ShapedSkeletonBlock(
                modifier = Modifier
                    .width(132.dp)
                    .height(18.dp),
                shape = RoundedCornerShape(6.dp)
            )
            ShapedSkeletonBlock(
                modifier = Modifier
                    .width(56.dp)
                    .height(14.dp),
                shape = RoundedCornerShape(6.dp)
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            repeat(if (LocalConfiguration.current.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE) 5 else 3) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ShapedSkeletonBlock(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(0.92f),
                        shape = androidx.compose.ui.graphics.RectangleShape
                    )
                    ShapedSkeletonBlock(
                        modifier = Modifier
                            .fillMaxWidth(0.86f)
                            .height(14.dp),
                        shape = RoundedCornerShape(6.dp)
                    )
                    ShapedSkeletonBlock(
                        modifier = Modifier
                            .fillMaxWidth(0.56f)
                            .height(12.dp),
                        shape = RoundedCornerShape(6.dp)
                    )
                }
            }
        }
    }
}
@Composable
fun SearchResultsSkeleton(bottomPadding: Dp = 0.dp) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = bottomPadding + 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    repeat(4) { ShapedSkeletonBlock(Modifier.width(64.dp).height(36.dp), androidx.compose.ui.graphics.RectangleShape) }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    ShapedSkeletonBlock(Modifier.size(120.dp), androidx.compose.ui.graphics.RectangleShape)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        ShapedSkeletonBlock(Modifier.fillMaxWidth(0.8f).height(24.dp))
                        ShapedSkeletonBlock(Modifier.fillMaxWidth(0.6f).height(14.dp))
                    }
                }
            }
        }
        item { TrackRowsSkeleton() }
    }
}

@Composable
fun StatusSkeleton(modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ShapedSkeletonBlock(Modifier.fillMaxWidth(0.65f).height(16.dp))
        ShapedSkeletonBlock(Modifier.fillMaxWidth(0.85f).height(12.dp))
    }
}
