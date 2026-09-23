package com.valhalla.valhalla

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.valhalla.valhalla.http.ValhallaHttpClient
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Android's HttpURLConnection negotiates and inflates gzip itself, unlike the JDK's. */
@RunWith(AndroidJUnit4::class)
class ValhallaHttpClientDeviceTest {

  private lateinit var server: LocalTileServer

  @Before
  fun setUp() {
    server = LocalTileServer(InstrumentationRegistry.getInstrumentation().targetContext.assets)
  }

  @After
  fun tearDown() {
    if (::server.isInitialized) server.close()
  }

  private val url
    get() = "http://127.0.0.1:${server.port}/$TILE"

  @Test
  fun testThePlatformNegotiatesGzipAndInflates() {
    val response = ValhallaHttpClient().get(url, 0, 0)

    assertTrue(response.success)
    assertTrue(server.acceptEncodings.single()?.contains("gzip") == true)
    assertArrayEquals(server.fixture(TILE), response.body)
  }

  @Test
  fun testRangeRequestsAskForIdentity() {
    ValhallaHttpClient().get(url, 0, 512)

    assertEquals("identity", server.acceptEncodings.single())
  }

  private companion object {
    const val TILE = "2/000/762/485.gph"
  }
}
