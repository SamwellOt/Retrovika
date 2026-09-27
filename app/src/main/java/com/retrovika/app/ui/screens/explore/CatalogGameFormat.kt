package com.retrovika.app.ui.screens.explore

import android.content.Context
import android.content.Intent
import android.icu.text.CompactDecimalFormat
import android.net.Uri
import androidx.annotation.StringRes
import com.retrovika.app.R
import java.text.NumberFormat
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** Formatação dos dados externos (datas ISO, contagens) no idioma do app. */
internal object CatalogGameFormat {
    /** "1996-06-23" → "23 de junho de 1996"; "1996-06" → "junho de 1996"; outros formatos ficam como vieram. */
    fun date(raw: String?, locale: Locale): String? {
        val text = raw?.trim()?.ifBlank { null } ?: return null
        runCatching {
            if (Regex("""^\d{4}-\d{2}-\d{2}$""").matches(text)) {
                return LocalDate.parse(text).format(DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG).withLocale(locale))
            }
            if (Regex("""^\d{4}-\d{2}$""").matches(text)) {
                return YearMonth.parse(text).format(DateTimeFormatter.ofPattern("LLLL yyyy", locale))
                    .replaceFirstChar { it.titlecase(locale) }
            }
        }
        return text
    }

    /** Ano de uma data em qualquer formato que traga um ano de 4 dígitos. */
    fun year(raw: String?): String? = raw?.let { Regex("""\b(19|20)\d{2}\b""").find(it)?.value }

    /** 57057 → "57 mil" / "57K". */
    fun compact(n: Number, locale: Locale): String =
        runCatching { CompactDecimalFormat.getInstance(locale, CompactDecimalFormat.CompactStyle.SHORT).format(n) }
            .getOrElse { NumberFormat.getIntegerInstance(locale).format(n) }

    fun integer(n: Long, locale: Locale): String = NumberFormat.getIntegerInstance(locale).format(n)

    /** Nota com uma casa decimal na vírgula ou no ponto do idioma: 4.159 → "4,2". */
    fun rating(value: Double, locale: Locale): String = String.format(locale, "%.1f", value)

    /** Status das reviews do Backloggd traduzido; outros ficam como vieram. */
    @StringRes
    fun status(raw: String): Int? = when (raw.trim().lowercase()) {
        "completed" -> R.string.cgame_status_completed
        "played" -> R.string.cgame_status_played
        "abandoned" -> R.string.cgame_status_abandoned
        "retired" -> R.string.cgame_status_retired
        "shelved" -> R.string.cgame_status_shelved
        "mastered" -> R.string.cgame_status_mastered
        else -> null
    }

    fun openUrl(context: Context, url: String) {
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    fun share(context: Context, title: String, url: String) {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "$title\n$url")
        runCatching { context.startActivity(Intent.createChooser(send, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
}
