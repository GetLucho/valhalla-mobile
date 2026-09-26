package com.valhalla.valhalla

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.valhalla.valhalla.config.ValhallaConfigFactory
import com.valhalla.valhalla.config.ValhallaConfigManager
import com.valhalla.valhalla.files.ValhallaFile
import java.io.File
import java.util.UUID
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** [Valhalla.ensureTileCached] against the fixtures served over loopback. */
@RunWith(AndroidJUnit4::class)
class ValhallaPrefetchTest {

  private lateinit var context: Context
  private lateinit var tilesDir: File
  private lateinit var server: LocalTileServer

  @Before
  fun setUp() {
    context = InstrumentationRegistry.getInstrumentation().targetContext
    tilesDir = File(context.cacheDir, "prefetch-${UUID.randomUUID()}").apply { mkdirs() }
    server = LocalTileServer(context.assets)
  }

  @After
  fun tearDown() {
    if (::server.isInitialized) server.close()
    if (::tilesDir.isInitialized) tilesDir.deleteRecursively()
  }

  /** A new engine on the fixture server, with `mjolnir.tile_url_timeout` set when given. */
  private fun valhalla(timeoutSeconds: Double? = null): Valhalla {
    val config =
        ValhallaConfigFactory.usingTileUrl(
            "http://127.0.0.1:${server.port}/{tilePath}", tilesDir.absolutePath, false)
    val file = ValhallaFile(context, "prefetch.json")
    ValhallaConfigManager(context, file).writeConfig(config)
    if (timeoutSeconds != null) {
      val written = File(file.absolutePath())
      val json = JSONObject(written.readText())
      json.getJSONObject("mjolnir").put("tile_url_timeout", timeoutSeconds)
      written.writeText(json.toString())
    }
    return Valhalla(file.absolutePath())
  }

  @Test
  fun testFetchesATileTheOriginHas() {
    valhalla().use { assertTrue(it.ensureTileCached(2, TILE)) }

    assertTrue(File(tilesDir, "2/000/762/485.gph").isFile)
  }

  @Test
  fun testReturnsFalseForATileTheOriginDoesNotHave() {
    valhalla().use { assertFalse(it.ensureTileCached(2, MISSING_TILE)) }
  }

  @Test
  fun testThrowsWhenCancelled() {
    valhalla().use { valhalla ->
      valhalla.cancel()

      val error =
          assertThrows(ValhallaException.Internal::class.java) {
            valhalla.ensureTileCached(2, TILE)
          }
      assertEquals("ValhallaError(code=-1, valhalla-mobile: cancelled)", error.message)

      valhalla.resume()
      assertTrue(valhalla.ensureTileCached(2, TILE))
    }
  }

  @Test
  fun testThrowsWhenTheDeadlineFires() {
    valhalla(timeoutSeconds = 1e-9).use { valhalla ->
      val error =
          assertThrows(ValhallaException.Internal::class.java) {
            valhalla.ensureTileCached(2, TILE)
          }
      assertEquals("ValhallaError(code=-1, valhalla-mobile: tile fetch deadline)", error.message)
    }
  }

  private companion object {
    const val TILE = 762485
    const val MISSING_TILE = 762484
  }
}
