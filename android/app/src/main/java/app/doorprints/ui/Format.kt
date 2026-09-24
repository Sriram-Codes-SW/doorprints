package app.doorprints.ui

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import app.doorprints.R
import app.doorprints.data.HouseEntity
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * Locale-aware formatting that follows the app language (en/hi/ta/te) with Indian conventions, like the web's
 * TranslationService: INR with lakh grouping and no decimals (₹12,50,000), Latin digits, medium date + short time.
 */
object Formats {
    /** The app language with region IN, e.g. hi-IN, so grouping and the ₹ sign are Indian in every language. */
    fun indianLocale(context: Context): Locale {
        val language = context.resources.configuration.locales[0]?.language ?: "en"
        return Locale.Builder().setLanguage(language).setRegion("IN").build()
    }

    fun price(context: Context, price: Long?, priceType: String?): String? {
        price ?: return null
        val fmt = NumberFormat.getCurrencyInstance(indianLocale(context)).apply {
            maximumFractionDigits = 0
            minimumFractionDigits = 0
        }
        val base = fmt.format(price)
        return if (priceType == "RENT") context.getString(R.string.price_per_month, base) else base
    }

    fun score(context: Context, score: Double?): String =
        score?.let { String.format(indianLocale(context), "%.1f", it) } ?: "–"

    fun dateTime(context: Context, millis: Long): String =
        DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
            .withLocale(indianLocale(context))
            .withZone(ZoneId.systemDefault())
            .format(Instant.ofEpochMilli(millis))

    fun date(context: Context, millis: Long): String =
        DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
            .withLocale(indianLocale(context))
            .withZone(ZoneId.systemDefault())
            .format(Instant.ofEpochMilli(millis))
}

@Composable
fun HouseEntity.priceText(): String? {
    LocalConfiguration.current // re-read after a language change
    return Formats.price(LocalContext.current, price, priceType)
}

@Composable
fun Double?.scoreText(): String {
    LocalConfiguration.current
    return Formats.score(LocalContext.current, this)
}

@Composable
fun Long.dateText(): String {
    LocalConfiguration.current
    return Formats.dateTime(LocalContext.current, this)
}
