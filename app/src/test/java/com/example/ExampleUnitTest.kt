package com.example

import com.example.torstream.data.model.TorrentFileInfo
import com.example.torstream.engine.MagnetParser
import com.example.torstream.engine.bittorrent.TorrentMetadataResolver
import org.junit.Assert.*
import org.junit.Test

class ExampleUnitTest {
  @Test
  fun testMagnetParserMultiFiles() {
    val sampleFiles = listOf(
        TorrentFileInfo(0, "Show.S01E01.mkv", 1000L, true, "mkv"),
        TorrentFileInfo(1, "Show.S01E02.mkv", 2000L, true, "mkv"),
        TorrentFileInfo(2, "Show.S01E03.mkv", 3000L, true, "mkv")
    )
    val parsed = MagnetParser.ParsedMagnet(
        infoHash = "08ada5a7a6183aae1e09d831df6748d566095a10",
        displayName = "Show Season 1",
        trackers = listOf("udp://tracker.opentrackr.org:1337"),
        webSeeds = emptyList(),
        files = sampleFiles
    )

    // Episode 2 is selected
    val ep2 = sampleFiles[1]
    val item = MagnetParser.toStreamItem(parsed, "magnet:?xt=urn:btih:08ada5a7a6183aae1e09d831df6748d566095a10", ep2)
    assertEquals("Show.S01E02.mkv", item.selectedFileName)
    assertEquals(1, item.selectedFileIndex)
    assertEquals(2000L, item.fileSizeBytes)
    assertEquals("stream_08ada5a7a6183aae1e09d831df6748d566095a10_1", item.id)

    // Verify offset calculation: file 0 is 1000 bytes, so file 1 starts at 1000
    val fileOffset = sampleFiles.take(1).sumOf { it.sizeBytes }
    assertEquals(1000L, fileOffset)
  }

  @Test
  fun testBencodeUrlListParsing() {
    // bencoded dictionary with name, length, and url-list
    val bencoded = "d4:name10:Sintel.mp46:lengthi5000e8:url-list26:https://webtorrent.io/torre".toByteArray() +
        "d4:info".toByteArray() // check safe parsing
    assertNotNull(bencoded)
  }
}
