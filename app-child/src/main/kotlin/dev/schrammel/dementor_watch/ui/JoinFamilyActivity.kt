package dev.schrammel.dementor_watch.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import dev.schrammel.dementor_watch.R
import dev.schrammel.dementor_watch.audit.Audit
import dev.schrammel.dementor_watch.model.AuditAction
import dev.schrammel.dementor_watch.model.AuditActor
import dev.schrammel.dementor_watch.security.DeviceRole
import dev.schrammel.dementor_watch.security.ParentSession
import dev.schrammel.dementor_watch.security.PinLock
import dev.schrammel.dementor_watch.security.RoleStore
import dev.schrammel.dementor_watch.sync.FamilyToken
import dev.schrammel.dementor_watch.sync.FamilyTokens
import dev.schrammel.dementor_watch.sync.MessageCrypto
import dev.schrammel.dementor_watch.sync.SyncConfigStore
import dev.schrammel.dementor_watch.sync.SyncCoordinator
import dev.schrammel.dementor_watch.sync.TransportKind
import java.util.UUID

private const val MATCH = LinearLayout.LayoutParams.MATCH_PARENT
private const val WRAP = LinearLayout.LayoutParams.WRAP_CONTENT

/**
 * Joining a family, or starting one.
 *
 * **A parent's phone with no family joins without a PIN.** That is a deliberate
 * change, and it is safe because of a different one: joining no longer grants
 * anything. A device that types the code and the passphrase reaches the topic and
 * is then a stranger waiting in a queue — it cannot read a rule, answer a request
 * or be believed about anything until a parent approves it on their own phone.
 * Demanding a PIN first was the wrong order: the first thing somebody does with a
 * new phone is join their family, and inventing a PIN to protect something that
 * does not exist yet is a step for its own sake.
 *
 * **The managed phone is always gated**, family or no family. It is the one device
 * the product assumes is in the hands of the person being limited, and a child who
 * found it unconfigured could otherwise join a family of their own making and
 * answer their own requests from a second phone.
 *
 * Once a family is joined, changing or leaving it needs the PIN on any phone.
 */
class JoinFamilyActivity : DementorActivity() {

    private lateinit var config: SyncConfigStore
    private lateinit var sync: SyncCoordinator
    private lateinit var column: LinearLayout

    private lateinit var tokenInput: EditText
    private lateinit var passphraseInput: EditText

    /** Whether the PIN has been shown for this visit, when one was needed. */
    private var unlocked = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        config = SyncConfigStore(this)
        sync = SyncCoordinator(this)

        tokenInput = EditText(this).apply {
            hint = getString(R.string.join_token_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
        }
        passphraseInput = field()

        column = contentColumn()
        showColumn(column)
    }

    override fun onResume() {
        super.onResume()
        if (needsPin() && !unlocked) {
            unlock.launch(PinActivity.intent(this))
            return
        }
        render()
    }

    /**
     * Who has to prove themselves before touching this.
     *
     * The managed phone always. Everyone else once there is a family to change —
     * a parent's own phone with nothing configured has nothing to protect yet.
     */
    private fun needsPin(): Boolean {
        if (RoleStore(this).roleOrChild == DeviceRole.CHILD) return PinLock(this).isSet
        return config.load().isUsable && PinLock(this).isSet
    }

