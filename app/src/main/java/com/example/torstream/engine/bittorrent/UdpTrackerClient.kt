package com.example.torstream.engine.bittorrent

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.random.Random

object UdpTrackerClient {
    private const val TAG = "UdpTrackerClient"
    private const val PROTOCOL_ID = 0x41727101980L

    data class TrackerResult(
        val seeders: Int,
        val leechers: Int,
        val peers: List<InetSocketAddress>
    )

    suspend fun announce(
        host: String,
        port: Int,
        infoHash: ByteArray,
        peerId: ByteArray
    ): TrackerResult? = withContext(Dispatchers.IO) {
        var socket: DatagramSocket? = null
        try {
            val address = InetAddress.getByName(host)
            socket = DatagramSocket()
            socket.soTimeout = 4000

            // 1. Connect Request
            val transactionId = Random.nextInt(1000, 999999)
            val connectBuf = ByteBuffer.allocate(16).order(ByteOrder.BIG_ENDIAN).apply {
                putLong(PROTOCOL_ID)
                putInt(0) // action = 0 (connect)
                putInt(transactionId)
            }

            val connectPacket = DatagramPacket(connectBuf.array(), 16, address, port)
            socket.send(connectPacket)

            // Connect Response
            val recvBuf = ByteArray(1024)
            val recvPacket = DatagramPacket(recvBuf, recvBuf.size)
            socket.receive(recvPacket)

            val connectResp = ByteBuffer.wrap(recvBuf, 0, recvPacket.length).order(ByteOrder.BIG_ENDIAN)
            val action = connectResp.getInt()
            val rTid = connectResp.getInt()
            if (action != 0 || rTid != transactionId) {
                return@withContext null
            }
            val connectionId = connectResp.getLong()

            // 2. Announce Request
            val annTid = Random.nextInt(1000, 999999)
            val annBuf = ByteBuffer.allocate(98).order(ByteOrder.BIG_ENDIAN).apply {
                putLong(connectionId)
                putInt(1) // action = 1 (announce)
                putInt(annTid)
                put(infoHash)
                put(peerId)
                putLong(0L) // downloaded
                putLong(0L) // left
                putLong(0L) // uploaded
                putInt(0)   // event = 0 (none)
                putInt(0)   // ip = 0
                putInt(0)   // key = 0
                putInt(30)  // num_want = 30
                putShort(6881.toShort()) // port
            }

            val annPacket = DatagramPacket(annBuf.array(), 98, address, port)
            socket.send(annPacket)

            // Announce Response
            val annRecvBuf = ByteArray(4096)
            val annRecvPacket = DatagramPacket(annRecvBuf, annRecvBuf.size)
            socket.receive(annRecvPacket)

            val annResp = ByteBuffer.wrap(annRecvBuf, 0, annRecvPacket.length).order(ByteOrder.BIG_ENDIAN)
            val annAction = annResp.getInt()
            val annRespTid = annResp.getInt()
            if (annAction != 1 || annRespTid != annTid) {
                return@withContext null
            }

            val interval = annResp.getInt()
            val leechers = annResp.getInt()
            val seeders = annResp.getInt()

            val peers = mutableListOf<InetSocketAddress>()
            while (annResp.remaining() >= 6) {
                val ipBytes = ByteArray(4)
                annResp.get(ipBytes)
                val peerPort = annResp.getShort().toInt() and 0xFFFF
                val peerIp = InetAddress.getByAddress(ipBytes)
                if (peerPort > 0) {
                    peers.add(InetSocketAddress(peerIp, peerPort))
                }
            }

            Log.d(TAG, "Real announce success from $host:$port: seeders=$seeders, leechers=$leechers, peers=${peers.size}")
            return@withContext TrackerResult(seeders, leechers, peers)
        } catch (e: Exception) {
            Log.d(TAG, "Tracker announce failed for $host:$port: ${e.message}")
            return@withContext null
        } finally {
            socket?.close()
        }
    }
}
