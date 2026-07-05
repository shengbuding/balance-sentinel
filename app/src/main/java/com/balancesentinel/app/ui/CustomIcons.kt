package com.balancesentinel.app.ui

import androidx.compose.material.icons.Icons
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType.Companion.NonZero
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * 手写 ImageVector 图标，替代 material-icons-extended。
 * 每个图标仅包含核心 path 数据（~200-500 字节），远小于 extended 库的 ~15MB。
 *
 * 已从 core 直接使用的图标（无需定义）：
 *   Settings, Refresh, Delete, CheckCircle, Warning, Close（替代 Cancel）,
 *   KeyboardArrowUp（替代 ExpandLess）, KeyboardArrowDown（替代 ExpandMore）
 */
object CustomIcons {
    val ErrorOutline: ImageVector
        get() = _errorOutline ?: createErrorOutline().also { _errorOutline = it }
    private var _errorOutline: ImageVector? = null

    val Visibility: ImageVector
        get() = _visibility ?: createVisibility().also { _visibility = it }
    private var _visibility: ImageVector? = null

    val VisibilityOff: ImageVector
        get() = _visibilityOff ?: createVisibilityOff().also { _visibilityOff = it }
    private var _visibilityOff: ImageVector? = null

    val Save: ImageVector
        get() = _save ?: createSave().also { _save = it }
    private var _save: ImageVector? = null

    val SwapHoriz: ImageVector
        get() = _swapHoriz ?: createSwapHoriz().also { _swapHoriz = it }
    private var _swapHoriz: ImageVector? = null

    val History: ImageVector
        get() = _history ?: createHistory().also { _history = it }
    private var _history: ImageVector? = null

    val SaveAlt: ImageVector
        get() = _saveAlt ?: createSaveAlt().also { _saveAlt = it }
    private var _saveAlt: ImageVector? = null

    val ContentCopy: ImageVector
        get() = _contentCopy ?: createContentCopy().also { _contentCopy = it }
    private var _contentCopy: ImageVector? = null

    // ── v1.3: 数据洞察图标 ──

    val TrendingUp: ImageVector
        get() = _trendingUp ?: createTrendingUp().also { _trendingUp = it }
    private var _trendingUp: ImageVector? = null

    val BarChart: ImageVector
        get() = _barChart ?: createBarChart().also { _barChart = it }
    private var _barChart: ImageVector? = null

    val CalendarMonth: ImageVector
        get() = _calendarMonth ?: createCalendarMonth().also { _calendarMonth = it }
    private var _calendarMonth: ImageVector? = null

    // ── Icon path definitions (24x24dp, extracted from Material Design icons) ──

    private fun createErrorOutline(): ImageVector = ImageVector.Builder(
        name = "ErrorOutline", defaultWidth = 24.dp, defaultHeight = 24.dp,
        viewportWidth = 24f, viewportHeight = 24f
    ).apply {
        path(fill = SolidColor(Color.Black)) {
            moveTo(11f, 15f)
            horizontalLineToRelative(2f)
            verticalLineToRelative(2f)
            horizontalLineToRelative(-2f)
            close()
            moveTo(11f, 7f)
            horizontalLineToRelative(2f)
            verticalLineToRelative(6f)
            horizontalLineToRelative(-2f)
            close()
            moveTo(11.99f, 2f)
            curveTo(6.47f, 2f, 2f, 6.48f, 2f, 12f)
            reflectiveCurveToRelative(4.47f, 10f, 9.99f, 10f)
            curveTo(17.52f, 22f, 22f, 17.52f, 22f, 12f)
            reflectiveCurveTo(17.52f, 2f, 11.99f, 2f)
            close()
            moveTo(12f, 20f)
            curveToRelative(-4.42f, 0f, -8f, -3.58f, -8f, -8f)
            reflectiveCurveToRelative(3.58f, -8f, 8f, -8f)
            reflectiveCurveToRelative(8f, 3.58f, 8f, 8f)
            reflectiveCurveToRelative(-3.58f, 8f, -8f, 8f)
            close()
        }
    }.build()

