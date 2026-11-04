package com.armsx2

import android.content.Context
import com.armsx2.runtime.MainActivityRuntime
import java.net.DatagramPacket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import kotlin.concurrent.thread

/**
 * Answers "who is buzz.local?" on the local network (multicast DNS), so a phone can open
 * http://buzz.local:8642 instead of the Thor's numeric address.
 *
 * Android gives an app no way to name the device itself, so this is a minimal mDNS responder: it
 * listens on 224.0.0.251:5353 and answers A (IPv4) questions for [HOST] with the address phones
 * should use ([BuzzPhones.localIp]). It also announces the name twice on start, which fills
 * phones' caches before anyone types it. The port stays in the address: an app cannot serve on
 * port 80 without root. iPhones resolve .local names; some Android phones do not, so the QR code
 * keeps the numeric address, which always works.
 */
object BuzzMdns {
    const val HOST = "buzz.local"
    const val ADDRESS = "$HOST:${BuzzPhones.PORT}"

    private const val MDNS_PORT = 5353
    private val GROUP: InetAddress = InetAddress.getByName("224.0.0.251")
    private const val TTL_SECONDS = 120

    @Volatile private var socket: MulticastSocket? = null
    private var lock: android.net.wifi.WifiManager.MulticastLock? = null

    fun start(context: Context?) {
        if (socket != null) return
        val s = runCatching {
            MulticastSocket(null as java.net.SocketAddress?).apply {
                reuseAddress = true
                bind(InetSocketAddress(MDNS_PORT))
                timeToLive = 255
            }
        }.getOrNull() ?: return
        // Join on every IPv4 interface that is up: Wi-Fi, or the Thor's own hotspot.
        for (iface in NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()) {
            val usable = runCatching {
                iface.isUp && !iface.isLoopback && iface.supportsMulticast() &&
                    iface.inetAddresses.toList().any { it is Inet4Address }
            }.getOrDefault(false)
            if (usable) runCatching { s.joinGroup(InetSocketAddress(GROUP, MDNS_PORT), iface) }
        }
        // Wi-Fi drops multicast traffic to save power unless an app holds this lock.
        runCatching {
            val wifi = (context ?: MainActivityRuntime.instance)?.applicationContext
                ?.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
            lock = wifi?.createMulticastLock("buzz.local")?.apply {
                setReferenceCounted(false)
                acquire()
            }
        }
        socket = s
        thread(name = "BuzzMdns", isDaemon = true) { serve(s) }
        thread(name = "BuzzMdns-announce", isDaemon = true) {
            repeat(2) {
                announce(s)
                runCatching { Thread.sleep(1000) }
            }
        }
    }

    fun stop() {
        val s = socket ?: return
        socket = null
        runCatching { s.close() }
        runCatching { lock?.release() }
        lock = null
    }

    private fun serve(s: MulticastSocket) {
        val buf = ByteArray(1500)
        while (!s.isClosed) {
            val packet = DatagramPacket(buf, buf.size)
            if (runCatching { s.receive(packet) }.isFailure) break
            runCatching { answer(s, packet) }
        }
    }

    private fun announce(s: MulticastSocket) {
        val ip = BuzzPhones.localIp() ?: return
        val out = response(0, null, ip)
        runCatching { s.send(DatagramPacket(out, out.size, GROUP, MDNS_PORT)) }
    }

    /** Reply if [packet] is a query that asks for our name. */
    private fun answer(s: MulticastSocket, packet: DatagramPacket) {
        val d = packet.data
        val len = packet.length
        if (len < 12) return
        val flags = u16(d, 2)
        if (flags and 0x8000 != 0) return // a response, not a query
        val questions = u16(d, 4)
        var pos = 12
        var wanted = false
        var unicast = false
        var questionEnd = pos
        repeat(questions) {
            val (name, after) = readName(d, len, pos) ?: return
            if (after + 4 > len) return
            val type = u16(d, after)
            val klass = u16(d, after + 2)
            if (name.equals(HOST, ignoreCase = true) && (type == 1 || type == 255)) {
                wanted = true
                if (klass and 0x8000 != 0) unicast = true
                questionEnd = after + 4
            }
            pos = after + 4
        }
        if (!wanted) return
        val ip = BuzzPhones.localIp() ?: return
        // A query from a port other than 5353 is a plain ("legacy") DNS lookup: it wants its id
        // and question echoed, by unicast. Otherwise answer to the group, and to the asker too
        // when it set the unicast-response bit.
        val legacy = packet.port != MDNS_PORT
        if (legacy) {
            val question = d.copyOfRange(12, questionEnd.coerceAtMost(len))
            val out = response(u16(d, 0), question, ip)
            s.send(DatagramPacket(out, out.size, packet.address, packet.port))
            return
        }
        val out = response(0, null, ip)
        s.send(DatagramPacket(out, out.size, GROUP, MDNS_PORT))
        if (unicast) s.send(DatagramPacket(out, out.size, packet.address, packet.port))
    }

    /** A DNS response with one A record: [HOST] is at [ip]. */
    private fun response(id: Int, question: ByteArray?, ip: String): ByteArray {
        val o = java.io.ByteArrayOutputStream()
        fun w16(v: Int) { o.write(v shr 8 and 0xFF); o.write(v and 0xFF) }
        w16(id)
        w16(0x8400) // response, authoritative
        w16(if (question != null) 1 else 0)
        w16(1)
        w16(0)
        w16(0)
        question?.let { o.write(it) }
        for (label in HOST.split('.')) {
            o.write(label.length)
            o.write(label.toByteArray(Charsets.US_ASCII))
        }
        o.write(0)
        w16(1) // A
        w16(if (question != null) 0x0001 else 0x8001) // IN, with the cache-flush bit for mDNS
        w16(TTL_SECONDS shr 16)
        w16(TTL_SECONDS and 0xFFFF)
        w16(4)
        val addr = InetAddress.getByName(ip).address
        o.write(addr)
        return o.toByteArray()
    }

    private fun u16(d: ByteArray, at: Int) = ((d[at].toInt() and 0xFF) shl 8) or (d[at + 1].toInt() and 0xFF)

    /** The dotted name at [start], following compression pointers, and where the field ends. */
    private fun readName(d: ByteArray, len: Int, start: Int): Pair<String, Int>? {
        val labels = mutableListOf<String>()
        var pos = start
        var end = -1
        var jumps = 0
        while (true) {
            if (pos >= len) return null
            val n = d[pos].toInt() and 0xFF
            when {
                n == 0 -> {
                    if (end < 0) end = pos + 1
                    return labels.joinToString(".") to end
                }
                n and 0xC0 == 0xC0 -> {
                    if (pos + 1 >= len || ++jumps > 16) return null
                    if (end < 0) end = pos + 2
                    pos = ((n and 0x3F) shl 8) or (d[pos + 1].toInt() and 0xFF)
                }
                else -> {
                    if (pos + 1 + n > len) return null
                    labels += String(d, pos + 1, n, Charsets.US_ASCII)
                    pos += 1 + n
                }
            }
        }
    }
}