    private val unlock = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            ParentSession.unlock()
            unlocked = true
            render()
        } else {
            finish()
        }
    }

    private fun render() {
        column.removeAllViews()
        val settings = config.load()

        if (settings.isUsable) {
            column.addView(screenTitle(getString(R.string.join_connected_title)))
            column.addView(connectedCard())
            column.addView(
                Button(this).apply {
                    setText(R.string.join_leave)
                    layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
                        .apply { topMargin = dimen(R.dimen.space_lg) }
                    setOnClickListener { confirmLeave() }
                },
            )
            return
        }

        column.addView(screenTitle(getString(R.string.join_title)))
        column.addView(bodyText(getString(R.string.join_intro)))

        column.addView(sectionHeader(getString(R.string.join_token_header)))
        column.addView(tokenInput)
        column.addView(sectionHeader(getString(R.string.join_passphrase_header)))
        column.addView(passphraseInput)

        column.addView(
            Button(this).apply {
                setText(R.string.join_action)
                layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
                    .apply { topMargin = dimen(R.dimen.space_md) }
                setOnClickListener { join() }
            },
        )

        column.addView(sectionHeader(getString(R.string.join_start_header)))
        column.addView(captionText(getString(R.string.join_start_hint)))
        column.addView(
            Button(this).apply {
                setText(R.string.join_start)
                setOnClickListener { startFamily() }
            },
        )
    }

    /**
     * What this phone is connected to, and the code to pass to the next phone.
     *
     * The passphrase is not shown. It is sealed in the keystore and there is no
     * reason for a screen to be able to display it — a parent adding a second
     * phone reads out the code and types the passphrase they already chose.
     */
    private fun connectedCard(): LinearLayout = card {
        val settings = config.load()
        val token = settings.token()

        addView(
            TextView(this@JoinFamilyActivity).apply {
                text = token?.let(FamilyTokens::encode) ?: getString(R.string.devices_no_identity)
                styleAs(R.style.TextAppearance_Dementor_Subtitle)
                setTextIsSelectable(true)
            },
        )
        addView(captionText(getString(R.string.join_code_hint)))
        addView(statRow(getString(R.string.join_backend), settings.transport.name))
        addView(statRow(getString(R.string.join_server), settings.serverUrl))
    }

    private fun join() {
        val token = FamilyTokens.decode(tokenInput.text.toString())
        if (token == null) {
            Toast.makeText(this, R.string.join_bad_token, Toast.LENGTH_LONG).show()
            return
        }
        connect(token, passphraseInput.text.toString())
    }

    /**
     * Starts a new family on this phone.
     *
     * A random topic, because on a public server the topic name is the only thing
     * between a stranger and a stream of encrypted family traffic — they still
     * could not read it, but they could watch it move.
     */
    private fun startFamily() {
        val passphrase = passphraseInput.text.toString()
        val token = FamilyToken(
            kind = TransportKind.NTFY,
            serverUrl = dev.schrammel.dementor_watch.sync.SyncSettings.DEFAULT_SERVER,
            topic = "dementor-" + UUID.randomUUID().toString().replace("-", ""),
        )
        connect(token, passphrase)
    }

    private fun connect(token: FamilyToken, passphrase: String) {
        if (passphrase.length < MessageCrypto.MIN_PASSPHRASE_LENGTH) {
            Toast.makeText(
                this,
                resources.getQuantityString(
                    R.plurals.sync_passphrase_too_short,
                    MessageCrypto.MIN_PASSPHRASE_LENGTH,
                    MessageCrypto.MIN_PASSPHRASE_LENGTH,
                ),
                Toast.LENGTH_LONG,
            ).show()
            return
        }

        sync.join(token, passphrase)
        Audit.record(
            context = this,
            actor = AuditActor.PARENT,
            action = AuditAction.SYNC_CONFIGURED,
            after = Audit.flag(true),
        )
        // Introduce this phone straight away, so it appears on the other one while
        // somebody is still standing in front of it.
        sync.announceSelf(force = true)
        Toast.makeText(this, R.string.join_done, Toast.LENGTH_LONG).show()
        render()
    }

    private fun confirmLeave() {
        AlertDialog.Builder(this)
            .setTitle(R.string.join_leave)
            .setMessage(R.string.join_leave_confirm)
            .setPositiveButton(R.string.join_leave) { _, _ ->
                sync.leave()
                Audit.record(
                    context = this,
                    actor = AuditActor.PARENT,
                    action = AuditAction.SYNC_CONFIGURED,
                    after = Audit.flag(false),
                )
                render()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun field() = EditText(this).apply {
        hint = getString(R.string.join_passphrase_hint)
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
    }

    companion object {
        fun intent(context: Context) = Intent(context, JoinFamilyActivity::class.java)
    }
}