    private fun createVisibility(): ImageVector = ImageVector.Builder(
        name = "Visibility", defaultWidth = 24.dp, defaultHeight = 24.dp,
        viewportWidth = 24f, viewportHeight = 24f
    ).apply {
        path(fill = SolidColor(Color.Black)) {
            moveTo(12f, 4.5f)
            curveTo(7f, 4.5f, 2.73f, 7.61f, 1f, 12f)
            curveToRelative(1.73f, 4.39f, 6f, 7.5f, 11f, 7.5f)
            reflectiveCurveToRelative(9.27f, -3.11f, 11f, -7.5f)
            curveToRelative(-1.73f, -4.39f, -6f, -7.5f, -11f, -7.5f)
            close()
            moveTo(12f, 17f)
            curveToRelative(-2.76f, 0f, -5f, -2.24f, -5f, -5f)
            reflectiveCurveToRelative(2.24f, -5f, 5f, -5f)
            reflectiveCurveToRelative(5f, 2.24f, 5f, 5f)
            reflectiveCurveToRelative(-2.24f, 5f, -5f, 5f)
            close()
            moveTo(12f, 9f)
            curveToRelative(-1.66f, 0f, -3f, 1.34f, -3f, 3f)
            reflectiveCurveToRelative(1.34f, 3f, 3f, 3f)
            reflectiveCurveToRelative(3f, -1.34f, 3f, -3f)
            reflectiveCurveToRelative(-1.34f, -3f, -3f, -3f)
            close()
        }
    }.build()

    private fun createVisibilityOff(): ImageVector = ImageVector.Builder(
        name = "VisibilityOff", defaultWidth = 24.dp, defaultHeight = 24.dp,
        viewportWidth = 24f, viewportHeight = 24f
    ).apply {
        path(fill = SolidColor(Color.Black)) {
            moveTo(12f, 7f)
            curveToRelative(2.76f, 0f, 5f, 2.24f, 5f, 5f)
            curveToRelative(0f, 0.65f, -0.13f, 1.26f, -0.36f, 1.83f)
            lineToRelative(2.92f, 2.92f)
            curveToRelative(1.51f, -1.26f, 2.7f, -2.89f, 3.43f, -4.75f)
            curveToRelative(-1.73f, -4.39f, -6f, -7.5f, -11f, -7.5f)
            curveToRelative(-1.4f, 0f, -2.74f, 0.25f, -3.98f, 0.7f)
            lineToRelative(2.16f, 2.16f)
            curveTo(10.74f, 7.13f, 11.35f, 7f, 12f, 7f)
            close()
            moveTo(2f, 4.27f)
            lineToRelative(2.28f, 2.28f)
            lineToRelative(0.46f, 0.46f)
            curveTo(3.08f, 8.3f, 1.78f, 10.02f, 1f, 12f)
            curveToRelative(1.73f, 4.39f, 6f, 7.5f, 11f, 7.5f)
            curveToRelative(1.55f, 0f, 3.03f, -0.3f, 4.38f, -0.84f)
            lineToRelative(0.42f, 0.42f)
            lineTo(19.73f, 22f)
            lineTo(21f, 20.73f)
            lineTo(3.27f, 3f)
            close()
            moveTo(12f, 17f)
            curveToRelative(-2.76f, 0f, -5f, -2.24f, -5f, -5f)
            curveToRelative(0f, -0.56f, 0.09f, -1.08f, 0.26f, -1.57f)
            lineToRelative(1.59f, 1.59f)
            curveToRelative(-0.06f, 0.14f, -0.11f, 0.29f, -0.15f, 0.43f)
            curveToRelative(-0.24f, -0.24f, -0.51f, -0.46f, -0.8f, -0.66f)
            curveToRelative(0.28f, 0.55f, 0.63f, 1.05f, 1.05f, 1.5f)
            curveToRelative(1.95f, 1.95f, 5.12f, 1.95f, 7.07f, 0f)
            curveToRelative(0.28f, -0.28f, 0.51f, -0.58f, 0.71f, -0.89f)
            curveToRelative(-0.67f, 1.05f, -1.64f, 2.07f, -2.83f, 2.83f)
            curveToRelative(-0.49f, 0.18f, -1.01f, 0.29f, -1.55f, 0.32f)
            curveToRelative(-0.18f, 0.1f, -0.34f, 0.23f, -0.48f, 0.38f)
            curveTo(12.41f, 16.93f, 12.21f, 17f, 12f, 17f)
            close()
            moveTo(8.86f, 5.68f)
            curveTo(12.41f, 5.36f, 16.21f, 7.25f, 18.09f, 12f)
            curveToRelative(-0.88f, 1.68f, -2.13f, 3.06f, -3.66f, 4.01f)
            lineToRelative(1.42f, 1.42f)
            curveToRelative(1.78f, -1.16f, 3.21f, -2.82f, 4.14f, -4.83f)
            curveToRelative(-1.71f, -4.24f, -5.67f, -7.24f, -10.24f, -7.24f)
            curveToRelative(-0.61f, 0f, -1.2f, 0.06f, -1.78f, 0.17f)
            close()
        }
    }.build()

