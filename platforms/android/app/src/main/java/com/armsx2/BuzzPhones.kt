package com.armsx2

import android.os.Handler
import android.os.Looper
import android.util.Base64
import java.io.BufferedInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import com.armsx2.runtime.MainActivityRuntime
import kotlin.concurrent.thread

/**
 * Phones as Buzz! buzzers.
 *
 * A tiny web server on the local network. A phone opens http://<thor-ip>:8642 (the Thor shows a
 * QR code for it), gets a full-screen buzzer page, and talks back over a WebSocket. Nothing to
 * install on the phone: any browser will do, iPhone or Android.
 *
 * A phone joins with a name and a seat (the page suggests one), and gets a token so it can come
 * back to the same seat if it drops off: the seat is held for a minute. Presses go into the
 * emulated Buzz device exactly as the bottom-screen buzzers do. Every two seconds each phone is
 * pinged, and the round trip becomes the signal strength the screens show.
 *
 * Slot rule (see [claimSlot]): the Thor's screen holds up to four players; phones fill the rest
 * of the game first (players 5-8), then take the screen's highest player. [screenSlots] is the
 * other half of the rule.
 *
 * Plain java.net with a minimal hand-written WebSocket: the messages are a few bytes each, from a
 * handful of phones, and pulling in a server library for that would cost more than it saves.
 */
object BuzzPhones {
    const val PORT = 8642
    private const val WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"

    /** How long a phone that dropped off keeps its seat. */
    private const val HOLD_SEAT_MS = 60_000L
    private const val PING_MS = 2_000L

    private class Client(val socket: Socket, val out: OutputStream) {
        @Volatile var slot = -1
        /** Joined a seat (the join form is done). Until then it gets seat updates. */
        @Volatile var joined = false
        @Volatile var lastPong = System.currentTimeMillis()
        /** Buttons (0 red .. 4 yellow) this phone is holding down. */
        val held = HashSet<Int>()
    }

    /** A seat a phone holds: while connected, and for [HOLD_SEAT_MS] after it drops off. */
    private class Seat(val token: String, var name: String) {
        var client: Client? = null
        var lostAt = 0L
        var quality = Quality.GOOD
    }

    enum class Quality(val label: String) { GOOD("strong signal"), OK("fair signal"), WEAK("weak signal") }

    /** What the screens show about a phone player. */
    data class PhoneSeat(val slot: Int, val name: String, val connected: Boolean, val quality: Quality) {
        val where: String get() = if (connected) "Phone · ${quality.label}" else "Phone · reconnecting"
    }

    private var server: ServerSocket? = null
    private val lock = Any()
    private val clients = HashSet<Client>()
    private val seats = arrayOfNulls<Seat>(BuzzPad.PLAYERS)
    private val main = Handler(Looper.getMainLooper())

    /** Called on the main thread whenever a phone joins, leaves or changes. */
    private val listeners = LinkedHashSet<() -> Unit>()

    /** Bumped on every change, for Compose screens (the pause menu's player list). */
    val version = androidx.compose.runtime.mutableIntStateOf(0)

    @Volatile private var force = false

    fun addListener(l: () -> Unit) { listeners += l }
    fun removeListener(l: () -> Unit) { listeners -= l }
    private fun notifyChanged() {
        main.post {
            version.intValue++
            listeners.toList().forEach { runCatching { it() } }
        }
        // Phones still on the join form see seats come and go.
        val waiting = synchronized(lock) { clients.filter { !it.joined } }
        waiting.forEach { c -> thread(isDaemon = true) { send(c, helloJson()) } }
    }

    // ---- Seats ----------------------------------------------------------------------------------

    /** Player slots (0-based) held by phones, including ones briefly reconnecting. */
    fun phoneSlots(): Set<Int> = synchronized(lock) {
        seats.indices.filter { seats[it] != null }.toSet()
    }

    /** Every phone player, for the roster, the bottom bar and the pause menu. */
    fun phoneSeats(): List<PhoneSeat> = synchronized(lock) {
        seats.indices.mapNotNull { i ->
            seats[i]?.let { PhoneSeat(i, it.name, it.client != null, it.quality) }
        }
    }

    /** Players the Thor's bottom screen should show: the first players in the game that no phone
     *  has, at most [BuzzPad.SCREEN_MAX] of them. Anyone past that plays on a phone. */
    fun screenSlots(count: Int): IntArray {
        val phones = phoneSlots()
        return (0 until count.coerceAtMost(BuzzPad.PLAYERS)).filter { it !in phones }
            .take(BuzzPad.SCREEN_MAX).toIntArray()
    }

