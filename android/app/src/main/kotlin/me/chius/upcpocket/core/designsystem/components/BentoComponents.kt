package me.chius.upcpocket.core.designsystem.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Single-row Google-native top header with title, optional contextual pill, actions, and
 * a circular Google-style account/settings avatar button on the trailing edge.
 */
@Composable
fun GoogleTopBar(
    title: String,
    isAccountConfigured: Boolean,
    onAccountClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitleChip: String? = null,
    onChipClick: (() -> Unit)? = null,
    accountBadgeText: String? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.weight(1f),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!subtitleChip.isNullOrBlank()) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    onClick = { onChipClick?.invoke() },
                    enabled = onChipClick != null,
                ) {
                    Text(
                        text = subtitleChip,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        maxLines = 1,
                    )
                }
            }
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            actions()
            Surface(
                shape = CircleShape,
                color = if (isAccountConfigured) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHigh
                },
                contentColor = if (isAccountConfigured) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                onClick = onAccountClick,
                modifier = Modifier.size(40.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    if (!accountBadgeText.isNullOrBlank()) {
                        Text(
                            text = accountBadgeText,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Rounded.Person,
                            contentDescription = "账号与设置",
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
        }
    }
}

/**
 * Large Google Pixel Bento squircle tile with headline numeric/status display, optional unit & progress.
 */
@Composable
fun BentoMetricTile(
    title: String,
    icon: ImageVector,
    metricValue: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    metricUnit: String? = null,
    accentBadge: String? = null,
    progress: Float? = null,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerLowest,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    iconContainerColor: Color = MaterialTheme.colorScheme.primaryContainer,
    iconTint: Color = MaterialTheme.colorScheme.onPrimaryContainer,
) {
    Surface(
        modifier = modifier.heightIn(min = 148.dp),
        shape = RoundedCornerShape(28.dp),
        color = containerColor,
        contentColor = contentColor,
        onClick = onClick,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    Surface(
                        shape = CircleShape,
                        color = iconContainerColor,
                        contentColor = iconTint,
                        modifier = Modifier.size(34.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = icon,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (!accentBadge.isNullOrBlank()) {
                    Surface(
                        shape = CircleShape,
                        color = iconContainerColor.copy(alpha = 0.72f),
                        contentColor = iconTint,
                    ) {
                        Text(
                            text = accentBadge,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                            maxLines = 1,
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = metricValue,
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (!metricUnit.isNullOrBlank()) {
                        Text(
                            text = metricUnit,
                            style = MaterialTheme.typography.labelLarge,
                            color = contentColor.copy(alpha = 0.75f),
                            modifier = Modifier.padding(bottom = 3.dp),
                            maxLines = 1,
                        )
                    }
                }
                if (progress != null) {
                    Spacer(modifier = Modifier.height(4.dp))
                    LinearProgressIndicator(
                        progress = { progress.coerceIn(0f, 1f) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp),
                        strokeCap = StrokeCap.Round,
                        trackColor = contentColor.copy(alpha = 0.12f),
                        color = iconTint,
                    )
                }
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = contentColor.copy(alpha = 0.72f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
fun HeroAtAGlanceCard(
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerLowest,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (onClick != null) {
        Surface(
            modifier = modifier.fillMaxWidth(),
            shape = RoundedCornerShape(32.dp),
            color = containerColor,
            contentColor = contentColor,
            onClick = onClick,
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                content = content,
            )
        }
    } else {
        Surface(
            modifier = modifier.fillMaxWidth(),
            shape = RoundedCornerShape(32.dp),
            color = containerColor,
            contentColor = contentColor,
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                content = content,
            )
        }
    }
}

@Composable
fun BentoWideCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    trailingBadge: String? = null,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerLowest,
    iconContainerColor: Color = MaterialTheme.colorScheme.primaryContainer,
    iconTint: Color = MaterialTheme.colorScheme.onPrimaryContainer,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        color = containerColor,
        contentColor = MaterialTheme.colorScheme.onSurface,
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Surface(
                shape = CircleShape,
                color = iconContainerColor,
                contentColor = iconTint,
                modifier = Modifier.size(44.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (!trailingBadge.isNullOrBlank()) {
                Surface(
                    shape = CircleShape,
                    color = iconContainerColor,
                    contentColor = iconTint,
                ) {
                    Text(
                        text = trailingBadge,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
            }
        }
    }
}
