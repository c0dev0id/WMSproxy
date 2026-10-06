package de.codevoid.wmsproxy.catalog

import de.codevoid.wmsproxy.core.ServiceReader
import de.codevoid.wmsproxy.proxy.ProxyServer
import de.codevoid.wmsproxy.proxy.Upstream
import okhttp3.Request

/**
 * [ServiceReader] over the capabilities client, with the result put in the store: the
 * one way a screen reads a service. Blocking; call it from IO.
 */
object CapabilitiesFetcher {

    fun read(url: String): ServiceReader.Read {
        val read = ServiceReader.read(url, System.currentTimeMillis(), ::get)
        if (read is ServiceReader.Read.Service) CatalogStore.put(read.service)
        return read
    }

    /**
     * One round-trip on the capabilities client: a document can be megabytes and the
     * user is watching a spinner, not waiting on a tile. The body stream closes the
     * response when the reader is done with it.
     */
    private fun get(url: String): ServiceReader.Reply {
        val response = Upstream.capabilitiesClient.newCall(
            Request.Builder().url(url).header("User-Agent", ProxyServer.USER_AGENT).build(),
        ).execute()
        val body = response.body
        return ServiceReader.Reply(response.code, body?.contentType()?.toString(), body?.byteStream())
    }
}
