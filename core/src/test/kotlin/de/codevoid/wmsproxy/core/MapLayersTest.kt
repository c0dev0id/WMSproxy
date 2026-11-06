package de.codevoid.wmsproxy.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MapLayersTest {

    private val plain = Json { ignoreUnknownKeys = true }

    private val xyz = MapLayer(
        name = "Radar",
        tiles = listOf("https://p/{z}/{x}/{y}.png"),
        source = MapLayerSource(input = "https://p/{z}/{x}/{y}.png", type = "xyz"),
    )

    @Test
    fun `import body wraps the layers under a layers array`() {
        val body = MapLayerCodec.encodeImportBody(listOf(xyz))
        val root = plain.parseToJsonElement(body).jsonObject
        assertEquals(1, root["layers"]!!.jsonArray.size)
        assertEquals("Radar", root["layers"]!!.jsonArray[0].jsonObject["name"]!!.jsonPrimitive.content)
    }

    @Test
    fun `update body wraps a single layer under the layer key`() {
        val body = MapLayerCodec.encodeUpdateBody(xyz)
        val root = plain.parseToJsonElement(body).jsonObject
        assertEquals("Radar", root["layer"]!!.jsonObject["name"]!!.jsonPrimitive.content)
    }

    @Test
    fun `reorder body carries the ids in order`() {
        val body = MapLayerCodec.encodeReorderBody(listOf("ml_a", "ml_b", "ml_c"))
        val ids = plain.parseToJsonElement(body).jsonObject["ids"]!!.jsonArray
        assertEquals(listOf("ml_a", "ml_b", "ml_c"), ids.map { it.jsonPrimitive.content })
    }

    @Test
    fun `snake_case names are used for the wire fields the server expects`() {
        val body = MapLayerCodec.encodeUpdateBody(xyz)
        val layer = plain.parseToJsonElement(body).jsonObject["layer"]!!.jsonObject
        assertTrue(layer.containsKey("tile_size"))
        assertTrue(layer.containsKey("min_zoom"))
        assertTrue(layer.containsKey("max_zoom"))
        assertFalse(layer.containsKey("tileSize"))
        assertFalse(layer.containsKey("minZoom"))
        assertFalse(layer.containsKey("maxZoom"))
    }

    @Test
    fun `null fields are omitted on write rather than sent explicitly`() {
        // bounds, attribution, token_map, library_id, id, rev, order, has_secret all default
        // to null for a plain xyz layer, and none of them should appear in the body.
        val body = MapLayerCodec.encodeUpdateBody(xyz)
        val layer = plain.parseToJsonElement(body).jsonObject["layer"]!!.jsonObject
        assertFalse("bounds leaked", layer.containsKey("bounds"))
        assertFalse("attribution leaked", layer.containsKey("attribution"))
        assertFalse("token_map leaked", layer.containsKey("token_map"))
        assertFalse("library_id leaked", layer.containsKey("library_id"))
        assertFalse("id leaked", layer.containsKey("id"))
        assertFalse("rev leaked", layer.containsKey("rev"))
        assertFalse("order leaked", layer.containsKey("order"))
        assertFalse("has_secret leaked", layer.containsKey("has_secret"))
    }

    @Test
    fun `required defaults are sent explicitly so the server does not guess`() {
        val body = MapLayerCodec.encodeUpdateBody(xyz)
        val layer = plain.parseToJsonElement(body).jsonObject["layer"]!!.jsonObject
        assertEquals("xyz", layer["scheme"]!!.jsonPrimitive.content)
        assertEquals("256", layer["tile_size"]!!.jsonPrimitive.content)
        assertEquals("overlay", layer["role"]!!.jsonPrimitive.content)
        assertEquals("none", layer["auth"]!!.jsonObject["mode"]!!.jsonPrimitive.content)
        assertEquals("true", layer["enabled"]!!.jsonObject["planner"]!!.jsonPrimitive.content)
    }

    @Test
    fun `wmts source emits matrix_set in snake_case alongside the other fields`() {
        val wmts = xyz.copy(
            tiles = listOf("https://s/wmts/web/default/WEBMERCATOR/{z}/{y}/{x}.png"),
            source = MapLayerSource(
                input = "https://s/wmts/1.0.0/WMTSCapabilities.xml",
                type = "wmts",
                layer = "web",
                style = "default",
                matrixSet = "WEBMERCATOR",
                format = "image/png",
            ),
        )
        val source = plain.parseToJsonElement(MapLayerCodec.encodeUpdateBody(wmts)).jsonObject
            .getValue("layer").jsonObject.getValue("source").jsonObject
        assertEquals("WEBMERCATOR", source["matrix_set"]!!.jsonPrimitive.content)
        assertEquals("web", source["layer"]!!.jsonPrimitive.content)
        assertFalse(source.containsKey("matrixSet"))
    }

    @Test
    fun `token_map is omitted when there is no padded zoom`() {
        val body = MapLayerCodec.encodeUpdateBody(xyz)
        assertFalse(plain.parseToJsonElement(body).jsonObject["layer"]!!.jsonObject.containsKey("token_map"))
    }

    @Test
    fun `token_map carries the z token list under the z key`() {
        val padded = xyz.copy(tokenMap = MapLayerTokens(z = listOf("00", "01", "02")))
        val tokenMap = plain.parseToJsonElement(MapLayerCodec.encodeUpdateBody(padded)).jsonObject
            .getValue("layer").jsonObject.getValue("token_map").jsonObject
        assertEquals(
            listOf("00", "01", "02"),
            tokenMap["z"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
    }

    @Test
    fun `bounds encode as the four-element wsen array`() {
        val bounded = xyz.copy(bounds = listOf(-180.0, -85.051129, 180.0, 85.051129))
        val bounds = plain.parseToJsonElement(MapLayerCodec.encodeUpdateBody(bounded)).jsonObject
            .getValue("layer").jsonObject.getValue("bounds").jsonArray
        assertEquals(4, bounds.size)
        assertEquals("-180.0", bounds[0].jsonPrimitive.content)
    }

    @Test
    fun `parseList reads the full list envelope with rev and deleted`() {
        val body = """
            {"success":true,"layers":[
              {"name":"OTM","tiles":["https://a/{z}/{x}/{y}.png"],
               "source":{"input":"https://{s}.a/{z}/{x}/{y}.png","type":"xyz"},
               "id":"ml_123","rev":12,"order":10,"has_secret":false,
               "min_zoom":0,"max_zoom":17}
            ],"deleted":["ml_gone"],"rev":42}
        """.trimIndent()
        val list = MapLayerCodec.parseList(body)
        assertEquals(42, list.rev)
        assertEquals(listOf("ml_gone"), list.deleted)
        assertEquals(1, list.layers.size)
        val layer = list.layers[0]
        assertEquals("OTM", layer.name)
        assertEquals("ml_123", layer.id)
        assertEquals(12, layer.rev)
        assertEquals(10, layer.order)
        assertEquals(17, layer.maxZoom)
        assertEquals(false, layer.hasSecret)
    }

    @Test
    fun `parseList ignores the server-side probe and any other unknown field`() {
        val body = """
            {"layers":[
              {"name":"X","tiles":["https://h/{z}/{x}/{y}.png"],
               "source":{"input":"https://h/{z}/{x}/{y}.png","type":"xyz"},
               "check":{"at":"2026-10-21T15:06:03+00:00","ok":true,"http":200,"type":"image/png"},
               "future_field":"ignored"}
            ],"rev":1}
        """.trimIndent()
        val layer = MapLayerCodec.parseList(body).layers.single()
        assertEquals("X", layer.name)
    }

    @Test
    fun `parseList coerces a null attribution against the non-nullable envelope default`() {
        // The live server writes "attribution":null; a JSON null against a field that
        // defaults to null must still decode to null, not fail the whole envelope.
        val body = """{"layers":[{"name":"Y","tiles":["u"],"source":{"input":"u","type":"xyz"},"attribution":null,"bounds":null,"token_map":null}],"rev":3}"""
        val layer = MapLayerCodec.parseList(body).layers.single()
        assertNull(layer.attribution)
        assertNull(layer.bounds)
        assertNull(layer.tokenMap)
    }

    @Test
    fun `parseList on garbage input yields the empty envelope`() {
        val list = MapLayerCodec.parseList("not json")
        assertEquals(0, list.rev)
        assertTrue(list.layers.isEmpty())
        assertTrue(list.deleted.isEmpty())
    }

    @Test
    fun `wmts token_map survives a round-trip through parseList`() {
        val body = """
            {"layers":[{"name":"T","tiles":["u"],"source":{"input":"u","type":"wmts"},
             "token_map":{"z":["00","01","02"]}}],"rev":9}
        """.trimIndent()
        val tokens = MapLayerCodec.parseList(body).layers.single().tokenMap
        assertEquals(listOf("00", "01", "02"), tokens?.z)
    }
}
