package com.balancesentinel.app.ui.icons

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.ui.graphics.vector.ImageVector
import com.balancesentinel.app.data.api.ProviderType

/**
 * 供应商图标定义
 */
object ProviderIcons {

    /**
     * 获取供应商图标
     */
    fun getIcon(type: ProviderType): ImageVector {
        return when (type) {
            ProviderType.DEEPSEEK -> Icons.Default.AccountCircle
            ProviderType.MOONSHOT -> Icons.Default.Favorite
            ProviderType.DOUBAO -> Icons.Default.Face
            ProviderType.BAICHUAN -> Icons.Default.Home
            ProviderType.QWEN -> Icons.Default.Info
            ProviderType.ZHIPU -> Icons.Default.LocationOn
            ProviderType.WENXIN -> Icons.Default.Email
            ProviderType.OPENAI -> Icons.Default.Star
            ProviderType.ANTHROPIC -> Icons.Default.Person
            ProviderType.GEMINI -> Icons.Default.FavoriteBorder
            ProviderType.MISTRAL -> Icons.Default.ShoppingCart
            ProviderType.COHERE -> Icons.Default.ThumbUp
            ProviderType.CUSTOM -> Icons.Default.Settings
        }
    }

    /**
     * 获取供应商颜色（十六进制）
     */
    fun getColor(type: ProviderType): Long {
        return when (type) {
            ProviderType.DEEPSEEK -> 0xFF4A90E2
            ProviderType.MOONSHOT -> 0xFF9C27B0
            ProviderType.DOUBAO -> 0xFFFF6B35
            ProviderType.BAICHUAN -> 0xFF00BCD4
            ProviderType.QWEN -> 0xFFFF9800
            ProviderType.ZHIPU -> 0xFF4CAF50
            ProviderType.WENXIN -> 0xFF2196F3
            ProviderType.OPENAI -> 0xFF10A37F
            ProviderType.ANTHROPIC -> 0xFFD97706
            ProviderType.GEMINI -> 0xFF8B5CF6
            ProviderType.MISTRAL -> 0xFFEF4444
            ProviderType.COHERE -> 0xFF6366F1
            ProviderType.CUSTOM -> 0xFF6B7280
        }
    }
}