    private fun createSave(): ImageVector = ImageVector.Builder(
        name = "Save", defaultWidth = 24.dp, defaultHeight = 24.dp,
        viewportWidth = 24f, viewportHeight = 24f
    ).apply {
        path(fill = SolidColor(Color.Black)) {
            moveTo(17f, 3f)
            lineTo(5f, 3f)
            curveToRelative(-1.11f, 0f, -2f, 0.9f, -2f, 2f)
            verticalLineToRelative(14f)
            curveToRelative(0f, 1.1f, 0.89f, 2f, 2f, 2f)
            horizontalLineToRelative(14f)
            curveToRelative(1.1f, 0f, 2f, -0.9f, 2f, -2f)
            lineTo(21f, 7f)
            lineToRelative(-4f, -4f)
            close()
            moveTo(12f, 19f)
            curveToRelative(-1.66f, 0f, -3f, -1.34f, -3f, -3f)
            reflectiveCurveToRelative(1.34f, -3f, 3f, -3f)
            reflectiveCurveToRelative(3f, 1.34f, 3f, 3f)
            reflectiveCurveToRelative(-1.34f, 3f, -3f, 3f)
            close()
            moveTo(5f, 5f)
            horizontalLineToRelative(11.17f)
            lineTo(19f, 7.83f)
            lineTo(19f, 9f)
            lineTo(5f, 9f)
            close()
        }
    }.build()

    private fun createSwapHoriz(): ImageVector = ImageVector.Builder(
        name = "SwapHoriz", defaultWidth = 24.dp, defaultHeight = 24.dp,
        viewportWidth = 24f, viewportHeight = 24f
    ).apply {
        path(fill = SolidColor(Color.Black)) {
            moveTo(6.99f, 11f)
            lineTo(3f, 15f)
            lineToRelative(3.99f, 4f)
            verticalLineToRelative(-3f)
            lineTo(14f, 16f)
            verticalLineToRelative(-2f)
            lineTo(6.99f, 14f)
            close()
            moveTo(21f, 9f)
            lineToRelative(-3.99f, -4f)
            verticalLineToRelative(3f)
            lineTo(10f, 8f)
            verticalLineToRelative(2f)
            horizontalLineToRelative(7.01f)
            verticalLineToRelative(3f)
            close()
        }
    }.build()

