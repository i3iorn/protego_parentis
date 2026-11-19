package dev.schrammel.protego_parentis.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.InputType
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
    private var chosen: TransportKind = TransportKind.NTFY

    /** Null until a test has been run in this visit. */
    private var testResult: String? = null

    /** Auth type selector and inputs (persisted across renders). */
    private lateinit var authTypeRadioGroup: RadioGroup
    private lateinit var tokenInput: EditText
    private lateinit var usernameInput: EditText
    private lateinit var passwordInput: EditText

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

    private fun render() {
        column.removeAllViews()
        column.addView(screenTitle(getString(R.string.backend_title)))
        column.addView(bodyText(getString(R.string.backend_intro)))

        val settings = config.load()
        if (!settings.isUsable) {
            // Nothing to move. Changing the address of a family this phone has not
            // joined would write settings that the next join overwrites anyway.
            column.addView(bodyText(getString(R.string.backend_not_connected)))
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

        // Authentication section
        val credentials = settings.credentials
        column.addView(sectionHeader(getString(R.string.backend_auth_header)))
        column.addView(
            card {
                authTypeRadioGroup = RadioGroup(this@SyncBackendActivity).apply {
                    orientation = RadioGroup.VERTICAL
                }
                val types = listOf(
                    "none" to R.string.backend_auth_none,
                    "token" to R.string.backend_auth_token,
                    "basic" to R.string.backend_auth_basic,
                )
                types.forEachIndexed { index, (key, label) ->
                    val isSelected = when {
                        credentials is NtfyCredentials.Token && key == "token" -> true
                        credentials is NtfyCredentials.Basic && key == "basic" -> true
                        credentials is NtfyCredentials.None && key == "none" -> true
                        else -> false
                    }
                    authTypeRadioGroup.addView(
                        RadioButton(this@SyncBackendActivity).apply {
                            id = index + 1
                            text = getString(label)
                            isChecked = isSelected
                            setOnCheckedChangeListener { _, checkedNow ->
                                if (checkedNow) {
                                    // Show/hide input fields
                                    tokenInput.visibility = if (key == "token") android.view.View.VISIBLE else android.view.View.GONE
                                    val showBasic = key == "basic"
                                    usernameInput.visibility = if (showBasic) android.view.View.VISIBLE else android.view.View.GONE
                                    passwordInput.visibility = if (showBasic) android.view.View.VISIBLE else android.view.View.GONE
                                }
                            }
                        },
                    )
                }
                addView(authTypeRadioGroup)

                // Show/hide inputs based on current selection
                val initialVisible = when (credentials) {
                    is NtfyCredentials.Token -> "token"
                    is NtfyCredentials.Basic -> "basic"
                    is NtfyCredentials.None -> "none"
                }
                tokenInput.visibility = if (initialVisible == "token") android.view.View.VISIBLE else android.view.View.GONE
                val showBasic = initialVisible == "basic"
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
            },
        )

        column.addView(
            actionButton().apply {
                setText(R.string.backend_test)
                setOnClickListener { test() }
            },
        )
        testResult?.let { column.addView(captionText(it)) }

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
        testResult = getString(R.string.backend_testing)
        render()

        sync.testReachable(token) { result ->
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                testResult = getString(R.string.backend_test_result, result)
                render()
            }
        }
    }

    private fun proposed(): FamilyToken? {
        val settings = config.load()
        val server = serverInput.text.toString().trim().trimEnd('/')

        return runCatching { FamilyToken(chosen, server, settings.topic) }
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

        confirmDialog(
            R.string.backend_save,
            getString(R.string.backend_move_confirm, FamilyTokens.encode(token)),
            R.string.backend_save,
        ) {
            move(token)
        }
    }

    private fun move(token: FamilyToken) {
        config.move(token)
        // Save credentials as well
        val tokenValue = tokenInput.text.toString().trim().takeIf { it.isNotBlank() }
        val user = usernameInput.text.toString().trim().takeIf { it.isNotBlank() }
        val pass = passwordInput.text.toString().trim().takeIf { it.isNotBlank() }
        config.saveCredentials(token = tokenValue, username = user, password = pass)
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
        showNewCode(token)
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
        fun intent(context: Context) = Intent(context, SyncBackendActivity::class.java)
    }
}
