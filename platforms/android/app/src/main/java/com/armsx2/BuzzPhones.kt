package com.armsx2

import android.os.Handler
import android.os.Looper
import android.util.Base64
import kr.co.iefriends.pcsx2.NativeApp
import java.io.BufferedInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import kotlin.concurrent.thread

/**
 * Phones as Buzz! buzzers.
 *
 * A tiny web server on the local network. A phone opens http://<thor-ip>:8642 (the Thor shows a
 * QR code for it), gets a full-screen buzzer page, and talks back over a WebSocket. Nothing to
 * install on the phone: any browser will do, iPhone or Android.
 *
 * Each phone claims one player slot. Presses go into the emulated Buzz device exactly as the
 * bottom-screen buzzers do, through NativeApp.usbLightgunButton. When a phone disconnects, any
 * button it was holding is released and its slot goes back to the Thor's screen.
 *
 * Slot rule (see [claimSlot]): a phone first takes a player nobody is using; if the screen is
 * already showing every free player, it takes the highest-numbered screen player, and the screen
 * drops that one. [screenSlots] is the other half of the same rule.
 *
 * Plain java.net with a minimal hand-written WebSocket: the messages are a few bytes each, from a
 * handful of phones, and pulling in a server library for that would cost more than it saves.
 */
object BuzzPhones {
    const val PORT = 8642
    private const val WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"

    private class Client(val socket: Socket, val out: OutputStream) {
        @Volatile var slot = -1
        /** Buttons (0 red .. 4 yellow) this phone is holding down. */
        val held = HashSet<Int>()
    }

    private var server: ServerSocket? = null
    private val lock = Any()
    private val clients = HashSet<Client>()
    private val slotOwner = arrayOfNulls<Client>(BuzzPad.PLAYERS)
    private val main = Handler(Looper.getMainLooper())

    /** Called on the main thread whenever a phone joins or leaves. */
    private val listeners = LinkedHashSet<() -> Unit>()

    @Volatile private var force = false

    fun addListener(l: () -> Unit) { listeners += l }
    fun removeListener(l: () -> Unit) { listeners -= l }
    private fun notifyChanged() = main.post { listeners.toList().forEach { runCatching { it() } } }

    // ---- Slots ----------------------------------------------------------------------------------

    /** Player slots (0-based) held by phones. */
    fun phoneSlots(): Set<Int> = synchronized(lock) {
        slotOwner.indices.filter { slotOwner[it] != null }.toSet()
    }

    /** Players the Thor's bottom screen should show: the first [count] slots no phone has. */
    fun screenSlots(count: Int): IntArray {
        val phones = phoneSlots()
        return (0 until BuzzPad.PLAYERS).filter { it !in phones }.take(count).toIntArray()
    }

    private fun claimSlot(c: Client): Int = synchronized(lock) {
        val phones = slotOwner.indices.filter { slotOwner[it] != null }.toSet()
        val screen = (0 until BuzzPad.PLAYERS).filter { it !in phones }.take(BuzzPad.playerCount())
        val slot = (0 until BuzzPad.PLAYERS).firstOrNull { it !in phones && it !in screen }
            ?: screen.lastOrNull()
            ?: return -1
        slotOwner[slot] = c
        c.slot = slot
        slot
    }

    // ---- Lifecycle ------------------------------------------------------------------------------

    fun isRunning() = server != null

