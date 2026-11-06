package de.codevoid.wmsproxy.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MapLayersTest {

    private val plain = Json { ignoreUnknownKeys = true }

    private val xyz = MapLayer(
        name = "Radar",
        tiles = listOf("https://p/{z}/{x}/{y}.png"),
        source = MapLayerSource(input = "https://p/{z}/{x}/{y}.png", type = "xyz"),
    )

    private fun encoded(layer: MapLayer): JsonObject = plain
        .parseToJsonElement(MapLayerCodec.encodeImportBody(layer))
        .jsonObject["layers"]!!.jsonArray[0].jsonObject

    @Test
    fun `import body wraps the one layer under a layers array`() {
        val body = MapLayerCodec.encodeImportBody(xyz)
        val root = plain.parseToJsonElement(body).jsonObject
        assertEquals(1, root["layers"]!!.jsonArray.size)
        assertEquals("Radar", root["layers"]!!.jsonArray[0].jsonObject["name"]!!.jsonPrimitive.content)
    }

    @Test
    fun `snake_case names are used for the wire fields the server expects`() {
        val layer = encoded(xyz)
        assertTrue(layer.containsKey("tile_size"))
        assertTrue(layer.containsKey("min_zoom"))
        assertTrue(layer.containsKey("max_zoom"))
        assertFalse(layer.containsKey("tileSize"))
        assertFalse(layer.containsKey("minZoom"))
        assertFalse(layer.containsKey("maxZoom"))
    }

    @Test
    fun `null fields are omitted on write rather than sent explicitly`() {
        val layer = encoded(xyz)
        assertFalse("token_map leaked", layer.containsKey("token_map"))
        assertFalse("id leaked", layer.containsKey("id"))
    }

    @Test
    fun `required defaults are sent explicitly so the server does not guess`() {
        val layer = encoded(xyz)
        assertEquals("xyz", layer["scheme"]!!.jsonPrimitive.content)
        assertEquals("256", layer["tile_size"]!!.jsonPrimitive.content)
        assertEquals("overlay", layer["role"]!!.jsonPrimitive.content)
        assertEquals("none", layer["auth"]!!.jsonObject["mode"]!!.jsonPrimitive.content)
        assertEquals("true", layer["enabled"]!!.jsonObject["planner"]!!.jsonPrimitive.content)
    }

    @Test
    fun `token_map carries the z token list under the z key`() {
        val padded = xyz.copy(tokenMap = MapLayerTokens(z = listOf("00", "01", "02")))
        val tokenMap = encoded(padded)["token_map"]!!.jsonObject
        assertEquals(
            listOf("00", "01", "02"),
            tokenMap["z"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
    }

    @Test
    fun `update body wraps one layer under a singular layer key`() {
        val body = MapLayerCodec.encodeUpdateBody(xyz.copy(id = "ml_xyz"))
        val root = plain.parseToJsonElement(body).jsonObject
        assertTrue("wrong wrapper key", root.containsKey("layer"))
        assertFalse("encoder emitted a plural layers key", root.containsKey("layers"))
        assertEquals("ml_xyz", root["layer"]!!.jsonObject["id"]!!.jsonPrimitive.content)
    }

    @Test
    fun `wms source carries the LAYERS parameter under the layer key`() {
        val wms = xyz.copy(
            source = MapLayerSource(
                input = "https://s?SERVICE=WMS&REQUEST=GetMap&LAYERS=roads&BBOX={bbox}",
                type = "wms",
                layer = "roads",
            ),
        )
        val source = encoded(wms)["source"]!!.jsonObject
        assertEquals("wms", source["type"]!!.jsonPrimitive.content)
        assertEquals("roads", source["layer"]!!.jsonPrimitive.content)
    }
}
