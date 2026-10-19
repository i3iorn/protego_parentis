package dev.schrammel.protego_parentis.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.schrammel.protego_parentis.R
import dev.schrammel.protego_parentis.enforcement.RequestNotifier
import dev.schrammel.protego_parentis.model.ChatText
import dev.schrammel.protego_parentis.security.LocalDeviceId
import dev.schrammel.protego_parentis.sync.ChatMessage
import dev.schrammel.protego_parentis.sync.ChatStore
import dev.schrammel.protego_parentis.sync.RosterStore
import dev.schrammel.protego_parentis.sync.SyncCoordinator
import dev.schrammel.protego_parentis.sync.SyncGraph
import dev.schrammel.protego_parentis.sync.core.KnownDevice
import java.time.Duration
import java.time.Instant

/**
 * Messages between the phones in the family.
 *
 * Text. That is the whole feature, and the restraint is deliberate rather than a
 * first version waiting to grow: there is no attach button, no camera, no file
 * picker, no clipboard image handling and no link preview, because every one of
 * those is a way for one phone to hand another phone something to open. A chat
 * that can only ever produce characters in a `TextView` cannot deliver anything
 * else, whatever arrives on the wire.
 *
 * The two consequences of that decision worth spelling out:
 *
 *  - The input accepts text and emoji and refuses everything else, using the same
 *    [ChatText] rules the receiving side applies. A sender is shown what will
 *    actually be sent as they type rather than discovering later that half of it
 *    was dropped.
 *  - Messages are rendered with `text =`, never through any HTML or markup path.
 *    A message reading `<b>` shows those five characters.
 *
 * Not PIN-gated on either side. A parent needing a code to say "dinner is ready"
 * is friction with nothing behind it, and a child who cannot answer without one
 * has not been given a chat.
 *
 * While this screen is open it polls on its own short timer (#70) rather than
 * relying on the background cadence everything else in this app uses — a
 * parent's own `ParentPollWorker` runs on a 15-minute floor, and the managed
 * phone's tick can be minutes apart too, both far too slow for a conversation
 * somebody is actively looking at. That short timer only ever runs while this
 * Activity is the one on screen; it is not a new always-on channel, just this
 * screen refusing to wait for the next unrelated tick while somebody is here.
 */
class ChatActivity : DementorActivity() {
    override val showsBackButton = true

    // The one screen with something pinned to the bottom that has to stay above
    // the keyboard. See DementorActivity.movesForKeyboard.
    override val movesForKeyboard = true

    private lateinit var sync: SyncCoordinator
    private lateinit var store: ChatStore
    private lateinit var roster: RosterStore
    private lateinit var conversation: LinearLayout
    private lateinit var scroll: ScrollView
    /** Given `R.id.chat_composer_input` so rotation doesn't silently discard an unsent draft. */
    private lateinit var input: EditText
    private lateinit var composer: View
    private lateinit var status: TextView

    private val pollHandler = Handler(Looper.getMainLooper())

    /**
     * The message list as the screen last painted it. A poll tick that reloads
     * the same conversation must not rebuild every bubble — this is the
     * equality check that keeps an open screen from churning views.
     */
    private var renderedMessages: List<ChatMessage> = emptyList()

    private val pollTick = object : Runnable {
        override fun run() {
            sync.pollNow { runOnUiThread { render() } }
            pollHandler.postDelayed(this, LIVE_POLL_INTERVAL_MILLIS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sync = SyncGraph.get(this)
        store = ChatStore(this)
        roster = RosterStore(this)

        conversation = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dimen(R.dimen.screen_padding)
            setPadding(pad, dimen(R.dimen.space_sm), pad, dimen(R.dimen.space_md))
        }

        scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH, 0, 1f)
            isFillViewport = true
            addView(conversation, LinearLayout.LayoutParams(MATCH, WRAP))
        }

        status = statusLine()

