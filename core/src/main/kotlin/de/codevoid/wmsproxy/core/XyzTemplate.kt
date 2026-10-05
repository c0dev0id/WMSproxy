package de.codevoid.wmsproxy.core

import java.util.Locale

/**
 * A plain tile template — `https://host/{z}/{x}/{y}.png` — is a service with no document
 * to read and exactly one layer. This names that layer so a template can sit in the same
 * list and cache as a WMS, and reads the placeholders in the spellings people paste.
 */
object XyzTemplate {

    /** The one layer every template offers, under the name the list and cache use. */
    const val LAYER_NAME = "tiles"

    /** True for a template carrying zoom, column and row placeholders in any case. */
    fun isTemplate(url: String): Boolean {
        val (template, _) = normalise(url)
        return TileLayer.hasZoomPlaceholder(template) && template.contains("{x}") && template.contains("{y}")
    }

    /**
     * The template in the spelling [TileLayer.urlFor] expands, and whether its rows run
     * from the south: `{-y}` is the TMS row, which the proxy flips rather than carries.
     */
    fun normalise(url: String): Pair<String, Boolean> {
        val flipY = url.contains("{-y}") || url.contains("{-Y}")
        val template = url
            .replace("{-y}", "{y}")
            .replace("{-Y}", "{y}")
            .replace("{X}", "{x}")
            .replace("{Y}", "{y}")
            .replace("{Z}", "{z}")
        return template to flipY
    }

    /** The one layer a template offers: titled by its host, formatted from its extension. */
    fun implicitLayer(url: String): DiscoveredLayer {
        val (template, flipY) = normalise(url.trim())
        return DiscoveredLayer(
            name = LAYER_NAME,
            title = Urls.hostOf(template),
            service = ServiceKind.XYZ,
            format = formatOf(template),
            template = template,
            flipY = flipY,
        )
    }

    private fun formatOf(template: String): String {
        val path = template.substringBefore('?').lowercase(Locale.ROOT)
        return when {
            path.endsWith(".png") -> "image/png"
            path.endsWith(".jpg") || path.endsWith(".jpeg") -> "image/jpeg"
            path.endsWith(".webp") -> "image/webp"
            else -> ""
        }
    }
}
