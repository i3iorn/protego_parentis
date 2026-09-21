package dev.schrammel.protego_parentis.sync

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import dev.schrammel.protego_parentis.sync.core.PairingWire
import java.io.BufferedReader
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "LanPairing"

/** How long either side waits on a socket before giving up. */
private const val SOCKET_TIMEOUT_MILLIS = 15_000

/** A phone that is offering to pass on its family, as the finder sees it. */
data class NearbyPhone(val name: String, val host: InetAddress, val port: Int)

/**
 * Handing a family token to a phone on the same network.
 *
 * The reasoning about what may and may not cross the network lives in
 * [PairingWire]; this is the machinery that carries it out. Two halves that never
 * run at the same time on the same device: [Offer] on a phone already in a family,
 * [Finder] on the phone that wants in.
 *
 * Both are tied to a screen being open. Nothing here starts on its own, nothing
 * survives the screen closing, and nothing is left listening — a pairing service
 * that outlives the moment somebody asked for it is the failure this whole design
 * is arranged to avoid.
 *
 * None of this can be tested off-device: it needs two phones, one network, and
 * mDNS actually working on it. The parts that could be made testable — the
 * protocol, the parsing, the refusals — were pushed into [PairingWire], which is
 * pure and has tests. What is left here is Android API plumbing, and it is written
 * to be obvious rather than clever for that reason.
 */
object LanPairing {

    /**
     * Offers this phone's family to whoever asks, until told to stop.
     *
     * Opens a socket on an ephemeral port, advertises that the port exists — and
     * nothing else — then waits. Each connection is read, and the code it sent is
     * handed to [onRequest]; the answer comes back through the callback that
     * [onRequest] is given, so the decision is a person's, made on screen, while
     * the socket is held open.
     */
    class Offer(
        private val context: Context,
        private val onRequest: (code: String, decide: (Boolean) -> Unit) -> Unit,
        private val onStopped: (reason: String?) -> Unit,
    ) {
        private val nsd = context.applicationContext.getSystemService(NsdManager::class.java)
        private val io = Executors.newCachedThreadPool()
        private val running = AtomicBoolean(false)

        private var server: ServerSocket? = null
        private var registration: NsdManager.RegistrationListener? = null

        /**
         * True while somebody is being asked about a connection.
         *
         * Anything that arrives meanwhile is refused without a prompt. Without
         * this, whatever is on the network decides how many dialogs the person
         * holding the phone has to read, and the real request is the one buried in
         * the middle of them.
         */
        private val deciding = AtomicBoolean(false)

        fun start() {
            if (nsd == null) {
                onStopped("no discovery service")
                return
            }
            if (!running.compareAndSet(false, true)) return

            val socket = runCatching { ServerSocket(0) }.getOrElse {
                Log.w(TAG, "Could not open a pairing socket", it)
                running.set(false)
                onStopped("no socket")
                return
            }
            socket.soTimeout = SOCKET_TIMEOUT_MILLIS
            server = socket

            val info = NsdServiceInfo().apply {
                // A name Android may rename on a collision, which is fine: the
                // finder shows whatever it resolves and the code is what actually
                // identifies the phone.
                serviceName = "Dementor Watch"
                serviceType = PairingWire.SERVICE_TYPE
                port = socket.localPort
                // No attributes. The token is deliberately not here — see the
                // note on PairingWire.
            }

            val listener = object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(info: NsdServiceInfo) = Unit
                override fun onServiceUnregistered(info: NsdServiceInfo) = Unit

                override fun onRegistrationFailed(info: NsdServiceInfo, code: Int) {
                    Log.w(TAG, "Could not advertise: $code")
                    stop("advertising failed")
                }

                override fun onUnregistrationFailed(info: NsdServiceInfo, code: Int) {
                    Log.w(TAG, "Could not stop advertising: $code")
                }
            }
            registration = listener
            runCatching { nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener) }
                .onFailure {
                    Log.w(TAG, "Could not advertise", it)
                    stop("advertising failed")
                    return
                }

