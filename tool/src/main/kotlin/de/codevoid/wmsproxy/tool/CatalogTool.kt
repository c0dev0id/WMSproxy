package de.codevoid.wmsproxy.tool

import de.codevoid.wmsproxy.core.CachedService
import de.codevoid.wmsproxy.core.CatalogCache
import de.codevoid.wmsproxy.core.CatalogCodec
import de.codevoid.wmsproxy.core.LibraryCodec
import de.codevoid.wmsproxy.core.LibraryEntry
import de.codevoid.wmsproxy.core.ServiceReader
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.system.exitProcess

/**
 * Builds the catalogue the app ships: the document of every library service, read by
 * the same [ServiceReader] the app uses, so what the phone shows without a request is
 * what it would have read itself.
 *
 * A service that cannot be read keeps its previous document when there is one and is
 * reported; one whose document has not changed keeps its previous read, date and all,
 * so the asset only changes where a server did. Exit status 1 when anything failed,
 * after the asset has been written.
 *
 * Usage: `CatalogTool [library.json] [catalog.json]`, paths relative to the repository.
 */
object CatalogTool {

    private const val USER_AGENT = "WMSproxy-catalog (+https://github.com/c0dev0id/WMSproxy)"
    private const val IN_FLIGHT = 6
    private val REQUEST_TIMEOUT: Duration = Duration.ofSeconds(60)

    private val client: HttpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .connectTimeout(Duration.ofSeconds(20))
        .build()

    @JvmStatic
    fun main(args: Array<String>) {
        val libraryPath = Path.of(args.getOrElse(0) { "app/src/main/assets/library.json" })
        val catalogPath = Path.of(args.getOrElse(1) { "app/src/main/assets/catalog.json" })

        val library = LibraryCodec.decode(Files.readString(libraryPath))
        val previous = if (Files.exists(catalogPath)) CatalogCodec.decodeCache(Files.readString(catalogPath)) else CatalogCache()
        println("${library.entries.size} entries, ${previous.services.size} documents in the current catalogue\n")

        val pool = Executors.newFixedThreadPool(IN_FLIGHT)
        val outcomes = try {
            pool.invokeAll(library.entries.map { entry -> Callable { read(entry, previous[entry.url]) } }).map { it.get() }
        } finally {
            pool.shutdown()
        }

        val services = outcomes.mapNotNull { it.service }.associateBy { it.url }
        Files.writeString(catalogPath, CatalogCodec.encodeCache(CatalogCache(services)) + "\n")

        val failed = outcomes.count { it.problem != null }
        for (outcome in outcomes.sortedBy { it.entry.name }) {
            val service = outcome.service
            val counts = if (service == null) "no document" else "${service.layers.size} layers, ${service.skipped.size} skipped"
            when {
                outcome.problem == null -> println("ok      ${outcome.entry.name}: $counts${if (outcome.unchanged) " (unchanged)" else ""}")
                service != null -> println("STALE   ${outcome.entry.name}: ${outcome.problem}; kept the previous read, $counts")
                else -> println("MISSING ${outcome.entry.name}: ${outcome.problem}")
            }
        }
        println("\nWrote ${services.size} documents to $catalogPath.")
        if (failed > 0) {
            println("$failed of ${library.entries.size} entries could not be read.")
            exitProcess(1)
        }
    }

    private class Outcome(val entry: LibraryEntry, val service: CachedService?, val problem: String?, val unchanged: Boolean)

    private fun read(entry: LibraryEntry, previous: CachedService?): Outcome =
        when (val result = ServiceReader.read(entry.url, System.currentTimeMillis(), ::get)) {
            is ServiceReader.Read.Service -> {
                val unchanged = previous != null && previous.sameDocumentAs(result.service)
                Outcome(entry, if (unchanged) previous else result.service, null, unchanged)
            }
            is ServiceReader.Read.Failed -> Outcome(entry, previous, result.message, false)
        }

    /**
     * The same document, whatever order it came in: some servers list their layers in a
     * different order on every request, and an asset that moved with them would change
     * on every run without a layer having changed.
     */
    private fun CachedService.sameDocumentAs(other: CachedService): Boolean =
        copy(fetchedAt = 0, layers = emptyList(), skipped = emptyList()) ==
            other.copy(fetchedAt = 0, layers = emptyList(), skipped = emptyList()) &&
            layers.toSet() == other.layers.toSet() &&
            skipped.toSet() == other.skipped.toSet()

    private fun get(url: String): ServiceReader.Reply {
        val response = client.send(request(url), HttpResponse.BodyHandlers.ofInputStream())
        return ServiceReader.Reply(response.statusCode(), response.headers().firstValue("content-type").orElse(null), response.body())
    }

    private fun request(url: String): HttpRequest = HttpRequest.newBuilder(URI(url))
        .header("User-Agent", USER_AGENT)
        .timeout(REQUEST_TIMEOUT)
        .GET()
        .build()
}
