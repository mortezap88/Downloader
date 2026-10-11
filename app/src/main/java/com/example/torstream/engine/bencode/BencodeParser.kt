package com.example.torstream.engine.bencode

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets

object BencodeParser {

    fun decode(bytes: ByteArray): Any? {
        return decode(ByteArrayInputStream(bytes))
    }

    fun decode(stream: InputStream): Any? {
        val b = stream.read()
        if (b == -1) return null

        return when (b.toChar()) {
            'i' -> decodeInteger(stream)
            'l' -> decodeList(stream)
            'd' -> decodeDictionary(stream)
            in '0'..'9' -> decodeString(stream, b)
            else -> null
        }
    }

    private fun decodeInteger(stream: InputStream): Long {
        val sb = StringBuilder()
        var c = stream.read()
        while (c != -1 && c.toChar() != 'e') {
            sb.append(c.toChar())
            c = stream.read()
        }
        return sb.toString().toLongOrNull() ?: 0L
    }

    private fun decodeString(stream: InputStream, firstDigit: Int): ByteArray {
        val lenSb = StringBuilder().append(firstDigit.toChar())
        var c = stream.read()
        while (c != -1 && c.toChar() != ':') {
            lenSb.append(c.toChar())
            c = stream.read()
        }
        val length = lenSb.toString().toIntOrNull() ?: 0
        val bytes = ByteArray(length)
        var read = 0
        while (read < length) {
            val r = stream.read(bytes, read, length - read)
            if (r == -1) break
            read += r
        }
        return bytes
    }

    private fun decodeList(stream: InputStream): List<Any> {
        val list = mutableListOf<Any>()
        while (true) {
            stream.mark(1)
            val c = stream.read()
            if (c == -1 || c.toChar() == 'e') break
            stream.reset()
            val item = decode(stream) ?: break
            list.add(item)
        }
        return list
    }

    private fun decodeDictionary(stream: InputStream): Map<String, Any> {
        val map = mutableMapOf<String, Any>()
        while (true) {
            stream.mark(1)
            val c = stream.read()
            if (c == -1 || c.toChar() == 'e') break
            stream.reset()
            val keyBytes = decode(stream) as? ByteArray ?: break
            val key = String(keyBytes, StandardCharsets.UTF_8)
            val value = decode(stream) ?: break
            map[key] = value
        }
        return map
    }
}
