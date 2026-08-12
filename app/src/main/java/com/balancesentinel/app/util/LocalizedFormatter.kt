package com.balancesentinel.app.util

import android.content.Context
import com.balancesentinel.app.R
import java.text.DateFormat
import java.text.NumberFormat
import java.util.Currency
import java.util.Date
import java.util.Locale

/** Locale-aware formatting for user-visible values. Machine formats must not use this class. */
class LocalizedFormatter(
    context: Context,
    val locale: Locale = context.resources.configuration.locales[0] ?: Locale.getDefault()
) {
    private val localizedResources = context.resources

    fun formatAmount(amount: String): String = amount.toDoubleOrNull()
        ?.let(::formatAmount)
        ?: amount

    fun formatAmount(amount: Number): String = decimalFormat(2, 2).format(amount)

    fun formatNumber(value: Number, minimumFractionDigits: Int = 0, maximumFractionDigits: Int = 2): String {
        return decimalFormat(minimumFractionDigits, maximumFractionDigits).format(value)
    }

    fun formatCurrency(amount: Number, currencyCode: String): String {
        val normalizedCode = currencyCode.uppercase(Locale.ROOT)
        val currency = runCatching { Currency.getInstance(normalizedCode) }.getOrNull()
            ?: return "$normalizedCode ${formatAmount(amount)}".trim()
        return NumberFormat.getCurrencyInstance(locale).apply {
            this.currency = currency
            minimumFractionDigits = 2
            maximumFractionDigits = 2
        }.format(amount)
    }

    fun formatSignedCurrency(amount: Number, currencyCode: String, showPositiveSign: Boolean = false): String {
        val value = amount.toDouble()
        val sign = if (showPositiveSign && value >= 0.0) "+" else ""
        return sign + formatCurrency(amount, currencyCode)
    }

    fun currencySymbol(currencyCode: String): String {
        val normalizedCode = currencyCode.uppercase(Locale.ROOT)
        return runCatching { Currency.getInstance(normalizedCode).getSymbol(locale) }
            .getOrElse { normalizedCode }
    }

    fun formatDateTime(timestamp: Long): String = DateFormat.getDateTimeInstance(
        DateFormat.SHORT,
        DateFormat.SHORT,
        locale
    ).format(Date(timestamp))

    fun formatTime(timestamp: Long): String = DateFormat.getTimeInstance(
        DateFormat.SHORT,
        locale
    ).format(Date(timestamp))

    fun formatRelativeTime(timestamp: Long, now: Long = System.currentTimeMillis()): String {
        val elapsed = (now - timestamp).coerceAtLeast(0L)
        return when {
            elapsed < MINUTE_MILLIS -> localizedResources.getString(R.string.time_just_now)
            elapsed < HOUR_MILLIS -> quantityString(
                R.plurals.time_minutes_ago,
                (elapsed / MINUTE_MILLIS).toInt()
            )
            elapsed < DAY_MILLIS -> quantityString(
                R.plurals.time_hours_ago,
                (elapsed / HOUR_MILLIS).toInt()
            )
            elapsed < WEEK_MILLIS -> quantityString(
                R.plurals.time_days_ago,
                (elapsed / DAY_MILLIS).toInt()
            )
            else -> formatDateTime(timestamp)
        }
    }

    fun formatInterval(seconds: Int): String {
        val safeSeconds = seconds.coerceAtLeast(0)
        val minutes = safeSeconds / 60
        val remainingSeconds = safeSeconds % 60
        return when {
            minutes == 0 -> quantityString(R.plurals.duration_seconds, remainingSeconds)
            remainingSeconds == 0 -> quantityString(R.plurals.duration_minutes, minutes)
            else -> localizedResources.getString(
                R.string.duration_minutes_seconds,
                minutes,
                remainingSeconds
            )
        }
    }

    fun formatCompactNumber(value: Number): String {
        val number = value.toDouble()
        return when {
            locale.language == Locale.CHINESE.language && kotlin.math.abs(number) >= 10_000.0 ->
                localizedResources.getString(
                    R.string.compact_ten_thousand_format,
                    formatNumber(number / 10_000.0, 0, 2)
                )
            kotlin.math.abs(number) >= 1_000.0 -> localizedResources.getString(
                R.string.compact_thousand_format,
                formatNumber(number / 1_000.0, 0, 2)
            )
            kotlin.math.abs(number) >= 0.01 -> formatNumber(number, 0, 2)
            else -> formatNumber(number, 0, 4)
        }
    }

    private fun decimalFormat(minimumFractionDigits: Int, maximumFractionDigits: Int): NumberFormat {
        return NumberFormat.getNumberInstance(locale).apply {
            isGroupingUsed = true
            this.minimumFractionDigits = minimumFractionDigits
            this.maximumFractionDigits = maximumFractionDigits
        }
    }

    private fun quantityString(resourceId: Int, quantity: Int): String {
        return localizedResources.getQuantityString(resourceId, quantity, quantity)
    }

    private companion object {
        const val MINUTE_MILLIS = 60_000L
        const val HOUR_MILLIS = 60 * MINUTE_MILLIS
        const val DAY_MILLIS = 24 * HOUR_MILLIS
        const val WEEK_MILLIS = 7 * DAY_MILLIS
    }
}
