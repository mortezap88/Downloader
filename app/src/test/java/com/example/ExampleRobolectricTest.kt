package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.torstream.engine.MagnetParser
import com.example.torstream.engine.bencode.BencodeParser
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    @Test
    fun `read string from context`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("TorStream", appName)
    }

    @Test
    fun `magnet link parser extracts hash and display name`() {
        val magnet = "magnet:?xt=urn:btih:dd8255ecdc7ca55fb0bbf81323d87062db1f6d1c&dn=Test+Movie+1080p&tr=udp%3A%2F%2Ftracker.opentrackr.org%3A1337%2Fannounce"
        val parsed = MagnetParser.parse(magnet)

        assertNotNull(parsed)
        assertEquals("dd8255ecdc7ca55fb0bbf81323d87062db1f6d1c", parsed?.infoHash)
        assertEquals("Test Movie 1080p", parsed?.displayName)
        assertTrue(parsed?.trackers?.isNotEmpty() == true)
        assertTrue(parsed?.files?.isNotEmpty() == true)
    }

    @Test
    fun `bencode parser decodes dictionary and integers accurately`() {
        val bencoded = "d4:name10:sample.mkv6:lengthi10485760ee".toByteArray()
        val decoded = BencodeParser.decode(bencoded) as? Map<*, *>

        assertNotNull(decoded)
        val nameBytes = decoded?.get("name") as? ByteArray
        val length = decoded?.get("length") as? Long

        assertEquals("sample.mkv", nameBytes?.let { String(it) })
        assertEquals(10485760L, length)
    }

    @Test
    fun `raw info hash parser accepts 40 hex characters`() {
        val rawHash = "209c3cd4e0e56dae8a7195eb079c0953a830b503"
        val parsed = MagnetParser.parse(rawHash)

        assertNotNull(parsed)
        assertEquals("209c3cd4e0e56dae8a7195eb079c0953a830b503", parsed?.infoHash)
        assertTrue(parsed?.displayName?.contains("Torrent") == true)
    }
}
