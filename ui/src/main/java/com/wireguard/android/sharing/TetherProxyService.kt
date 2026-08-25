/*
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.sharing

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.content.ContextCompat
import com.wireguard.android.R
import com.wireguard.android.activity.SettingsActivity
import java.io.BufferedInputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean

/** A private mixed HTTP/SOCKS5 proxy for tethered clients on devices without root. */
class TetherProxyService : Service() {
    private val running = AtomicBoolean(false)
    private val workers = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "RabbitHoleShareWorker").apply { isDaemon = true }
    }
    private val connectionSlots = Semaphore(MAX_CONNECTIONS)
    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var activePort = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        val port = intent?.getIntExtra(EXTRA_PORT, SharingSettings.DEFAULT_PROXY_PORT)
            ?.takeIf { it in SharingSettings.MIN_PROXY_PORT..65535 }
            ?: SharingSettings.DEFAULT_PROXY_PORT
        startForeground(NOTIFICATION_ID, notification(port))
        if (!running.get() || activePort != port) restartListener(port)
        return START_STICKY
    }

    override fun onDestroy() {
        running.set(false)
        serverSocket?.close()
        serverSocket = null
        workers.shutdownNow()
        super.onDestroy()
    }

    private fun restartListener(port: Int) {
        running.set(false)
        serverSocket?.close()
        activePort = port
        running.set(true)
        workers.execute {
            try {
                ServerSocket().use { listener ->
                    listener.reuseAddress = true
                    listener.bind(InetSocketAddress("0.0.0.0", port), 32)
                    serverSocket = listener
                    Log.i(TAG, "Tether proxy listening on port $port")
                    while (running.get() && activePort == port) {
                        val client = listener.accept()
                        if (!connectionSlots.tryAcquire()) {
                            client.close()
                            continue
                        }
                        workers.execute {
                            try {
                                handleClient(client)
                            } catch (e: Throwable) {
                                Log.d(TAG, "Proxy client closed: ${e.message}")
                            } finally {
                                runCatching { client.close() }
                                connectionSlots.release()
                            }
                        }
                    }
                }
            } catch (e: Throwable) {
                if (running.get() && activePort == port) {
                    Log.e(TAG, "Proxy listener failed", e)
                    SharingController.reportProxyError(e.message ?: e.javaClass.simpleName)
                    stopSelf()
                }
            }
        }
    }

    private fun handleClient(client: Socket) {
        client.soTimeout = HANDSHAKE_TIMEOUT_MS
        if (!SharingController.isAllowedClient(client.inetAddress)) return
        if (!SharingController.isVpnActive()) return
        val input = BufferedInputStream(client.getInputStream())
        when (val firstByte = input.read()) {
            0x05 -> handleSocks5(client, input)
            -1 -> Unit
            else -> handleHttp(client, input, firstByte)
        }
    }

    /** The first HTTP byte was already consumed, so this overload receives it explicitly. */
    private fun handleHttp(client: Socket, input: BufferedInputStream, firstByte: Int) {
        val firstLine = (firstByte.toChar() + readAsciiLine(input)).trimEnd()
        val parts = firstLine.split(' ', limit = 3)
        if (parts.size != 3) return
        val headers = mutableListOf<String>()
        while (true) {
            val line = readAsciiLine(input)
            if (line.isEmpty()) break
            if (headers.size >= MAX_HEADERS) return
            headers += line
        }
        val method = parts[0]
        val isConnect = method.equals("CONNECT", ignoreCase = true)
        val uri = if (isConnect) null else runCatching { URI(parts[1]) }.getOrNull()
        val hostHeader = headers.firstOrNull { it.startsWith("Host:", ignoreCase = true) }
            ?.substringAfter(':')?.trim()
        val authority = if (isConnect) parts[1] else uri?.authority ?: hostHeader ?: return
        val (host, port) = parseAuthority(authority, if (uri?.scheme == "https") 443 else 80) ?: return
        if (!SharingController.isVpnActive()) return
        Socket().use { upstream ->
            upstream.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            upstream.soTimeout = 0
            client.soTimeout = 0
            if (isConnect) {
                client.getOutputStream().write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray())
            } else {
                val path = buildString {
                    append(uri?.rawPath?.takeIf(String::isNotEmpty) ?: "/")
                    uri?.rawQuery?.let { append('?').append(it) }
                }
                val out = upstream.getOutputStream()
                out.write("$method $path ${parts[2]}\r\n".toByteArray(StandardCharsets.ISO_8859_1))
                headers.filterNot { it.startsWith("Proxy-Connection:", ignoreCase = true) }
                    .forEach { out.write("$it\r\n".toByteArray(StandardCharsets.ISO_8859_1)) }
                out.write("\r\n".toByteArray())
                out.flush()
            }
            relay(client, input, upstream)
        }
    }

    private fun handleSocks5(client: Socket, input: BufferedInputStream) {
        val methodCount = readByte(input)
        val methods = ByteArray(methodCount)
        readFully(input, methods)
        if (methods.none { it.toInt() and 0xff == 0 }) {
            client.getOutputStream().write(byteArrayOf(0x05, 0xff.toByte()))
            return
        }
        client.getOutputStream().write(byteArrayOf(0x05, 0x00))
        if (readByte(input) != 0x05) return
        val command = readByte(input)
        readByte(input) // reserved
        val destination = readSocksAddress(input) ?: return
        when (command) {
            0x01 -> handleSocksConnect(client, input, destination)
            0x03 -> handleSocksUdpAssociate(client, destination)
            else -> writeSocksReply(client.getOutputStream(), 0x07, null)
        }
    }

    private fun handleSocksConnect(client: Socket, input: InputStream, destination: InetSocketAddress) {
        if (!SharingController.isVpnActive()) {
            writeSocksReply(client.getOutputStream(), 0x02, null)
            return
        }
        try {
            Socket().use { upstream ->
                upstream.connect(destination, CONNECT_TIMEOUT_MS)
                writeSocksReply(client.getOutputStream(), 0x00, upstream.localSocketAddress as? InetSocketAddress)
                client.soTimeout = 0
                upstream.soTimeout = 0
                relay(client, input, upstream)
            }
        } catch (_: Throwable) {
            writeSocksReply(client.getOutputStream(), 0x05, null)
        }
    }

    private fun handleSocksUdpAssociate(client: Socket, requested: InetSocketAddress) {
        DatagramSocket(0).use { udp ->
            udp.soTimeout = UDP_POLL_TIMEOUT_MS
            val local = InetSocketAddress(client.localAddress, udp.localPort)
            writeSocksReply(client.getOutputStream(), 0x00, local)
            client.soTimeout = UDP_POLL_TIMEOUT_MS
            val expectedIp = client.inetAddress
            var clientAddress: InetSocketAddress? = requested.takeIf {
                it.address?.isAnyLocalAddress == false && it.port != 0 && it.address == expectedIp
            }
            workers.execute {
                try {
                    while (client.getInputStream().read() >= 0) Unit
                } catch (_: Throwable) {
                } finally {
                    udp.close()
                }
            }
            val buffer = ByteArray(MAX_UDP_PACKET)
            while (running.get() && SharingController.isVpnActive()) {
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    udp.receive(packet)
                } catch (_: java.net.SocketTimeoutException) {
                    continue
                }
                val source = InetSocketAddress(packet.address, packet.port)
                if (packet.address == expectedIp && (clientAddress == null || source == clientAddress)) {
                    clientAddress = source
                    val request = parseUdpRequest(packet.data, packet.length) ?: continue
                    if (!SharingController.isVpnActive()) break
                    udp.send(DatagramPacket(request.payload, request.payload.size, request.destination))
                } else {
                    val target = clientAddress ?: continue
                    val response = wrapUdpResponse(source, packet.data, packet.length)
                    udp.send(DatagramPacket(response, response.size, target))
                }
            }
        }
    }

    private fun relay(client: Socket, clientInput: InputStream, upstream: Socket) {
        val upstreamToClient = workers.submit {
            runCatching { copy(upstream.getInputStream(), client.getOutputStream()) }
            runCatching { client.shutdownOutput() }
        }
        runCatching { copy(clientInput, upstream.getOutputStream()) }
        runCatching { upstream.shutdownOutput() }
        upstreamToClient.get()
    }

    private fun copy(input: InputStream, output: OutputStream) {
        val buffer = ByteArray(32 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) return
            output.write(buffer, 0, count)
            output.flush()
        }
    }

    private fun readSocksAddress(input: InputStream): InetSocketAddress? {
        val host = when (readByte(input)) {
            0x01 -> InetAddress.getByAddress(ByteArray(4).also { readFully(input, it) }).hostAddress
            0x04 -> InetAddress.getByAddress(ByteArray(16).also { readFully(input, it) }).hostAddress
            0x03 -> String(ByteArray(readByte(input)).also { readFully(input, it) }, StandardCharsets.UTF_8)
            else -> return null
        }
        val port = (readByte(input) shl 8) or readByte(input)
        return InetSocketAddress(host, port)
    }

    private data class UdpRequest(val destination: InetSocketAddress, val payload: ByteArray)

    private fun parseUdpRequest(data: ByteArray, length: Int): UdpRequest? {
        if (length < 10 || data[0].toInt() != 0 || data[1].toInt() != 0 || data[2].toInt() != 0) return null
        var offset = 4
        val host = when (data[3].toInt() and 0xff) {
            0x01 -> InetAddress.getByAddress(data.copyOfRange(offset, offset + 4)).hostAddress.also { offset += 4 }
            0x04 -> InetAddress.getByAddress(data.copyOfRange(offset, offset + 16)).hostAddress.also { offset += 16 }
            0x03 -> {
                val size = data[offset++].toInt() and 0xff
                if (offset + size + 2 > length) return null
                String(data, offset, size, StandardCharsets.UTF_8).also { offset += size }
            }
            else -> return null
        }
        if (offset + 2 > length) return null
        val port = ((data[offset].toInt() and 0xff) shl 8) or (data[offset + 1].toInt() and 0xff)
        offset += 2
        return UdpRequest(InetSocketAddress(host, port), data.copyOfRange(offset, length))
    }

    private fun wrapUdpResponse(source: InetSocketAddress, data: ByteArray, length: Int): ByteArray {
        val address = source.address.address
        val result = ByteArray(4 + address.size + 2 + length)
        result[3] = if (source.address is Inet4Address) 0x01 else 0x04
        address.copyInto(result, 4)
        val portOffset = 4 + address.size
        result[portOffset] = (source.port ushr 8).toByte()
        result[portOffset + 1] = source.port.toByte()
        data.copyInto(result, portOffset + 2, 0, length)
        return result
    }

    private fun writeSocksReply(output: OutputStream, result: Int, address: InetSocketAddress?) {
        val inetAddress = address?.address ?: InetAddress.getByName("0.0.0.0")
        val raw = inetAddress.address
        output.write(byteArrayOf(0x05, result.toByte(), 0x00, if (inetAddress is Inet6Address) 0x04 else 0x01))
        output.write(raw)
        output.write(byteArrayOf(((address?.port ?: 0) ushr 8).toByte(), (address?.port ?: 0).toByte()))
        output.flush()
    }

    private fun parseAuthority(value: String, defaultPort: Int): Pair<String, Int>? {
        val trimmed = value.trim()
        return try {
            val parsed = URI("scheme://$trimmed")
            val host = parsed.host ?: return null
            host to if (parsed.port >= 0) parsed.port else defaultPort
        } catch (_: Throwable) {
            null
        }
    }

    private fun readAsciiLine(input: InputStream): String {
        val bytes = ArrayList<Byte>(128)
        while (bytes.size <= MAX_LINE_LENGTH) {
            val value = input.read()
            if (value < 0) throw EOFException()
            if (value == '\n'.code) break
            if (value != '\r'.code) bytes += value.toByte()
        }
        if (bytes.size > MAX_LINE_LENGTH) throw IllegalArgumentException("Proxy header line too long")
        return String(bytes.toByteArray(), StandardCharsets.ISO_8859_1)
    }

    private fun readByte(input: InputStream): Int = input.read().takeIf { it >= 0 } ?: throw EOFException()

    private fun readFully(input: InputStream, buffer: ByteArray) {
        var offset = 0
        while (offset < buffer.size) {
            val count = input.read(buffer, offset, buffer.size - offset)
            if (count < 0) throw EOFException()
            offset += count
        }
    }

    private fun notification(port: Int): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, getString(R.string.sharing_notification_channel), NotificationManager.IMPORTANCE_LOW),
            )
        }
        val intent = Intent(this, SettingsActivity::class.java)
            .putExtra("settings_page", "sharing")
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pending = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setSmallIcon(R.drawable.ic_tile)
            .setContentTitle(getString(R.string.sharing_notification_title))
            .setContentText(getString(R.string.sharing_notification_text, port))
            .setContentIntent(pending)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "RabbitHole/TetherProxy"
        private const val CHANNEL_ID = "rabbithole_sharing"
        private const val NOTIFICATION_ID = 43031
        private const val ACTION_START = "com.wireguard.android.sharing.START"
        private const val ACTION_STOP = "com.wireguard.android.sharing.STOP"
        private const val EXTRA_PORT = "port"
        private const val MAX_CONNECTIONS = 64
        private const val MAX_HEADERS = 100
        private const val MAX_LINE_LENGTH = 16 * 1024
        private const val MAX_UDP_PACKET = 65_535
        private const val HANDSHAKE_TIMEOUT_MS = 15_000
        private const val CONNECT_TIMEOUT_MS = 12_000
        private const val UDP_POLL_TIMEOUT_MS = 2_000

        fun start(context: Context, port: Int) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, TetherProxyService::class.java)
                    .setAction(ACTION_START)
                    .putExtra(EXTRA_PORT, port),
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, TetherProxyService::class.java))
        }
    }
}