    /** Start listening. Idempotent; a failure (port busy) leaves phones off and is not fatal. */
    fun start() {
        if (server != null) return
        val ss = runCatching {
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(PORT))
            }
        }.getOrNull() ?: return
        server = ss
        thread(name = "BuzzPhones-accept", isDaemon = true) {
            while (!ss.isClosed) {
                val sock = runCatching { ss.accept() }.getOrNull() ?: break
                thread(name = "BuzzPhones-client", isDaemon = true) { serve(sock) }
            }
        }
    }

    fun stop() {
        val ss = server ?: return
        server = null
        runCatching { ss.close() }
        val all = synchronized(lock) { clients.toList() }
        all.forEach { runCatching { it.socket.close() } }
    }

    /** Thor's force-colours toggle, mirrored on every phone. */
    fun setForce(on: Boolean) {
        force = on
        broadcast("""{"t":"force","on":$on}""")
    }

    // ---- Address --------------------------------------------------------------------------------

    /** The address phones should open, or null with no Wi-Fi or hotspot. */
    fun url(): String? = localAddress()?.let { "http://$it:$PORT" }

    private fun localAddress(): String? {
        val found = mutableListOf<Pair<String, String>>() // interface name, address
        runCatching {
            for (iface in NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()) {
                if (!iface.isUp || iface.isLoopback) continue
                for (a in iface.inetAddresses.toList()) {
                    if (a is Inet4Address && !a.isLoopbackAddress && a.isSiteLocalAddress)
                        found += iface.name to (a.hostAddress ?: continue)
                }
            }
        }
        // Wi-Fi or hotspot first; mobile data interfaces (rmnet) are no use to phones in the room.
        return found.firstOrNull { (n, _) -> n.startsWith("wlan") || n.startsWith("ap") || n.startsWith("swlan") }?.second
            ?: found.firstOrNull { (n, _) -> !n.startsWith("rmnet") }?.second
    }

    // ---- HTTP / WebSocket -----------------------------------------------------------------------

    private fun serve(sock: Socket) {
      try {
        runCatching {
            sock.tcpNoDelay = true
            val input = BufferedInputStream(sock.getInputStream())
            val out = sock.getOutputStream()
            val requestLine = readLine(input) ?: return
            val headers = HashMap<String, String>()
            while (true) {
                val line = readLine(input) ?: return
                if (line.isEmpty()) break
                val i = line.indexOf(':')
                if (i > 0) headers[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
            }
            val path = requestLine.split(' ').getOrNull(1)?.substringBefore('?') ?: "/"
            val key = headers["sec-websocket-key"]
            when {
                path == "/ws" && key != null -> webSocket(sock, input, out, key)
                path == "/" || path == "/index.html" -> respond(out, "200 OK", "text/html; charset=utf-8", BuzzPhonePage.HTML)
                else -> respond(out, "404 Not Found", "text/plain", "Not found")
            }
        }
      } finally {
        runCatching { sock.close() }
      }
    }

    private fun respond(out: OutputStream, status: String, type: String, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        out.write(
            ("HTTP/1.1 $status\r\nContent-Type: $type\r\nContent-Length: ${bytes.size}\r\n" +
                "Cache-Control: no-store\r\nConnection: close\r\n\r\n").toByteArray(Charsets.ISO_8859_1),
        )
        out.write(bytes)
        out.flush()
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) return sb.toString().trimEnd('\r')
            if (sb.length > 8192) return null
            sb.append(b.toChar())
        }
    }

    private fun webSocket(sock: Socket, input: InputStream, out: OutputStream, key: String) {
        val accept = Base64.encodeToString(
            MessageDigest.getInstance("SHA-1").digest((key + WS_GUID).toByteArray(Charsets.ISO_8859_1)),
            Base64.NO_WRAP,
        )
        out.write(
            ("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
                "Sec-WebSocket-Accept: $accept\r\n\r\n").toByteArray(Charsets.ISO_8859_1),
        )
        out.flush()

        val c = Client(sock, out)
        synchronized(lock) { clients += c }
        val slot = claimSlot(c)
        if (slot < 0) {
            send(c, """{"t":"full"}""")
            synchronized(lock) { clients -= c }
            return
        }
        send(c, """{"t":"slot","p":${slot + 1}}""")
        send(c, """{"t":"force","on":$force}""")
        notifyChanged()

        try {
            while (true) {
                val msg = readFrame(input, c) ?: break
                handle(c, msg)
            }
        } finally {
            disconnect(c)
        }
    }

    /** One text message, or null when the phone has gone. Pings are answered here. */
    private fun readFrame(input: InputStream, c: Client): String? {
        while (true) {
            val b0 = input.read()
            val b1 = input.read()
            if (b0 < 0 || b1 < 0) return null
            val opcode = b0 and 0x0F
            var len = (b1 and 0x7F).toLong()
            if (len == 126L) len = ((input.read() shl 8) or input.read()).toLong()
            else if (len == 127L) { len = 0; repeat(8) { len = (len shl 8) or input.read().toLong() } }
            if (len > 4096) return null
            val mask = if (b1 and 0x80 != 0) ByteArray(4) { input.read().toByte() } else null
            val data = ByteArray(len.toInt())
            var read = 0
            while (read < data.size) {
                val n = input.read(data, read, data.size - read)
                if (n < 0) return null
                read += n
            }
            if (mask != null) for (i in data.indices) data[i] = (data[i].toInt() xor mask[i % 4].toInt()).toByte()
            when (opcode) {
                0x1 -> return String(data, Charsets.UTF_8)
                0x8 -> return null
                0x9 -> sendFrame(c, 0xA, data)
                else -> Unit // binary, pong, continuation: not used
            }
        }
    }

    /** "d<n>" presses button n (0 red, 1 blue, 2 orange, 3 green, 4 yellow), "u<n>" releases it. */
    private fun handle(c: Client, msg: String) {
        if (msg.length != 2) return
        val button = msg[1] - '0'
        if (button !in 0..4 || c.slot < 0) return
        when (msg[0]) {
            'd' -> if (synchronized(c.held) { c.held.add(button) }) press(c.slot, button, true)
            'u' -> if (synchronized(c.held) { c.held.remove(button) }) press(c.slot, button, false)
        }
    }

    private fun press(slot: Int, button: Int, down: Boolean) {
        val port = BuzzPad.buzzPort()
        if (port < 0) return
        runCatching { NativeApp.usbLightgunButton(port, BuzzPad.bindId(slot, button), down) }
    }

    private fun disconnect(c: Client) {
        // Never leave a buzzer stuck down in the game because a phone locked or walked away.
        val held = synchronized(c.held) { c.held.toList().also { c.held.clear() } }
        held.forEach { press(c.slot, it, false) }
        synchronized(lock) {
            clients -= c
            if (c.slot >= 0 && slotOwner[c.slot] === c) slotOwner[c.slot] = null
        }
        runCatching { c.socket.close() }
        notifyChanged()
    }

    private fun broadcast(text: String) {
        val all = synchronized(lock) { clients.toList() }
        all.forEach { c -> thread(isDaemon = true) { send(c, text) } }
    }

    private fun send(c: Client, text: String) = sendFrame(c, 0x1, text.toByteArray(Charsets.UTF_8))

    private fun sendFrame(c: Client, opcode: Int, data: ByteArray) {
        runCatching {
            synchronized(c.out) {
                c.out.write(0x80 or opcode)
                c.out.write(data.size) // every message here is well under 126 bytes
                c.out.write(data)
                c.out.flush()
            }
        }
    }
}