    private fun createHistory(): ImageVector = ImageVector.Builder(
        name = "History", defaultWidth = 24.dp, defaultHeight = 24.dp,
        viewportWidth = 24f, viewportHeight = 24f
    ).apply {
        path(fill = SolidColor(Color.Black)) {
            moveTo(13f, 3f)
            curveToRelative(-4.97f, 0f, -9f, 4.03f, -9f, 9f)
            lineTo(1f, 12f)
            lineToRelative(3.89f, 3.89f)
            lineToRelative(0.07f, 0.14f)
            lineTo(9f, 12f)
            lineTo(6f, 12f)
            curveToRelative(0f, -3.87f, 3.13f, -7f, 7f, -7f)
            reflectiveCurveToRelative(7f, 3.13f, 7f, 7f)
            reflectiveCurveToRelative(-3.13f, 7f, -7f, 7f)
            curveToRelative(-1.93f, 0f, -3.68f, -0.79f, -4.94f, -2.06f)
            lineToRelative(-1.42f, 1.42f)
            curveTo(8.27f, 19.99f, 10.51f, 21f, 13f, 21f)
            curveToRelative(4.97f, 0f, 9f, -4.03f, 9f, -9f)
            reflectiveCurveToRelative(-4.03f, -9f, -9f, -9f)
            close()
            moveTo(12f, 8f)
            verticalLineToRelative(5f)
            lineToRelative(4.25f, 2.52f)
            lineToRelative(0.77f, -1.28f)
            lineToRelative(-3.52f, -2.09f)
            lineTo(13.5f, 8f)
            close()
        }
    }.build()

    private fun createSaveAlt(): ImageVector = ImageVector.Builder(
        name = "SaveAlt", defaultWidth = 24.dp, defaultHeight = 24.dp,
        viewportWidth = 24f, viewportHeight = 24f
    ).apply {
        path(fill = SolidColor(Color.Black)) {
            moveTo(19f, 12f)
            verticalLineToRelative(7f)
            lineTo(5f, 19f)
            verticalLineToRelative(-7f)
            lineTo(3f, 12f)
            verticalLineToRelative(7f)
            curveToRelative(0f, 1.1f, 0.9f, 2f, 2f, 2f)
            horizontalLineToRelative(14f)
            curveToRelative(1.1f, 0f, 2f, -0.9f, 2f, -2f)
            verticalLineToRelative(-7f)
            horizontalLineToRelative(-2f)
            close()
            moveTo(13f, 12.17f)
            lineToRelative(2.59f, -2.58f)
            lineTo(17f, 11f)
            lineToRelative(-5f, 5f)
            lineToRelative(-5f, -5f)
            lineToRelative(1.41f, -1.41f)
            lineTo(11f, 12.17f)
            lineTo(11f, 3f)
            horizontalLineToRelative(2f)
            close()
        }
    }.build()

    private fun createContentCopy(): ImageVector = ImageVector.Builder(
        name = "ContentCopy", defaultWidth = 24.dp, defaultHeight = 24.dp,
        viewportWidth = 24f, viewportHeight = 24f
    ).apply {
        path(fill = SolidColor(Color.Black)) {
            moveTo(16f, 1f)
            lineTo(4f, 1f)
            curveToRelative(-1.1f, 0f, -2f, 0.9f, -2f, 2f)
            verticalLineToRelative(14f)
            horizontalLineToRelative(2f)
            lineTo(4f, 3f)
            horizontalLineToRelative(12f)
            close()
            moveTo(19f, 5f)
            lineTo(8f, 5f)
            curveToRelative(-1.1f, 0f, -2f, 0.9f, -2f, 2f)
            verticalLineToRelative(14f)
            curveToRelative(0f, 1.1f, 0.9f, 2f, 2f, 2f)
            horizontalLineToRelative(11f)
            curveToRelative(1.1f, 0f, 2f, -0.9f, 2f, -2f)
            lineTo(21f, 7f)
            curveToRelative(0f, -1.1f, -0.9f, -2f, -2f, -2f)
            close()
            moveTo(19f, 21f)
            lineTo(8f, 21f)
            lineTo(8f, 7f)
            horizontalLineToRelative(11f)
            close()
        }
    }.build()

    // ── v1.3: 数据洞察图标 path definitions ──

