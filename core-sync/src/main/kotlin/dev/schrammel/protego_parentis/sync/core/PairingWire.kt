package dev.schrammel.protego_parentis.sync.core

import java.security.SecureRandom

/**
 * The two lines a phone says to another phone on the same network while pairing.
 *
 * ## What crosses the network, and what does not
 *
 * The **passphrase never does**, at any point, under any circumstances. It is
 * sealed in the keystore, no screen can display it, and nothing here can reach it.
 * That is unchanged by this feature and is the reason the feature is acceptable at
 * all: everything below trades in the family token, which is the half that was
 * always meant to be passed to a person — read aloud, texted, or held up as a QR
 * code.
 *
 * The token still matters, though, and this design is shaped by exactly how much.
 * Knowing a family's topic does not let anybody read a message: every payload is
 * encrypted, and every sender is checked against the roster. What it does allow is
 * subscribing to the relay to watch the *shape* of a family's traffic, and
 * publishing rubbish into it. The second is the one that bites — a topic somebody
 * is flooding is a topic where a request, or an emergency, can be pushed out of
 * the relay's retention window. So the token is a thing to hand to one phone
 * deliberately, not to announce.
 *
 * ## Which is why discovery announces nothing
 *
 * The obvious implementation puts the token straight into the mDNS TXT record.
 * That is one line of code and it publishes the family's topic to everyone on the
 * network — the guest, the flatmate, the neighbour who has the Wi-Fi password,
 * anyone at all on a café network. Passively, with no interaction and no trace.
 *
 * So the advertisement carries a version number and nothing else. A phone that
 * wants the token opens a connection, shows a code on its screen, and gets
 * nothing until a person on the other phone has compared that code with what is
 * in their hand and said yes.
 *
 * ## What this does not defend against
 *
 * The token travels over a plain socket on the local network. Somebody who can
 * capture packets on that network during the few seconds of a confirmed pairing
 * can read it. That is a much smaller exposure than announcing it — it needs
 * capture rather than a browse, and a window measured in seconds rather than
 * standing — but it is not nothing, and the screen says so rather than implying a
 * guarantee this does not make.
 */
object PairingWire {

    /** Version marker. A phone that does not recognise it says nothing back. */
    const val PROTOCOL = "DWP1"

    /**
     * The service type advertised over mDNS.
     *
     * Deliberately not the app's name. This is visible to anything browsing the
     * network, and while a pairing that is running is not a secret, there is no
     * reason for it to announce which parental-control app a household uses. It
     * is not obscurity standing in for a defence — the defence is that nothing
     * sensitive is in the record — it is simply not volunteering.
     */
    const val SERVICE_TYPE = "_dwpair._tcp."

    /**
     * How long an offer stands before it stops on its own.
     *
     * Bounded because a forgotten advertisement is the failure this design exists
     * to avoid. Long enough to walk across a room, short enough that leaving the
     * screen open by accident does not leave a phone offering to pair all evening.
     */
    const val OFFER_SECONDS = 120L

    /** Digits in the code compared between the two screens. */
    const val CODE_LENGTH = 6

    /**
     * The longest line either side will read.
     *
     * A pairing partner is unauthenticated by definition — anything on the network
     * can connect. Without a cap, one of them can hand over an endless line and
     * the phone reading it runs out of memory.
     */
    const val MAX_LINE = 512

    private const val HELLO = "HELLO"
    private const val TOKEN = "TOKEN"
    private const val DENY = "DENY"

    private val CODE_PATTERN = Regex("^[0-9]{$CODE_LENGTH}$")

    /**
     * A fresh code for the joining phone to show.
     *
     * Not a secret and not doing any cryptographic work: it exists so a person can
     * tell *this* phone from another one that happens to be pairing on the same
     * network at the same moment. Six digits is plenty for that and short enough
     * to compare at a glance.
     */
    fun code(random: SecureRandom = SecureRandom()): String =
        (1..CODE_LENGTH).joinToString("") { random.nextInt(10).toString() }

    // ---------- what the joining phone sends ----------

    fun hello(code: String): String {
        require(CODE_PATTERN.matches(code)) { "not a pairing code: '$code'" }
        return "$PROTOCOL $HELLO $code"
    }

    /**
     * Reads a hello, or null.
     *
     * Null for everything that is not exactly one: another app on the port, a port
     * scanner, a newer version of this protocol, a mangled line. The caller shows
     * nothing and waits for the next connection — there is no version of "nearly a
     * hello" worth acting on.
     */
    fun readHello(line: String): String? {
        val parts = line.trim().split(" ")
        if (parts.size != 3 || parts[0] != PROTOCOL || parts[1] != HELLO) return null
        return parts[2].takeIf(CODE_PATTERN::matches)
    }

    // ---------- what the offering phone sends back ----------

    fun grant(token: FamilyToken): String = "$PROTOCOL $TOKEN ${FamilyTokens.encode(token)}"

    fun deny(): String = "$PROTOCOL $DENY"

    /** What came back. */
    sealed interface Answer {
        /** The offer was accepted and here is the family. */
        data class Granted(val token: FamilyToken) : Answer

        /** The person on the other phone said no, or it timed out. */
        data object Denied : Answer

        /** Not one of ours, or damaged. Treated exactly like a refusal. */
        data object Unreadable : Answer
    }

    /**
     * Reads the answer.
     *
     * The token is decoded rather than trusted: it arrived over an unauthenticated
     * socket from something that answered on the right port, and
     * [FamilyTokens.decode] is what enforces the checksum, the known transport and
     * — the one that matters here — the rule that a family server must be https.
     * Without that a phone on the network could hand back a token pointing at a
     * plaintext server of its choosing.
     */
    fun readAnswer(line: String): Answer {
        val parts = line.trim().split(" ")
        if (parts.isEmpty() || parts[0] != PROTOCOL) return Answer.Unreadable

        return when {
            parts.size == 2 && parts[1] == DENY -> Answer.Denied
            parts.size == 3 && parts[1] == TOKEN ->
                FamilyTokens.decode(parts[2])?.let(Answer::Granted) ?: Answer.Unreadable

            else -> Answer.Unreadable
        }
    }
}
