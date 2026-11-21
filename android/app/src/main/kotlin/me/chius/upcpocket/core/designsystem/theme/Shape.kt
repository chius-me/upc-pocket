package me.chius.upcpocket.core.designsystem.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

val UpcShapes = Shapes(
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

enum class GroupedCardPosition {
    SINGLE,
    TOP,
    MIDDLE,
    BOTTOM,
    ;

    fun shape(): RoundedCornerShape = when (this) {
        SINGLE -> RoundedCornerShape(24.dp)
        TOP -> RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp, bottomStart = 6.dp, bottomEnd = 6.dp)
        MIDDLE -> RoundedCornerShape(6.dp)
        BOTTOM -> RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp, bottomStart = 24.dp, bottomEnd = 24.dp)
    }

    companion object {
        fun forIndex(index: Int, count: Int): GroupedCardPosition = when {
            count <= 1 -> SINGLE
            index == 0 -> TOP
            index == count - 1 -> BOTTOM
            else -> MIDDLE
        }
    }
}