        // The whole screen rather than a scrolling column: the composer has to stay
        // put at the bottom while the conversation moves behind it, which is the
        // one layout in this app that a single scroll view cannot give.
        setContentView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = ViewGroup.LayoutParams(MATCH, MATCH)
                composer = composer()
                addView(header())
                addView(status)
                addView(scroll)
                addView(composer)
            },
        )
    }

    override fun onResume() {
        super.onResume()
        // This phone is looking at the conversation now — a chat notification
        // for something already visible here would be telling somebody twice.
        // See RequestNotifier.notifyChat.
        isVisible = true
        RequestNotifier(this).clearChatNotification()
        render()
        // Opening the conversation is when somebody expects to be up to date,
        // and then the short timer keeps it that way for as long as the screen
        // stays open — see the class doc for why this screen alone does this.
        sync.pollNow { runOnUiThread { render() } }
        pollHandler.postDelayed(pollTick, LIVE_POLL_INTERVAL_MILLIS)
    }

    override fun onPause() {
        super.onPause()
        isVisible = false
        pollHandler.removeCallbacks(pollTick)
    }

    private fun header(): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
        val pad = dimen(R.dimen.screen_padding)
        setPadding(pad, dimen(R.dimen.space_sm), pad, 0)

        addView(
            screenTitle(getString(R.string.chat_title)).apply {
                layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
            },
        )
        addView(
            Button(context).apply {
                setText(R.string.chat_clear)
                setOnClickListener { confirmClear() }
            },
        )
    }

    /**
     * When everyone else in the family was last heard from (#70).
     *
     * Reads [RosterStore.observed]'s existing `lastHeardAt`, updated on any
     * accepted message from a device, not something new this screen has to
     * maintain itself. Not a read receipt and not a promise the other person
     * saw *this* conversation — only that their phone has talked to the
     * family at all recently, the same fact `ParentPollWorker`'s "gone quiet"
     * alert is already built on.
     */
    private fun statusLine(): TextView = captionText("").apply {
        val pad = dimen(R.dimen.screen_padding)
        setPadding(pad, 0, pad, dimen(R.dimen.space_xs))
    }

    private fun renderStatus(others: List<KnownDevice>) {
        val selfId = LocalDeviceId.current()
        val visible = others.filter { it.id.value != selfId }
        status.visibility = if (visible.isEmpty()) View.GONE else View.VISIBLE
        status.text = visible
            .sortedBy { it.name.lowercase() }
            .joinToString(STATUS_SEPARATOR) { statusFor(it) }
    }

    private fun statusFor(device: KnownDevice): String {
        val heard = device.lastHeardAt ?: return getString(R.string.chat_status_never, device.name)
        val since = Duration.between(heard, Instant.now())
        return when {
            since <= ONLINE_WITHIN -> getString(R.string.chat_status_online, device.name)
            since.toDays() >= 1 -> getString(R.string.chat_status_days_ago, device.name, since.toDays())
            since.toHours() >= 1 -> getString(R.string.chat_status_hours_ago, device.name, since.toHours())
            else -> getString(R.string.chat_status_minutes_ago, device.name, since.toMinutes().coerceAtLeast(1))
        }
    }

    private fun composer(): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.BOTTOM
        layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
        val pad = dimen(R.dimen.screen_padding)
        setPadding(pad, dimen(R.dimen.space_sm), pad, dimen(R.dimen.space_md))

        input = EditText(context).apply {
            id = R.id.chat_composer_input
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
            hint = getString(R.string.chat_hint)
            // TYPE_TEXT_FLAG_MULTI_LINE, and nothing that invites anything else.
            // Notably not any of the autofill or content-type hints: this field is
            // for words, and asking the system to help fill it in would be asking
            // for the wrong kind of help.
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            maxLines = COMPOSER_MAX_LINES
            // Two filters rather than one. The length cap stops a paste from
            // silently losing its tail somewhere later; the character filter is the
            // same rule the far end applies, so what is typed is what is sent.
            filters = arrayOf(
                InputFilter.LengthFilter(ChatText.MAX_LENGTH),
                textAndEmojiOnly(),
            )
        }
        addView(input)

        addView(
            Button(context).apply {
                setText(R.string.chat_send)
                (layoutParams as? LinearLayout.LayoutParams)?.marginStart =
                    dimen(R.dimen.space_sm)
                setOnClickListener { send() }
            },
        )
    }

    /**
     * Refuses, as it is typed or pasted, anything the far end would drop.
     *
     * An [InputFilter] rather than a check on send. A paste carrying a direction
     * override or a private-use character loses it at the moment it lands, so the
     * box never holds text that will not survive the trip — the alternative is a
     * sender who writes a message, sends it, and watches it come out different.
     *
     * [ChatText.keepCharacters] and not [ChatText.sanitise]: the character rule
     * applies to a keystroke, the tidying only to a finished message. Sanitising
     * here would reject the space bar, because a lone space tidies away to nothing
     * and the field would refuse every space as it was typed.
     *
     * Returning null means "accept this insert unchanged" and is the answer for
     * every ordinary keystroke, which matters — a filter that hands back a new
     * string on each key can disturb an IME mid-composition, and predictive text
     * and the emoji picker are exactly the input methods this field has to get
     * along with.
     */
    private fun textAndEmojiOnly() = InputFilter { source, start, end, _, _, _ ->
        val candidate = source.subSequence(start, end).toString()
        val allowed = ChatText.keepCharacters(candidate)
        if (allowed == candidate) null else allowed
    }

    private fun send() {
        val typed = input.text.toString()
        // Sanitised again inside the coordinator; this call is about whether there
        // is anything to send at all.
        if (ChatText.sanitise(typed) == null) return

        sync.publishChat(typed)
        input.setText("")
        // The optimistic render: force the next refresh to rebuild even if the
        // store has not received the sent message through replication yet.
        renderedMessages = emptyList()
        render()
    }

    /**
     * Reloads the conversation and roster off the main thread, then repaints.
     *
     * [store.all] and [roster.devices] are preference files, and this used to read
     * them on the poll tick — disk I/O on the main thread every few seconds for as
     * long as the screen is open, plus a full rebuild of every bubble whether or
     * not anything had changed. The read happens on Background's executor; only
     * the view work runs on the main thread, and only when something changed.
     */
    private fun render() {
        Background.load(
            this,
            {
                val messages = store.all()
                val connected = sync.settings().isUsable
                val others = if (connected) roster.devices else emptyList()
                Render(messages, others, connected)
            },
        ) { data ->
            composer.visibility = if (data.connected) View.VISIBLE else View.GONE
            if (data.connected) renderStatus(data.others) else status.visibility = View.GONE
            renderConversation(data.messages, data.connected)
        }
    }

    /** One shot of what the conversation screens need, computed off the main thread. */
    private data class Render(
        val messages: List<ChatMessage>,
        val others: List<KnownDevice>,
        val connected: Boolean,
    )

    /**
     * The conversation. Rebuilds the bubbles only when the message list actually
     * changed — a poll tick that fetched nothing new leaves the views alone, so a
     * screen that sits open does not churn view construction for nothing.
     */
    private fun renderConversation(messages: List<ChatMessage>, connected: Boolean) {
        if (messages == renderedMessages) return
        renderedMessages = messages

        conversation.removeAllViews()
        if (!connected) conversation.addView(captionText(getString(R.string.chat_no_family)))

        if (messages.isEmpty()) {
            if (connected) conversation.addView(captionText(getString(R.string.chat_empty)))
            return
        }

        messages.forEach { conversation.addView(bubble(it)) }
        // After layout, or the scroll view does not yet know how tall it became.
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    /**
     * One message.
     *
     * Sent and received messages differ by alignment and by which surface they sit
     * on, not by colour alone — the difference has to survive being read by someone
     * who does not distinguish the two colours.
     */
    private fun bubble(message: ChatMessage): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply {
            topMargin = dimen(R.dimen.space_sm)
        }
        gravity = if (message.mine) Gravity.END else Gravity.START

        addView(
            TextView(context).apply {
                // Who and when, above the words. The name is the roster's, and has
                // been through the sanitiser — see SyncCoordinator.rememberChat.
                text = getString(
                    R.string.chat_attribution,
                    if (message.mine) getString(R.string.chat_me) else message.sender,
                    formatTime(message.at),
                )
                styleAs(R.style.TextAppearance_Dementor_Caption)
            },
        )
        addView(
            card {
                addView(
                    TextView(this@ChatActivity).apply {
                        // Plain assignment. Not Html.fromHtml, not a Spanned, not
                        // anything that would treat the message as instructions.
                        text = message.text
                        styleAs(R.style.TextAppearance_Dementor_Body)
                    },
                )
                (layoutParams as? LinearLayout.LayoutParams)?.apply {
                    topMargin = dimen(R.dimen.space_xxs)
                    // Never the full width, so the two sides read as two sides.
                    marginStart = if (message.mine) dimen(R.dimen.chat_bubble_inset) else 0
                    marginEnd = if (message.mine) 0 else dimen(R.dimen.chat_bubble_inset)
                }
            },
        )
    }

    /**
     * Clearing is offered to whoever is holding the phone, child included.
     *
     * It only clears this device. There is no "delete for everyone" and there is
     * not going to be: a parent who could reach into a child's phone and remove
     * what was said turns a conversation into something the child cannot rely on.
     */
    private fun confirmClear() {
        confirmDialog(R.string.chat_clear, R.string.chat_clear_confirm, R.string.chat_clear) {
            store.forgetAll()
            render()
        }
    }

    companion object {

        /** The box grows to this and then scrolls, rather than eating the screen. */
        private const val COMPOSER_MAX_LINES = 5

        /**
         * How often this screen polls on its own while it is the one open (#70).
         *
         * Well under the child device's own enforcement-tick floor (15s) and far
         * under a parent's 15-minute `ParentPollWorker` window — deliberately so,
         * since this only ever runs while somebody is looking at the screen, not
         * as a background habit the rest of the app would then also pay for.
         */
        private const val LIVE_POLL_INTERVAL_MILLIS = 8_000L

        /** Below this, a device reads as "online" rather than "last seen…". */
        private val ONLINE_WITHIN: Duration = Duration.ofMinutes(2)

        private const val STATUS_SEPARATOR = "  ·  "

        /**
         * Whether this screen is the one currently on top.
         *
         * Read by [RequestNotifier.notifyChat] so a message arriving while
         * somebody is already watching it land in the transcript below does not
         * also buzz the phone about the same thing a second time.
         */
        @Volatile
        var isVisible: Boolean = false
            private set

        fun intent(context: Context) = Intent(context, ChatActivity::class.java)
    }
}
