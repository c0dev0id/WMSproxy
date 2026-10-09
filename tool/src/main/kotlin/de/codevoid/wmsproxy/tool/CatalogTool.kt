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
 * A run reads only the entries the catalogue has no document for, and drops the documents
 * of entries that left the library, so adding a library entry costs one request and
 * removing one costs none. `--all` reads every service again, for a parser change or to
 * pick up what the servers changed; the Catalogue workflow does that only when started by
 * hand.
 *
 * A service that cannot be read keeps its previous document when there is one and is
 * reported; one whose document has not changed keeps its previous read, date and all,
 * so the asset only changes where a server did. Exit status 1 when anything failed,
 * after the asset has been written.
 *
 * Usage: `CatalogTool [--all] [library.json] [catalog.json]`, paths relative to the
 * repository.
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
        val all = "--all" in args
        val paths = args.filterNot { it.startsWith("--") }
        val libraryPath = Path.of(paths.getOrElse(0) { "app/src/main/assets/library.json" })
        val catalogPath = Path.of(paths.getOrElse(1) { "app/src/main/assets/catalog.json" })

        val library = LibraryCodec.decode(Files.readString(libraryPath))
        val previous = if (Files.exists(catalogPath)) CatalogCodec.decodeCache(Files.readString(catalogPath)) else CatalogCache()
        val toRead = toRead(library.entries, previous, all)
        println(
            "${library.entries.size} entries, ${previous.services.size} documents in the current catalogue, " +
                "${toRead.size} to read\n",
        )

        val pool = Executors.newFixedThreadPool(IN_FLIGHT)
        val reads = try {
            pool.invokeAll(toRead.map { entry -> Callable { read(entry, previous[entry.url]) } }).map { it.get() }
        } finally {
            pool.shutdown()
        }

        // In library order, as before, so the asset does not reorder itself between runs.
        val fresh = reads.associateBy { it.entry.url }
        val outcomes = library.entries.map { fresh[it.url] ?: Outcome(it, previous[it.url], null, unchanged = true) }
        val services = outcomes.mapNotNull { it.service }.associateBy { it.url }
        Files.writeString(catalogPath, CatalogCodec.encodeCache(CatalogCache(services)) + "\n")

        val failed = reads.count { it.problem != null }
        for (outcome in reads.sortedBy { it.entry.name }) {
            val service = outcome.service
            val counts = if (service == null) "no document" else "${service.layers.size} layers, ${service.skipped.size} skipped"
            when {
                outcome.problem == null -> println("ok      ${outcome.entry.name}: $counts${if (outcome.unchanged) " (unchanged)" else ""}")
                service != null -> println("STALE   ${outcome.entry.name}: ${outcome.problem}; kept the previous read, $counts")
                else -> println("MISSING ${outcome.entry.name}: ${outcome.problem}")
            }
        }
        println("\nKept ${outcomes.size - reads.size} documents without a request.")
        println("Wrote ${services.size} documents to $catalogPath.")
        if (failed > 0) {
            println("$failed of ${reads.size} entries could not be read.")
            exitProcess(1)
        }
    }

    /** The entries a run reads: every one with [all], else those without a document yet. */
    internal fun toRead(entries: List<LibraryEntry>, previous: CatalogCache, all: Boolean): List<LibraryEntry> =
        if (all) entries else entries.filter { previous[it.url] == null }

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
