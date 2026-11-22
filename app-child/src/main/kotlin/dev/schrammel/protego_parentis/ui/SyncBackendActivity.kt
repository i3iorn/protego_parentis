package dev.schrammel.protego_parentis.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import dev.schrammel.protego_parentis.R
import dev.schrammel.protego_parentis.audit.Audit
import dev.schrammel.protego_parentis.model.AuditAction
import dev.schrammel.protego_parentis.model.AuditActor
import dev.schrammel.protego_parentis.sync.RealtimeSyncService
import dev.schrammel.protego_parentis.sync.SyncConfigStore
import dev.schrammel.protego_parentis.sync.SyncCoordinator
import dev.schrammel.protego_parentis.sync.SyncGraph
import dev.schrammel.protego_parentis.sync.core.FamilyToken
import dev.schrammel.protego_parentis.sync.core.FamilyTokens
import dev.schrammel.protego_parentis.sync.core.TransportKind
import dev.schrammel.protego_parentis.sync.LastServerResponse
import dev.schrammel.protego_parentis.sync.NtfyCredentials

/**
 * Where the family's messages are carried.
 *
 * The default sends them through ntfy.sh, a public relay. It cannot read a word of
 * them — everything is sealed and signed before it leaves the phone — but it does
 * see that a topic exists, roughly how large its messages are and when they move.
 * A family who would rather not hand a stranger even that can point this at their
 * own server, and this screen is where.
 *
 * The awkward part, and the reason for the confirmation: **the whole family has to
 * move together.** A phone left on the old server is not broken in any way it can
 * notice — it goes on publishing to a topic nobody is reading and hearing nothing
 * back, which looks exactly like a quiet week. So changing this produces a new
 * family code, and the screen says plainly that every other phone needs it.
 *
 * The topic and the passphrase do not change. They are the family's identity;
 * this is only its address.
 */
class SyncBackendActivity : ParentGatedActivity() {

    private lateinit var config: SyncConfigStore
    private lateinit var sync: SyncCoordinator
    private lateinit var column: LinearLayout
    private lateinit var serverInput: EditText
    private lateinit var topicInput: EditText
    private var chosen: TransportKind = TransportKind.NTFY

    /** Null until a test has been run in this visit. */
    private var testResult: String? = null

    /** Whether the last test — typed or stored credentials — reached the relay. */
    private var lastReachable: Boolean? = null

    /** Auth type selector and inputs (persisted across renders). */
    private lateinit var authTypeRadioGroup: RadioGroup
    private lateinit var tokenInput: EditText
    private lateinit var usernameInput: EditText
    private lateinit var passwordInput: EditText

    /** The auth mode currently selected on screen: [AUTH_NONE], [AUTH_TOKEN] or [AUTH_BASIC]. */
    private var authChoice: String = AUTH_NONE

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        config = SyncConfigStore(this)
        sync = SyncGraph.get(this)

