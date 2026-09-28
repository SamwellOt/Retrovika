package com.retrovika.app.core.catalog

import androidx.annotation.StringRes
import com.retrovika.app.R

/**
 * Ordem dos resultados no Explorar. Cada site ordena do lado dele, com os critérios que tem (veja
 * [CatalogSource.sorts]); uma fonte que não conhece a ordem pedida usa a própria ([DEFAULT]). Em filtros
 * de várias fontes a lista junta as ordens de cada site, então o resultado é aproximado.
 */
enum class SortOrder(@StringRes val label: Int) {
    /** A de cada fonte: lançamentos no CDRomance e no Homebrew Hub, populares no RomsFun, downloads no Archive. */
    DEFAULT(R.string.catalog_sort_default),
    POPULAR(R.string.catalog_sort_popular),
    RATED(R.string.catalog_sort_rated),
    RECENT(R.string.catalog_sort_recent),
    TITLE(R.string.catalog_sort_title),
}
