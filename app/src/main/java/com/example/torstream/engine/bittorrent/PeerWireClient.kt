package com.example.torstream.engine.bittorrent

import android.util.Log
import com.example.torstream.engine.bencode.BencodeParser
import kotlinx.coroutines.*
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class PeerWireClient(
    val peerAddress: InetSocketAddress,
    val infoHash: ByteArray,
    val peerId: ByteArray,
    val onMetadataReceived: (ByteArray) -> Unit,
    val onBlockReceived: (pieceIndex: Int, begin: Int, data: ByteArray) -> Unit
) {
    private val TAG = "PeerWireClient"
    private var socket: Socket? = null
    private var inStream: DataInputStream? = null
    private var outStream: DataOutputStream? = null
    private val isRunning = AtomicBoolean(false)

    val bytesRead = AtomicLong(0L)
    var peerUtMetadataId: Int = -1
    var metadataSize: Int = 0
    var isChoked: Boolean = true
    var peerHasPieces: Boolean = false

    fun isConnected(): Boolean = socket?.isConnected == true && !socket!!.isClosed

    suspend fun connectAndRun() = withContext(Dispatchers.IO) {
        try {
            socket = Socket()
            socket!!.soTimeout = 15000
            socket!!.connect(peerAddress, 5000)

            val rawIn = BufferedInputStream(socket!!.getInputStream(), 64 * 1024)
            val rawOut = BufferedOutputStream(socket!!.getOutputStream(), 64 * 1024)
            inStream = DataInputStream(rawIn)
            outStream = DataOutputStream(rawOut)

            // 1. BitTorrent Handshake (68 bytes)
            val extFlags = ByteArray(8).apply { this[5] = 0x10.toByte() } // BEP 10 extension bit
            outStream!!.writeByte(19)
            outStream!!.write("BitTorrent protocol".toByteArray(StandardCharsets.US_ASCII))
            outStream!!.write(extFlags)
            outStream!!.write(infoHash)
            outStream!!.write(peerId)
            outStream!!.flush()

            // Read peer handshake
            val pstrlen = inStream!!.readByte().toInt() and 0xFF
            if (pstrlen != 19) {
                close()
                return@withContext
            }
            val pstr = ByteArray(19)
            inStream!!.readFully(pstr)
            val peerReserved = ByteArray(8)
            inStream!!.readFully(peerReserved)
            val peerHash = ByteArray(20)
            inStream!!.readFully(peerHash)
            val peerRemoteId = ByteArray(20)
            inStream!!.readFully(peerRemoteId)

            if (!peerHash.contentEquals(infoHash)) {
                close()
                return@withContext
            }

            isRunning.set(true)

            // 2. Send BEP 10 extended handshake: d1:md11:ut_metadatai1eee
            val extHandshakePayload = "d1:md11:ut_metadatai1eee".toByteArray(StandardCharsets.UTF_8)
            sendExtendedMessage(0, extHandshakePayload)

            // Send unchoke and interested
            sendMessage(1, ByteArray(0)) // unchoke
            sendMessage(2, ByteArray(0)) // interested

            // Message reading loop
            while (isActive && isRunning.get() && socket?.isClosed == false) {
                val length = inStream!!.readInt()
                if (length == 0) continue // Keep-alive

                val messageId = inStream!!.readByte().toInt() and 0xFF
                val payloadLength = length - 1
                val payload = ByteArray(payloadLength)
                inStream!!.readFully(payload)

                bytesRead.addAndGet(length.toLong() + 4L)

                handlePeerMessage(messageId, payload)
            }
        } catch (e: Exception) {
            Log.d(TAG, "Peer ${peerAddress.hostString} closed: ${e.message}")
        } finally {
            close()
        }
    }

    private fun handlePeerMessage(id: Int, payload: ByteArray) {
        when (id) {
            0 -> isChoked = true // choke
            1 -> {
                isChoked = false // unchoke
                Log.d(TAG, "Peer ${peerAddress.hostString} unchoked us!")
            }
            4 -> peerHasPieces = true // have
            5 -> peerHasPieces = true // bitfield
            7 -> {
                // Piece block message: index (4), begin (4), block data
                if (payload.size >= 8) {
                    val pieceIndex = (payload[0].toInt() and 0xFF shl 24) or
                            (payload[1].toInt() and 0xFF shl 16) or
                            (payload[2].toInt() and 0xFF shl 8) or
                            (payload[3].toInt() and 0xFF)
                    val begin = (payload[4].toInt() and 0xFF shl 24) or
                            (payload[5].toInt() and 0xFF shl 16) or
                            (payload[6].toInt() and 0xFF shl 8) or
                            (payload[7].toInt() and 0xFF)
                    val data = payload.copyOfRange(8, payload.size)
                    onBlockReceived(pieceIndex, begin, data)
                }
            }
            20 -> {
                // BEP 10 extended message
                if (payload.isNotEmpty()) {
                    val extendedId = payload[0].toInt() and 0xFF
                    val extData = payload.copyOfRange(1, payload.size)
                    handleExtendedMessage(extendedId, extData)
                }
            }
        }
    }

    private fun handleExtendedMessage(extendedId: Int, data: ByteArray) {
        if (extendedId == 0) {
            // Extended handshake dictionary
            val decoded = BencodeParser.decode(data) as? Map<*, *> ?: return
            val m = decoded["m"] as? Map<*, *>
            peerUtMetadataId = (m?.get("ut_metadata") as? Long)?.toInt() ?: -1
            metadataSize = (decoded["metadata_size"] as? Long)?.toInt() ?: 0

            Log.d(TAG, "Peer ${peerAddress.hostString} supports ut_metadata id=$peerUtMetadataId, size=$metadataSize")

            if (peerUtMetadataId > 0) {
                // Request metadata piece 0: d8:msg_typei0e5:piecei0ee
                val reqPayload = "d8:msg_typei0e5:piecei0ee".toByteArray(StandardCharsets.UTF_8)
                sendExtendedMessage(peerUtMetadataId, reqPayload)
            }
        } else if (extendedId == 1 || extendedId == peerUtMetadataId) {
            // ut_metadata response: bencoded header + raw info dictionary
            // Look for "ee" marking end of bencode header dict
            var eeIndex = -1
            for (i in 0 until data.size - 1) {
                if (data[i] == 'e'.code.toByte() && data[i + 1] == 'e'.code.toByte()) {
                    eeIndex = i + 2
                    break
                }
            }
            if (eeIndex in 1 until data.size) {
                val rawInfoDict = data.copyOfRange(eeIndex, data.size)
                Log.d(TAG, "REAL METADATA INFO DICTIONARY RECEIVED from peer! (${rawInfoDict.size} bytes)")
                onMetadataReceived(rawInfoDict)
            }
        }
    }

    fun requestBlock(pieceIndex: Int, begin: Int, length: Int) {
        if (isChoked || outStream == null) return
        try {
            val buf = ByteArray(12)
            // piece index
            buf[0] = (pieceIndex shr 24).toByte()
            buf[1] = (pieceIndex shr 16).toByte()
            buf[2] = (pieceIndex shr 8).toByte()
            buf[3] = pieceIndex.toByte()
            // begin
            buf[4] = (begin shr 24).toByte()
            buf[5] = (begin shr 16).toByte()
            buf[6] = (begin shr 8).toByte()
            buf[7] = begin.toByte()
            // length
            buf[8] = (length shr 24).toByte()
            buf[9] = (length shr 16).toByte()
            buf[10] = (length shr 8).toByte()
            buf[11] = length.toByte()

            sendMessage(6, buf) // request
        } catch (e: Exception) {
            close()
        }
    }

    private fun sendMessage(id: Int, payload: ByteArray) {
        synchronized(this) {
            try {
                outStream?.writeInt(payload.size + 1)
                outStream?.writeByte(id)
                if (payload.isNotEmpty()) {
                    outStream?.write(payload)
                }
                outStream?.flush()
            } catch (e: Exception) {
                close()
            }
        }
    }

    private fun sendExtendedMessage(extendedId: Int, payload: ByteArray) {
        synchronized(this) {
            try {
                outStream?.writeInt(payload.size + 2)
                outStream?.writeByte(20) // ID = 20 (extended)
                outStream?.writeByte(extendedId)
                outStream?.write(payload)
                outStream?.flush()
            } catch (e: Exception) {
                close()
            }
        }
    }

    fun close() {
        isRunning.set(false)
        try {
            inStream?.close()
            outStream?.close()
            socket?.close()
        } catch (ignored: Exception) {}
        socket = null
    }
}