    /** The seat a phone gets when it does not ask for one: the first player in the game that is
     *  neither on a phone nor on the Thor's screen (players 5-8); failing that, the highest
     *  player on the screen, which the screen then drops. -1: every player is on a phone. */
    private fun suggestedSlot(): Int {
        val count = BuzzPad.playerCount().coerceAtMost(BuzzPad.PLAYERS)
        val phones = (0 until BuzzPad.PLAYERS).filter { seats[it] != null }.toSet()
        val screen = (0 until count).filter { it !in phones }.take(BuzzPad.SCREEN_MAX)
        return (0 until count).firstOrNull { it !in phones && it !in screen } ?: screen.lastOrNull() ?: -1
    }

    /** Seat [c] as [name], in [wanted] if that is free, otherwise the suggested seat. */
    private fun takeSeat(c: Client, wanted: Int, name: String, token: String): Int = synchronized(lock) {
        val count = BuzzPad.playerCount().coerceAtMost(BuzzPad.PLAYERS)
        val slot = wanted.takeIf { it in 0 until count && seats[it] == null } ?: suggestedSlot()
        if (slot < 0) return -1
        seats[slot] = Seat(token, name).also { it.client = c }
        c.slot = slot
        c.joined = true
        slot
    }

    /** A phone back after dropping off, with the token it was given: its seat, if still held. */
    private fun rejoin(c: Client, token: String): Int = synchronized(lock) {
        val slot = seats.indexOfFirst { it != null && it.token == token }
        if (slot < 0) return -1
        val seat = seats[slot]!!
        seat.client?.let { old -> runCatching { old.socket.close() } }
        seat.client = c
        seat.lostAt = 0L
        c.slot = slot
        c.joined = true
        slot
    }

    /** What the join form needs: how many players, which seats are taken, and a suggestion. */
    private fun helloJson(): String = synchronized(lock) {
        val count = BuzzPad.playerCount().coerceAtMost(BuzzPad.PLAYERS)
        val screen = screenSlots(count).toSet()
        val states = (0 until BuzzPad.PLAYERS).joinToString(",") { i ->
            val state = when {
                i >= count -> "off"
                seats[i] != null -> "phone"
                i in screen -> "thor"
                else -> "free"
            }
            "\"$state\""
        }
        val title = MainActivityRuntime.currentGame.value?.displayTitle(false).orEmpty()
        org.json.JSONObject()
            .put("t", "hello")
            .put("count", count)
            .put("suggest", suggestedSlot() + 1)
            .put("game", title)
            .toString()
            .dropLast(1) + ",\"seats\":[$states]}"
    }

    /**
     * Take a phone player off: back to the Thor's screen ([moved]) or out of the game. The phone
     * is told why and does not try to reconnect.
     */
    fun removePlayer(slot: Int, moved: Boolean) {
        val client = synchronized(lock) {
            val seat = seats.getOrNull(slot) ?: return
            seats[slot] = null
            seat.client
        }
        client?.let { c ->
            releaseHeld(c)
            c.slot = -1
            send(c, """{"t":"kicked","why":"${if (moved) "moved" else "removed"}"}""")
            runCatching { c.socket.close() }
        }
        notifyChanged()
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
        thread(name = "BuzzPhones-ticker", isDaemon = true) { tick(ss) }
        // buzz.local, for phones that would rather type an address than scan.
        runCatching { BuzzMdns.start(MainActivityRuntime.instance?.applicationContext) }
        main.post {
            BuzzLights.start()
            BuzzLights.addListener(lightsChanged)
        }
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
        BuzzMdns.stop()
        val all = synchronized(lock) {
            seats.fill(null)
            clients.toList()
        }
        all.forEach { runCatching { it.socket.close() } }
        notifyChanged()
    }

    /** Every [PING_MS]: measure each phone's round trip, and free seats held too long. */
    private fun tick(ss: ServerSocket) {
        while (!ss.isClosed) {
            runCatching { Thread.sleep(PING_MS) }
            val now = System.currentTimeMillis()
            var changed = false
            val live = synchronized(lock) {
                for (i in seats.indices) {
                    val seat = seats[i] ?: continue
                    if (seat.client == null && now - seat.lostAt > HOLD_SEAT_MS) {
                        seats[i] = null
                        changed = true
                    }
                }
                clients.filter { it.joined }
            }
            live.forEach { c ->
                send(c, """{"t":"ping","k":$now}""")
                // No answer to the last three pings: call it weak before the socket gives up.
                if (now - c.lastPong > PING_MS * 3) changed = setQuality(c, Quality.WEAK) || changed
            }
            if (changed) notifyChanged()
        }
    }

    /** True when it changed. */
    private fun setQuality(c: Client, q: Quality): Boolean = synchronized(lock) {
        val seat = seats.getOrNull(c.slot)?.takeIf { it.client === c } ?: return false
        if (seat.quality == q) return false
        seat.quality = q
        true
    }

