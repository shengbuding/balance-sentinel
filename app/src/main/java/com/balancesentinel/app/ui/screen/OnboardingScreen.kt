package com.balancesentinel.app.ui.screen

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.balancesentinel.app.R
import com.balancesentinel.app.util.OnboardingHelper
import kotlinx.coroutines.launch

// ═══════════════════════════════════════════════════════════
// 引导页数据模型（纯数据，不用 Composable 资源）
// ═══════════════════════════════════════════════════════════

private data class OnboardingPage(
    val icon: ImageVector,
    val titleRes: Int,
    val descRes: Int,
    val features: List<FeatureItem> = emptyList()
)

private data class FeatureItem(
    val icon: ImageVector,
    val labelRes: Int
)

// ═══════════════════════════════════════════════════════════
// 引导页主 Composable
// ═══════════════════════════════════════════════════════════

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun OnboardingScreen(
    onComplete: () -> Unit
) {
    val context = LocalContext.current
    val pages = remember {
        listOf(
            OnboardingPage(
                icon = Icons.Filled.Info,
                titleRes = R.string.onboarding_welcome_title,
                descRes = R.string.onboarding_welcome_desc
            ),
            OnboardingPage(
                icon = Icons.Filled.Star,
                titleRes = R.string.onboarding_features_title,
                descRes = R.string.onboarding_features_desc,
                features = listOf(
                    FeatureItem(Icons.Filled.Refresh, R.string.onboarding_feat_refresh),
                    FeatureItem(Icons.Filled.Notifications, R.string.onboarding_feat_alert),
                    FeatureItem(Icons.Filled.Home, R.string.onboarding_feat_widget)
                )
            ),
            OnboardingPage(
                icon = Icons.Filled.PlayArrow,
                titleRes = R.string.onboarding_start_title,
                descRes = R.string.onboarding_start_desc
            )
        )
    }

    val pagerState = rememberPagerState(pageCount = { pages.size })
    val coroutineScope = rememberCoroutineScope()

    Scaffold(
        bottomBar = {
            OnboardingBottomBar(
                currentPage = pagerState.currentPage,
                pageCount = pages.size,
                isLastPage = pagerState.currentPage == pages.size - 1,
                onNext = {
                    coroutineScope.launch {
                        if (pagerState.currentPage < pages.size - 1) {
                            pagerState.animateScrollToPage(pagerState.currentPage + 1)
                        } else {
                            OnboardingHelper.markCompleted(context)
                            onComplete()
                        }
                    }
                },
                onSkip = {
                    OnboardingHelper.markCompleted(context)
                    onComplete()
                },
                onPageSelected = { page ->
                    coroutineScope.launch {
                        pagerState.animateScrollToPage(page)
                    }
                }
            )
        }
    ) { padding ->
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) { pageIndex ->
            OnboardingPageContent(
                page = pages[pageIndex],
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

// ═══════════════════════════════════════════════════════════
// 单页内容
// ═══════════════════════════════════════════════════════════

@Composable
private fun OnboardingPageContent(
    page: OnboardingPage,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Spacer(modifier = Modifier.weight(0.3f))

        // 图标圆形容器
        Surface(
            modifier = Modifier.size(120.dp),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = page.icon,
                    contentDescription = null,
                    modifier = Modifier.size(56.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }

        Spacer(modifier = Modifier.height(40.dp))

        // 标题
        Text(
            text = stringResource(page.titleRes),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(16.dp))

        // 描述文字
        Text(
            text = stringResource(page.descRes),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            lineHeight = 24.sp
        )

        // 功能列表
        if (page.features.isNotEmpty()) {
            Spacer(modifier = Modifier.height(32.dp))
            Column(
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                page.features.forEach { feature ->
                    FeatureRow(feature = feature)
                }
            }
        }

        Spacer(modifier = Modifier.weight(0.7f))
    }
}

// ═══════════════════════════════════════════════════════════
// 功能行
// ═══════════════════════════════════════════════════════════

@Composable
private fun FeatureRow(feature: FeatureItem) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 1.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = feature.icon,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.width(16.dp))
            Text(
                text = stringResource(feature.labelRes),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

// ═══════════════════════════════════════════════════════════
// 底部导航栏：指示器 + 按钮
// ═══════════════════════════════════════════════════════════

@Composable
private fun OnboardingBottomBar(
    currentPage: Int,
    pageCount: Int,
    isLastPage: Boolean,
    onNext: () -> Unit,
    onSkip: () -> Unit,
    onPageSelected: (Int) -> Unit
) {
    Surface(
        tonalElevation = 3.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 跳过按钮
            if (!isLastPage) {
                TextButton(onClick = onSkip) {
                    Text(
                        stringResource(R.string.onboarding_skip),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                Spacer(modifier = Modifier.width(64.dp))
            }

            // 页面指示器
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                repeat(pageCount) { index ->
                    val isSelected = index == currentPage
                    val color by animateColorAsState(
                        targetValue = if (isSelected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
                        animationSpec = tween(300),
                        label = "dotColor"
                    )
                    val width by animateDpAsState(
                        targetValue = if (isSelected) 24.dp else 8.dp,
                        animationSpec = tween(300),
                        label = "dotWidth"
                    )
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(color)
                            .size(width = width, height = 8.dp)
                    )
                }
            }

            // 下一步/开始按钮
            Button(
                onClick = onNext,
                shape = RoundedCornerShape(24.dp)
            ) {
                Text(
                    if (isLastPage) stringResource(R.string.onboarding_get_started)
                    else stringResource(R.string.onboarding_next)
                )
            }
        }
    }
}