            io.execute { accept(socket) }
        }

        private fun accept(socket: ServerSocket) {
            while (running.get()) {
                val client = runCatching { socket.accept() }.getOrElse { error ->
                    // A timeout is the normal way round this loop: it is what lets
                    // a stopped offer notice it has been stopped.
                    if (error is IOException && running.get()) continue else return
                }
                io.execute { serve(client) }
            }
        }

        /**
         * One conversation, held open while a person decides.
         *
         * The socket stays open across the decision on purpose. Closing it and
         * reconnecting would mean the phone that gets the token is not necessarily
         * the phone that showed the code.
         */
        private fun serve(client: Socket) {
            client.use { connection ->
                connection.soTimeout = SOCKET_TIMEOUT_MILLIS
                val reader = connection.getInputStream().bufferedReader()
                val writer = connection.getOutputStream().bufferedWriter()

                val line = runCatching { readLine(reader) }.getOrNull() ?: return
                val code = PairingWire.readHello(line)
                if (code == null) {
                    // Something that is not us found the port. Say nothing at all
                    // rather than answering with a protocol error, which would
                    // confirm to a scanner that this is worth another look.
                    return
                }

                if (!deciding.compareAndSet(false, true)) {
                    runCatching {
                        writer.write(PairingWire.deny())
                        writer.newLine()
                        writer.flush()
                    }
                    return
                }

                val answered = AtomicBoolean(false)
                onRequest(code) { allowed ->
                    if (!answered.compareAndSet(false, true)) return@onRequest
                    // decide() is called from whatever thread the caller answers
                    // on — for the real UI, an AlertDialog button, i.e. the main
                    // thread. The reply is a disk read plus a blocking socket
                    // write, so it must never run on the thread that invoked us;
                    // io.execute puts it back on this Offer's own worker instead.
                    // Rejected only if stop() already shut the executor down
                    // (the person backed out as they answered), in which case
                    // there is nobody left to write the reply to anyway.
                    runCatching {
                        io.execute {
                            val token = SyncConfigStore(context).load().token()
                            val reply = if (allowed && token != null) {
                                PairingWire.grant(token)
                            } else {
                                PairingWire.deny()
                            }
                            runCatching {
                                writer.write(reply)
                                writer.newLine()
                                writer.flush()
                            }
                        }
                    }
                }

                // Held until the decision arrives or the socket times out. Without
                // this the method returns, `use` closes the connection, and the
                // reply written a moment later goes nowhere.
                val deadline = System.currentTimeMillis() + SOCKET_TIMEOUT_MILLIS
                try {
                    while (
                        !answered.get() &&
                        System.currentTimeMillis() < deadline &&
                        running.get()
                    ) {
                        Thread.sleep(POLL_MILLIS)
                    }
                } catch (interrupted: InterruptedException) {
                    // stop() interrupts these threads. Expected, and the only
                    // right response is to drop the connection — which `use`
                    // does on the way out. Left uncaught it surfaces as a crash
                    // report for an offer somebody simply closed.
                    Log.d(TAG, "Pairing connection dropped while stopping", interrupted)
                    Thread.currentThread().interrupt()
                } finally {
                    deciding.set(false)
                }
            }
        }

        fun stop(reason: String? = null) {
            if (!running.compareAndSet(true, false)) return

            registration?.let { listener ->
                runCatching { nsd?.unregisterService(listener) }
            }
            registration = null
            runCatching { server?.close() }
            server = null
            io.shutdownNow()
            onStopped(reason)
        }
    }

    /**
     * Looks for phones offering a family, and asks one for its token.
     *
     * Discovery runs only while the finding screen is open. What it finds is a
     * name and an address; nothing about a family is learned until [request] is
     * called and a person on the other phone agrees.
     */
    class Finder(
        context: Context,
        private val onFound: (NearbyPhone) -> Unit,
        private val onStopped: (reason: String?) -> Unit,
    ) {
        private val nsd = context.applicationContext.getSystemService(NsdManager::class.java)
        private val io = Executors.newCachedThreadPool()
        private val running = AtomicBoolean(false)

        private var discovery: NsdManager.DiscoveryListener? = null

        fun start() {
            if (nsd == null) {
                onStopped("no discovery service")
                return
            }
            if (!running.compareAndSet(false, true)) return

            val listener = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(type: String) = Unit
                override fun onDiscoveryStopped(type: String) = Unit

                override fun onStartDiscoveryFailed(type: String, code: Int) {
                    Log.w(TAG, "Could not look for phones: $code")
                    stop("discovery failed")
                }

                override fun onStopDiscoveryFailed(type: String, code: Int) = Unit

                override fun onServiceFound(info: NsdServiceInfo) {
                    if (!running.get()) return
                    resolve(info)
                }

                override fun onServiceLost(info: NsdServiceInfo) = Unit
            }
            discovery = listener

            runCatching {
                nsd.discoverServices(PairingWire.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
            }.onFailure {
                Log.w(TAG, "Could not look for phones", it)
                stop("discovery failed")
            }
        }

        @Suppress("DEPRECATION")
        private fun resolve(info: NsdServiceInfo) {
            // The callback-based resolve is deprecated in favour of a newer
            // registration API that is not available across the range this app
            // supports. There is no non-deprecated equivalent that works on
            // minSdk 33, and dropping resolution would leave a name with no
            // address, which is nothing.
            nsd?.resolveService(
                info,
                object : NsdManager.ResolveListener {
                    override fun onResolveFailed(info: NsdServiceInfo, code: Int) {
                        Log.w(TAG, "Could not resolve ${info.serviceName}: $code")
                    }

                    override fun onServiceResolved(resolved: NsdServiceInfo) {
                        if (!running.get()) return
                        val host = resolved.host ?: return
                        onFound(NearbyPhone(resolved.serviceName, host, resolved.port))
                    }
                },
            )
        }

        /**
         * Asks [phone] for its family, showing [code] so a person can check.
         *
         * Everything about the answer is decided by [PairingWire.readAnswer] —
         * including whether the token is one this build would ever accept. A
         * refusal, a timeout, a stranger on the port and a malformed token all end
         * up in the same place, because there is nothing useful a caller could do
         * differently with any of them.
         */
        fun request(phone: NearbyPhone, code: String, onAnswer: (PairingWire.Answer) -> Unit) {
            io.execute {
                val answer = runCatching {
                    Socket().use { socket ->
                        socket.connect(
                            java.net.InetSocketAddress(phone.host, phone.port),
                            SOCKET_TIMEOUT_MILLIS,
                        )
                        socket.soTimeout = SOCKET_TIMEOUT_MILLIS

                        val writer = socket.getOutputStream().bufferedWriter()
                        writer.write(PairingWire.hello(code))
                        writer.newLine()
                        writer.flush()

                        PairingWire.readAnswer(
                            readLine(socket.getInputStream().bufferedReader()).orEmpty(),
                        )
                    }
                }.getOrElse { error ->
                    // Not phone.name: a user-chosen device name has no place
                    // in a log line, the same rule Diagnostics.kt states for
                    // its own content.
                    Log.w(TAG, "Pairing with a nearby phone failed", error)
                    PairingWire.Answer.Unreadable
                }
                onAnswer(answer)
            }
        }

        fun stop(reason: String? = null) {
            if (!running.compareAndSet(true, false)) return
            discovery?.let { listener -> runCatching { nsd?.stopServiceDiscovery(listener) } }
            discovery = null
            io.shutdownNow()
            onStopped(reason)
        }
    }

    private const val POLL_MILLIS = 100L

    /**
     * One line, and never more than [PairingWire.MAX_LINE] characters of it.
     *
     * `BufferedReader.readLine` will read until a newline arrives or memory runs
     * out, whichever comes first. On a socket anything on the network can open,
     * that is a way to bring the phone down from across the room.
     */
    private fun readLine(reader: BufferedReader): String? {
        val builder = StringBuilder()
        while (builder.length <= PairingWire.MAX_LINE) {
            val next = reader.read()
            if (next < 0) return builder.toString().ifEmpty { null }
            if (next == '\n'.code) return builder.toString()
            if (next != '\r'.code) builder.append(next.toChar())
        }
        return null
    }
}