    /** The game's buzzer lights, sent to each phone as its own buzzer goes on or off. */
    private val lightsChanged: (Int) -> Unit = { mask -> sendLights(mask) }

    private fun sendLights(mask: Int) {
        val seated = synchronized(lock) { clients.filter { it.joined && it.slot >= 0 } }
        seated.forEach { c ->
            val on = (mask shr c.slot) and 1 == 1
            thread(isDaemon = true) { send(c, """{"t":"light","on":$on}""") }
        }
    }

    /** Thor's force-colours toggle, mirrored on every phone. */
    fun setForce(on: Boolean) {
        force = on
        broadcast("""{"t":"force","on":$on}""")
    }

    // ---- Address --------------------------------------------------------------------------------

    /** The address phones should open, or null with no Wi-Fi or hotspot. */
    fun url(): String? = localIp()?.let { "http://$it:$PORT" }

    /** The Thor's address on Wi-Fi or its hotspot, or null with neither. */
    fun localIp(): String? {
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
        // The phone answers with "rejoin" (it has a seat token) or "join" (from the form).
        send(c, helloJson())

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

    /** "d<n>" presses button n (0 red, 1 blue, 2 orange, 3 green, 4 yellow), "u<n>" releases it.
     *  Anything else is a JSON message: join, rejoin or pong. */
    private fun handle(c: Client, msg: String) {
        if (msg.startsWith("{")) {
            handleJson(c, msg)
            return
        }
        if (msg.length != 2) return
        val button = msg[1] - '0'
        if (button !in 0..4 || c.slot < 0) return
        when (msg[0]) {
            'd' -> if (synchronized(c.held) { c.held.add(button) }) press(c.slot, button, true)
            'u' -> if (synchronized(c.held) { c.held.remove(button) }) press(c.slot, button, false)
        }
    }

    private fun handleJson(c: Client, msg: String) {
        val json = runCatching { org.json.JSONObject(msg) }.getOrNull() ?: return
        when (json.optString("t")) {
            "pong" -> {
                val now = System.currentTimeMillis()
                c.lastPong = now
                val rtt = now - json.optLong("k", now)
                val q = when {
                    rtt < 80 -> Quality.GOOD
                    rtt < 200 -> Quality.OK
                    else -> Quality.WEAK
                }
                send(c, """{"t":"sig","q":"${q.name.lowercase()}"}""")
                if (setQuality(c, q)) notifyChanged()
            }
            "rejoin" -> {
                val slot = rejoin(c, json.optString("token"))
                if (slot < 0) send(c, helloJson()) else seated(c, slot)
            }
            "join" -> {
                if (c.joined) return
                val name = json.optString("name").trim().take(16).ifBlank { "Player" }
                val token = json.optString("token").take(64).ifBlank { java.util.UUID.randomUUID().toString() }
                val slot = takeSeat(c, json.optInt("seat", 0) - 1, name, token)
                if (slot < 0) send(c, """{"t":"full"}""") else seated(c, slot)
            }
        }
    }

    private fun seated(c: Client, slot: Int) {
        val seat = synchronized(lock) { seats[slot] } ?: return
        val reply = org.json.JSONObject()
            .put("t", "slot")
            .put("p", slot + 1)
            .put("name", seat.name)
            .put("token", seat.token)
            .put("colour", BuzzPad.seatColourHex(slot))
        send(c, reply.toString())
        send(c, """{"t":"force","on":$force}""")
        send(c, """{"t":"light","on":${BuzzLights.isLit(slot)}}""")
        c.lastPong = System.currentTimeMillis()
        notifyChanged()
    }

    private fun press(slot: Int, button: Int, down: Boolean) = BuzzPad.pressSlot(slot, button, down)

    private fun releaseHeld(c: Client) {
        // Never leave a buzzer stuck down in the game because a phone locked or walked away.
        val held = synchronized(c.held) { c.held.toList().also { c.held.clear() } }
        if (c.slot >= 0) held.forEach { press(c.slot, it, false) }
    }

    private fun disconnect(c: Client) {
        releaseHeld(c)
        synchronized(lock) {
            clients -= c
            // Keep the seat for a minute, so a phone that dropped off can come straight back.
            val seat = seats.getOrNull(c.slot)
            if (seat != null && seat.client === c) {
                seat.client = null
                seat.lostAt = System.currentTimeMillis()
            }
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
                if (data.size < 126) {
                    c.out.write(data.size)
                } else {
                    c.out.write(126)
                    c.out.write(data.size shr 8 and 0xFF)
                    c.out.write(data.size and 0xFF)
                }
                c.out.write(data)
                c.out.flush()
            }
        }
    }
}