        val settings = config.load()
        chosen = settings.transport
        serverInput = EditText(this).apply {
            hint = getString(R.string.backend_server_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setText(settings.serverUrl)
        }
        topicInput = EditText(this).apply {
            hint = getString(R.string.backend_topic_hint)
            inputType = InputType.TYPE_CLASS_TEXT
            setText(settings.topic)
        }

        tokenInput = EditText(this).apply {
            hint = getString(R.string.backend_token_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText((settings.credentials as? NtfyCredentials.Token)?.token ?: "")
        }
        usernameInput = EditText(this).apply {
            hint = getString(R.string.backend_username_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            setText((settings.credentials as? NtfyCredentials.Basic)?.username ?: "")
        }
        passwordInput = EditText(this).apply {
            hint = getString(R.string.backend_password_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText((settings.credentials as? NtfyCredentials.Basic)?.password ?: "")
        }

        column = contentColumn()
        showColumn(column)
    }

    override fun onGatedContentVisible() = render()

    override fun onGatedContentHidden() {
        column.removeAllViews()
    }

    /**
     * The family's shape is the child's business too: who is in it, what it is
     * called, where it meets. A managed phone renders all of it read-only and
     * asks for the PIN only when something is about to change.
     */
    override fun supportsReadOnly() = true

    private fun render() {
        column.removeAllViews()
        column.addView(screenTitle(getString(R.string.backend_title)))
        column.addView(bodyText(getString(R.string.backend_intro)))
        if (!canEdit) {
            column.addView(readOnlyBanner { requestUnlock { onGatedContentVisible() } })
        }

        val settings = config.load()
        if (!settings.isUsable) {
            // Nothing to move. Changing the address of a family this phone has not
            // joined would write settings that the next join overwrites anyway.
            column.addView(bodyText(getString(R.string.backend_not_connected)))
            return
        }

        // Read-only: say where the family meets and how it authenticates, but
        // show no field a credential is typed into — the inputs below are
        // pre-filled with the stored secrets, and a read-only visit must not
        // put those on screen.
        if (!canEdit) {
            column.addView(card {
                addView(statRow(getString(R.string.backend_kind_header), labelFor(chosen)))
                addView(statRow(getString(R.string.backend_server_header), settings.serverUrl))
                addView(statRow(getString(R.string.backend_topic_header), settings.topic))
                addView(
                    statRow(
                        getString(R.string.backend_auth_header),
                        getString(
                            when (settings.credentials) {
                                is NtfyCredentials.Token -> R.string.backend_auth_token
                                is NtfyCredentials.Basic -> R.string.backend_auth_basic
                                is NtfyCredentials.None -> R.string.backend_auth_none
                            },
                        ),
                    ),
                )
            })
            return
        }

        column.addView(sectionHeader(getString(R.string.backend_kind_header)))
        column.addView(card { addView(kinds()) })

        column.addView(sectionHeader(getString(R.string.backend_server_header)))
        column.addView(
            card {
                // The input is a field that outlives the render, so it keeps a
                // reference to the card it was in last time. Clearing the column
                // used to be enough when it sat there directly; now that it is a
                // level deeper, the old parent has to be told.
                (serverInput.parent as? ViewGroup)?.removeView(serverInput)
                addView(serverInput)
                addView(captionText(getString(R.string.backend_server_hint_detail)))
            },
        )

        column.addView(sectionHeader(getString(R.string.backend_topic_header)))
        column.addView(
            card {
                (topicInput.parent as? ViewGroup)?.removeView(topicInput)
                addView(topicInput)
                addView(captionText(getString(R.string.backend_topic_detail)))
            },
        )
        column.addView(
            actionButton().apply {
                setText(R.string.backend_suggest_topic)
                setOnClickListener { topicInput.setText(config.suggestTopic()) }
            },
        )

        // Authentication section
        val credentials = settings.credentials
        column.addView(sectionHeader(getString(R.string.backend_auth_header)))
        column.addView(
            card {
                authTypeRadioGroup = RadioGroup(this@SyncBackendActivity).apply {
                    orientation = RadioGroup.VERTICAL
                }
                val types = listOf(
                    AUTH_NONE to R.string.backend_auth_none,
                    AUTH_TOKEN to R.string.backend_auth_token,
                    AUTH_BASIC to R.string.backend_auth_basic,
                )
                authChoice = when (credentials) {
                    is NtfyCredentials.Token -> AUTH_TOKEN
                    is NtfyCredentials.Basic -> AUTH_BASIC
                    is NtfyCredentials.None -> AUTH_NONE
                }
                types.forEachIndexed { index, (key, label) ->
                    authTypeRadioGroup.addView(
                        RadioButton(this@SyncBackendActivity).apply {
                            id = index + 1
                            text = getString(label)
                            isChecked = key == authChoice
                            setOnCheckedChangeListener { _, checkedNow ->
                                if (checkedNow) {
                                    authChoice = key
                                    // Show/hide input fields
                                    tokenInput.visibility = if (key == AUTH_TOKEN) android.view.View.VISIBLE else android.view.View.GONE
                                    val showBasic = key == AUTH_BASIC
                                    usernameInput.visibility = if (showBasic) android.view.View.VISIBLE else android.view.View.GONE
                                    passwordInput.visibility = if (showBasic) android.view.View.VISIBLE else android.view.View.GONE
                                }
                            }
                        },
                    )
                }
                addView(authTypeRadioGroup)

                // Show/hide inputs based on current selection
                tokenInput.visibility = if (authChoice == AUTH_TOKEN) android.view.View.VISIBLE else android.view.View.GONE
                val showBasic = authChoice == AUTH_BASIC
                usernameInput.visibility = if (showBasic) android.view.View.VISIBLE else android.view.View.GONE
                passwordInput.visibility = if (showBasic) android.view.View.VISIBLE else android.view.View.GONE

                // Remove from old parent if needed
                (tokenInput.parent as? ViewGroup)?.removeView(tokenInput)
                (usernameInput.parent as? ViewGroup)?.removeView(usernameInput)
                (passwordInput.parent as? ViewGroup)?.removeView(passwordInput)

                addView(tokenInput)
                addView(captionText(getString(R.string.backend_token_detail)))
                addView(usernameInput)
                addView(passwordInput)

                // The box itself says what the last test found: green when the
                // relay answered with these credentials, red when it did not.
                // Untouched until something was tested — an untested box says
                // nothing rather than guessing.
                lastReachable?.let { reachable ->
                    val color = getColor(if (reachable) R.color.state_ok else R.color.state_critical)
                    addView(
                        View(this@SyncBackendActivity).apply {
                            setBackgroundColor(color)
                            layoutParams = LinearLayout.LayoutParams(MATCH, dimen(R.dimen.space_xs))
                                .apply { topMargin = dimen(R.dimen.space_sm) }
                        },
                    )
                    addView(
                        captionText(
                            getString(
                                if (reachable) R.string.backend_auth_reachable
                                else R.string.backend_auth_unreachable,
                            ),
                        ).apply { setTextColor(color) },
                    )
                }
            },
        )

        column.addView(
            actionButton().apply {
                setText(R.string.backend_test)
                setOnClickListener { test() }
            },
        )
        testResult?.let { column.addView(captionText(it)) }

        // A refused sync and a broken one look identical from the app's side;
        // the difference lives in the response itself — which proxy answered,
        // what it said. Small link rather than a permanent panel: it is
        // debugging help, not furniture.
        LastServerResponse.peek()?.let { snapshot ->
            column.addView(
                captionText(getString(R.string.backend_last_response)).apply {
                    setOnClickListener { showLastResponse(snapshot) }
                },
            )
        }

        column.addView(
            actionButton().apply {
                setText(R.string.backend_save)
                layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
                    .apply { topMargin = dimen(R.dimen.space_md) }
                setOnClickListener { confirmMove() }
            },
        )
        column.addView(captionText(getString(R.string.backend_move_warning)))
    }

    /**
     * The backends this build knows.
     *
     * One, today. Shown as a list anyway rather than hidden until there are two,
     * because the honest thing for this screen to say is what carries the family's
     * messages — and "ntfy, and that is the only choice" is a truer answer than a
     * screen that never mentions it.
     */
    private fun kinds(): RadioGroup = RadioGroup(this).apply {
        orientation = RadioGroup.VERTICAL

        TransportKind.entries.forEachIndexed { index, kind ->
            addView(
                RadioButton(context).apply {
                    id = index + 1
                    text = labelFor(kind)
                    isChecked = kind == chosen
                    setOnCheckedChangeListener { _, checkedNow ->
                        if (checkedNow) chosen = kind
                    }
                },
            )
        }
    }

    private fun labelFor(kind: TransportKind): String = when (kind) {
        TransportKind.NTFY -> getString(R.string.backend_ntfy)
    }

    /**
     * Tries the address before committing to it.
     *
     * Worth a button of its own: a mistyped server produces a family that looks
     * connected and hears nothing, which is indistinguishable from a quiet week
     * and will not be noticed for days.
     */
    private fun test() {
        val token = proposed() ?: return
        // Read what was typed BEFORE re-rendering: render() resets the auth
        // selection from the stored credentials, and the stored ones are
        // precisely what has not been saved yet.
        val credentials = typedCredentials()
        testResult = getString(R.string.backend_testing)
        render()

        // Test with what was typed, so a wrong password is caught here rather
        // than after it has been saved as the family's credentials.
        sync.testReachable(token, credentials) { result ->
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                lastReachable = result == "reachable"
                testResult = getString(R.string.backend_test_result, result)
                render()
            }
        }
    }

    /**
     * What the relay actually answered, in a scrollable, monospace view.
     *
     * Monospace because status lines, headers and JSON bodies read better in
     * columns; selectable because a response is exactly what a support
     * conversation wants pasted into it. No credentials are in here by
     * construction — [LastServerResponse] drops them.
     */
    private fun showLastResponse(snapshot: LastServerResponse.Snapshot) {
        val text = buildString {
            appendLine(snapshot.request)
            appendLine(
                when {
                    snapshot.status >= 0 -> "status ${snapshot.status} (${snapshot.phase})"
                    else -> "no response (${snapshot.phase})"
                },
            )
            if (snapshot.headers.isNotBlank()) {
                appendLine()
                appendLine(snapshot.headers)
            }
            if (snapshot.body.isNotBlank()) {
                appendLine()
                appendLine(snapshot.body)
            }
        }
        val view = TextView(this).apply {
            setText(text)
            typeface = android.graphics.Typeface.MONOSPACE
            setTextIsSelectable(true)
            val pad = dimen(R.dimen.screen_padding)
            setPadding(pad, pad, pad, 0)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.backend_last_response)
            .setView(android.widget.ScrollView(this).apply { addView(view) })
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    /** The credentials currently on screen, in the mode the radio buttons select. */
    private fun typedCredentials(): NtfyCredentials = when (authChoice) {
        AUTH_TOKEN ->
            tokenInput.text.toString().trim()
                .takeIf { it.isNotBlank() }
                ?.let(NtfyCredentials::Token)
                ?: NtfyCredentials.None
        AUTH_BASIC -> {
            val user = usernameInput.text.toString().trim()
            val pass = passwordInput.text.toString().trim()
            if (user.isNotBlank() && pass.isNotBlank()) NtfyCredentials.Basic(user, pass) else NtfyCredentials.None
        }
        else -> NtfyCredentials.None
    }

    private fun proposed(): FamilyToken? {
        val settings = config.load()
        val server = serverInput.text.toString().trim().trimEnd('/')
        val topic = topicInput.text.toString().trim().ifBlank { settings.topic }

        return runCatching { FamilyToken(chosen, server, topic) }
            .getOrElse {
                // Persistent like testResult below it, rather than a toast that is
                // gone by the time someone looks back at the screen.
                testResult = getString(R.string.backend_bad_server)
                render()
                null
            }
    }

    private fun confirmMove() {
        val token = proposed() ?: return

        // Changing the address re-issues the family code and needs a warning.
        // Saving credentials on the address the family already uses changes
        // nothing anyone else can see — it just saves, and the tint below says
        // whether the relay accepted them.
        val previous = config.load()
        val moves = token.kind != previous.transport ||
            token.serverUrl != previous.serverUrl ||
            token.topic != previous.topic
        if (moves) {
            confirmDialog(
                R.string.backend_save,
                getString(R.string.backend_move_confirm, FamilyTokens.encode(token)),
                R.string.backend_save,
            ) {
                move(token)
            }
        } else {
            move(token)
        }
    }

    private fun move(token: FamilyToken) {
        // Adding credentials to the address the family already uses is not a
        // move: the topic, server and poll history all stay as they are, and
        // no other phone needs to be told anything.
        val previous = config.load()
        val serverChanged = token.kind != previous.transport ||
            token.serverUrl != previous.serverUrl ||
            token.topic != previous.topic
        if (serverChanged) config.move(token)

        val stored = config.saveCredentials(
            token = tokenInput.text.toString().trim().takeIf { it.isNotBlank() },
            username = usernameInput.text.toString().trim().takeIf { it.isNotBlank() },
            password = passwordInput.text.toString().trim().takeIf { it.isNotBlank() },
        )
        if (!stored) {
            // Said rather than silent: a keystore that refused to seal leaves
            // the family running without credentials, and the 403s that follow
            // look exactly like the server having changed its mind.
            toast(R.string.backend_credentials_not_stored)
        } else {
            // Verify against the store, not the intent: read back what was
            // sealed and re-test the relay with exactly that. The box then
            // says whether the family is, as of this save, authenticated —
            // which is the thing the person pressing Save wants to know.
            val savedSettings = config.load()
            val savedIsEmpty = savedSettings.credentials is NtfyCredentials.None
            val typedIsEmpty = typedCredentials() is NtfyCredentials.None
            if (savedIsEmpty && !typedIsEmpty) {
                toast(R.string.backend_credentials_not_stored)
            } else {
                sync.testReachable(token) { result ->
                    runOnUiThread {
                        if (isFinishing || isDestroyed) return@runOnUiThread
                        lastReachable = result == "reachable"
                        testResult = getString(R.string.backend_test_result, result)
                        render()
                    }
                }
            }
        }
        Audit.record(
            context = this,
            actor = AuditActor.PARENT,
            action = AuditAction.SYNC_BACKEND_CHANGED,
            subject = token.serverUrl,
            after = token.kind.name,
        )
        // Say hello at the new address at once, so the other phones that have
        // already moved find this one waiting rather than an hour later.
        sync.announceSelf(force = true)
        // Restart the realtime stream against the new server.
        RealtimeSyncService.stop(this)
        RealtimeSyncService.start(this)
        if (serverChanged) showNewCode(token) else render()
    }

    /**
     * The code every other phone now needs.
     *
     * Shown rather than merely mentioned, and selectable, because a family halfway
     * through a move has one phone that can reach the others and several that
     * cannot — this is the only thing that gets them back together.
     */
    private fun showNewCode(token: FamilyToken) {
        val code = TextView(this).apply {
            text = FamilyTokens.encode(token)
            styleAs(R.style.TextAppearance_Dementor_Subtitle)
            setTextIsSelectable(true)
            val pad = dimen(R.dimen.screen_padding)
            setPadding(pad, pad, pad, 0)
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.backend_moved_title)
            .setView(code)
            .setMessage(R.string.backend_moved_body)
            .setPositiveButton(android.R.string.ok) { _, _ -> render() }
            .show()
    }

    companion object {
        private const val AUTH_NONE = "none"
        private const val AUTH_TOKEN = "token"
        private const val AUTH_BASIC = "basic"

        fun intent(context: Context) = Intent(context, SyncBackendActivity::class.java)
    }
}
