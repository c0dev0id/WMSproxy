package de.codevoid.wmsproxy.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Where a layer was measured to answer, and the template that answered. */
@Serializable
data class ZoomMeasurement(
    val minZoom: Int?,
    val maxZoom: Int?,
    /** The template to store: the layer's own, or its plain-zoom form where that worked. */
    val urlTemplate: String,
    val measuredAt: Long,
)

/**
 * A service's document as last read, kept so opening the service again costs nothing and
 * a layer loaded a second time needs no new measurement.
 *
 * Keyed by [url], the address the list knows the service by; [fetchedFrom] is the
 * candidate that actually answered, which is what a GetCapabilities preview should show.
 */
@Serializable
data class CachedService(
    val url: String,
    val fetchedFrom: String = url,
    val title: String = "",
    val service: ServiceKind,
    val fetchedAt: Long = 0,
    val layers: List<DiscoveredLayer> = emptyList(),
    val skipped: List<SkippedLayer> = emptyList(),
    /** By [DiscoveredLayer.name]. Kept past unloading, so loading a layer again is free. */
    val measured: Map<String, ZoomMeasurement> = emptyMap(),
) {
    fun layer(name: String): DiscoveredLayer? = layers.firstOrNull { it.name == name }

    fun withMeasurement(name: String, measurement: ZoomMeasurement): CachedService =
        copy(measured = measured + (name to measurement))

    /** The same document with every measurement forgotten, which a rescan asks for. */
    fun unmeasured(): CachedService = copy(measured = emptyMap())

    companion object {
        fun of(url: String, fetchedFrom: String, document: CapabilitiesResult.Success, now: Long): CachedService =
            CachedService(
                url = url,
                fetchedFrom = fetchedFrom,
                title = document.title,
                service = document.service,
                fetchedAt = now,
                layers = document.layers,
                skipped = document.skipped,
            )

        /** A tile template: no document, one implicit layer, nothing to fetch. */
        fun forTemplate(url: String, now: Long): CachedService {
            val layer = XyzTemplate.implicitLayer(url)
            return CachedService(
                url = url,
                title = layer.title,
                service = ServiceKind.XYZ,
                fetchedAt = now,
                layers = listOf(layer),
            )
        }
    }
}

/** Every service read so far. Disposable: losing it costs a re-read, never a setting. */
@Serializable
data class CatalogCache(val services: Map<String, CachedService> = emptyMap()) {
    operator fun get(url: String): CachedService? = services[url]

    fun with(service: CachedService): CatalogCache = copy(services = services + (service.url to service))

    fun without(url: String): CatalogCache = copy(services = services - url)
}

/** A service the user added by address rather than picked from the shipped list. */
@Serializable
data class OwnService(val url: String, val addedAt: Long = 0)

/**
 * The user's own services and shortlist. Small, and kept apart from [CatalogCache] so a
 * measurement never rewrites a file that holds a setting.
 */
@Serializable
data class UserCatalog(
    val own: List<OwnService> = emptyList(),
    val favorites: Set<String> = emptySet(),
) {
    fun isFavorite(key: String): Boolean = key in favorites

    fun toggleFavorite(key: String): UserCatalog =
        copy(favorites = if (key in favorites) favorites - key else favorites + key)

    fun withOwn(url: String, now: Long): UserCatalog =
        if (own.any { it.url == url }) this else copy(own = own + OwnService(url, now))

    /** Drops the service and, since nothing is left to star, its place on the shortlist. */
    fun withoutOwn(url: String): UserCatalog =
        copy(own = own.filterNot { it.url == url }, favorites = favorites - url)
}

/**
 * Reads and writes both files. Lenient on the way in: unknown keys are ignored so a file
 * from a newer build still loads, and unreadable text is an empty value, never a crash.
 */
object CatalogCodec {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encodeCache(cache: CatalogCache): String = json.encodeToString(CatalogCache.serializer(), cache)

    fun decodeCache(text: String): CatalogCache =
        runCatching { json.decodeFromString(CatalogCache.serializer(), text) }.getOrDefault(CatalogCache())

    fun encodeUser(user: UserCatalog): String = json.encodeToString(UserCatalog.serializer(), user)

    fun decodeUser(text: String): UserCatalog =
        runCatching { json.decodeFromString(UserCatalog.serializer(), text) }.getOrDefault(UserCatalog())
}