    /** 趋势上升图标 — 用于底部导航栏"洞察"tab */
    private fun createTrendingUp(): ImageVector = ImageVector.Builder(
        name = "TrendingUp", defaultWidth = 24.dp, defaultHeight = 24.dp,
        viewportWidth = 24f, viewportHeight = 24f
    ).apply {
        path(fill = SolidColor(Color.Black)) {
            moveTo(16f, 6f)
            lineToRelative(2.29f, 2.29f)
            lineToRelative(-4.88f, 4.88f)
            lineToRelative(-4f, -4f)
            lineTo(2f, 16.59f)
            lineTo(3.41f, 18f)
            lineToRelative(6f, -6f)
            lineToRelative(4f, 4f)
            lineToRelative(6.3f, -6.29f)
            lineTo(22f, 12f)
            lineTo(22f, 6f)
            close()
        }
    }.build()

    /** 柱状图图标 — 用于账单报表 */
    private fun createBarChart(): ImageVector = ImageVector.Builder(
        name = "BarChart", defaultWidth = 24.dp, defaultHeight = 24.dp,
        viewportWidth = 24f, viewportHeight = 24f
    ).apply {
        path(fill = SolidColor(Color.Black)) {
            moveTo(5f, 9.2f)
            horizontalLineToRelative(3f)
            lineTo(8f, 19f)
            lineTo(5f, 19f)
            close()
            moveTo(10.5f, 3f)
            horizontalLineToRelative(3f)
            verticalLineToRelative(16f)
            horizontalLineToRelative(-3f)
            close()
            moveTo(16f, 13f)
            horizontalLineToRelative(3f)
            verticalLineToRelative(6f)
            horizontalLineToRelative(-3f)
            close()
        }
    }.build()

    /** 日历图标 — 用于趋势图时间范围选择 */
    private fun createCalendarMonth(): ImageVector = ImageVector.Builder(
        name = "CalendarMonth", defaultWidth = 24.dp, defaultHeight = 24.dp,
        viewportWidth = 24f, viewportHeight = 24f
    ).apply {
        path(fill = SolidColor(Color.Black)) {
            moveTo(19f, 4f)
            horizontalLineToRelative(-1f)
            lineTo(18f, 2f)
            horizontalLineToRelative(-2f)
            verticalLineToRelative(2f)
            lineTo(8f, 4f)
            lineTo(8f, 2f)
            lineTo(6f, 2f)
            verticalLineToRelative(2f)
            lineTo(5f, 4f)
            curveToRelative(-1.11f, 0f, -1.99f, 0.9f, -1.99f, 2f)
            lineTo(3f, 20f)
            curveToRelative(0f, 1.1f, 0.89f, 2f, 2f, 2f)
            horizontalLineToRelative(14f)
            curveToRelative(1.1f, 0f, 2f, -0.9f, 2f, -2f)
            lineTo(21f, 6f)
            curveToRelative(0f, -1.1f, -0.9f, -2f, -2f, -2f)
            close()
            moveTo(19f, 20f)
            lineTo(5f, 20f)
            lineTo(5f, 10f)
            horizontalLineToRelative(14f)
            close()
            moveTo(9f, 14f)
            lineTo(7f, 14f)
            verticalLineToRelative(2f)
            horizontalLineToRelative(2f)
            close()
            moveTo(13f, 14f)
            horizontalLineToRelative(-2f)
            verticalLineToRelative(2f)
            horizontalLineToRelative(2f)
            close()
            moveTo(17f, 14f)
            horizontalLineToRelative(-2f)
            verticalLineToRelative(2f)
            horizontalLineToRelative(2f)
            close()
            moveTo(9f, 18f)
            lineTo(7f, 18f)
            verticalLineToRelative(2f)
            horizontalLineToRelative(2f)
            close()
            moveTo(13f, 18f)
            horizontalLineToRelative(-2f)
            verticalLineToRelative(2f)
            horizontalLineToRelative(2f)
            close()
            moveTo(17f, 18f)
            horizontalLineToRelative(-2f)
            verticalLineToRelative(2f)
            horizontalLineToRelative(2f)
            close()
        }
    }.build()
}
