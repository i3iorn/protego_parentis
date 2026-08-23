package dev.schrammel.dementor_watch.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import dev.schrammel.dementor_watch.R
import dev.schrammel.dementor_watch.audit.Audit
import dev.schrammel.dementor_watch.model.AuditAction
import dev.schrammel.dementor_watch.model.AuditActor
import dev.schrammel.dementor_watch.sync.MessageCrypto
import dev.schrammel.dementor_watch.sync.SyncCoordinator
import dev.schrammel.dementor_watch.sync.SyncSettings

/**
 * Connects this phone to the family's topic.
 *
 * Parent-gated, because the passphrase shown here is the only thing protecting
 * the family's traffic, and the topic name is what a second parent phone needs
 * in order to join.
 *
 * Adding another parent phone means entering the same server, topic and
 * passphrase there. There is no account and no invitation flow — the shared
 * secret *is* the membership.
 */
class SyncSettingsActivity : ParentGatedActivity() {

    private lateinit var sync: SyncCoordinator
    private lateinit var enabled: CheckBox
    private lateinit var server: EditText
    private lateinit var topic: EditText
    private lateinit var passphrase: EditText
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sync = SyncCoordinator(this)
        setContentView(buildLayout(sync.settings()))
    }

    private fun buildLayout(settings: SyncSettings): ScrollView {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dimen(R.dimen.screen_padding)
            setPadding(pad, pad, pad, pad)
        }

        content.addView(title(getString(R.string.sync_title), R.style.TextAppearance_Dementor_Title))
        content.addView(caption(getString(R.string.sync_explainer)))

        enabled = CheckBox(this).apply {
            setText(R.string.sync_enable)
            isChecked = settings.enabled
        }
        content.addView(enabled)

        content.addView(label(getString(R.string.sync_server_label)))
        server = field(settings.serverUrl, InputType.TYPE_TEXT_VARIATION_URI)
        content.addView(server)

        content.addView(label(getString(R.string.sync_topic_label)))
        content.addView(caption(getString(R.string.sync_topic_hint)))
        topic = field(settings.topic, InputType.TYPE_CLASS_TEXT)
        content.addView(topic)

        content.addView(
            Button(this).apply {
                setText(R.string.sync_generate_topic)
                setOnClickListener { topic.setText(sync.suggestTopic()) }
            },
        )

        content.addView(label(getString(R.string.sync_passphrase_label)))
        content.addView(
            caption(
                resources.getQuantityString(
                    R.plurals.sync_passphrase_hint,
                    MessageCrypto.MIN_PASSPHRASE_LENGTH,
                    MessageCrypto.MIN_PASSPHRASE_LENGTH,
                ),
            ),
        )
        // Never pre-filled: showing a stored secret back invites a shoulder-surf,
        // and leaving it blank on save keeps whatever is already stored.
        passphrase = field("", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        passphrase.hint = if (settings.passphrase.isBlank()) {
            getString(R.string.sync_passphrase_empty)
        } else {
            getString(R.string.sync_passphrase_set)
        }
        content.addView(passphrase)

        content.addView(
            Button(this).apply {
                setText(R.string.sync_save)
                layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply {
                    topMargin = dimen(R.dimen.space_xl)
                }
                setOnClickListener { save() }
            },
        )
        content.addView(
            Button(this).apply {
                setText(R.string.sync_test)
                layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
                setOnClickListener { test() }
            },
        )

        status = caption("")
        content.addView(status)

        return ScrollView(this).apply {
            layoutParams = ViewGroup.LayoutParams(MATCH, MATCH)
            addView(content)
        }
    }

    private fun save() {
        val serverUrl = server.text.toString().trim()
        val typedPassphrase = passphrase.text.toString()
        val stored = sync.settings()

        // Refuse http: the topic is public and the payload is encrypted, but
        // plain HTTP would still leak the topic name and the timing of every
        // message to anyone on the network path.
        if (enabled.isChecked && !serverUrl.startsWith("https://")) {
            toast(getString(R.string.sync_needs_https))
            return
        }
        val effective = typedPassphrase.ifBlank { stored.passphrase }
        if (enabled.isChecked && effective.length < MessageCrypto.MIN_PASSPHRASE_LENGTH) {
            toast(
                resources.getQuantityString(
                    R.plurals.sync_passphrase_too_short,
                    MessageCrypto.MIN_PASSPHRASE_LENGTH,
                    MessageCrypto.MIN_PASSPHRASE_LENGTH,
                ),
            )
            return
        }
        if (enabled.isChecked && topic.text.toString().isBlank()) {
            toast(getString(R.string.sync_needs_topic))
            return
        }

        sync.saveSettings(
            enabled = enabled.isChecked,
            serverUrl = serverUrl,
            topic = topic.text.toString(),
            passphrase = typedPassphrase,
        )
        // Neither the topic nor the passphrase is recorded. That the family is
        // connected is shared history; the key to it is not.
        Audit.record(
            context = this,
            actor = AuditActor.PARENT,
            action = AuditAction.SYNC_CONFIGURED,
            before = Audit.flag(stored.enabled),
            after = Audit.flag(enabled.isChecked),
        )
        passphrase.setText("")
        // The other phones cannot enrol this one until they have heard from it.
        sync.announceSelf(force = true)
        toast(getString(R.string.sync_saved))
    }

    private fun test() {
        status.setText(R.string.sync_testing)
        sync.pollNow { result ->
            runOnUiThread { status.text = getString(R.string.sync_test_result, result) }
        }
    }

    // ---------- view helpers ----------

    private fun field(value: String, type: Int) = EditText(this).apply {
        setText(value)
        inputType = type
        setSingleLine()
        layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
    }

    private fun title(text: String, appearance: Int) = TextView(this).apply {
        this.text = text
        styleAs(appearance)
    }

    private fun label(text: String) = TextView(this).apply {
        this.text = text
        styleAs(R.style.TextAppearance_Dementor_Section)
        setPadding(0, dimen(R.dimen.section_spacing), 0, 0)
    }

    private fun caption(text: String) = TextView(this).apply {
        this.text = text
        styleAs(R.style.TextAppearance_Dementor_Caption)
        setPadding(0, dimen(R.dimen.space_xxs), 0, dimen(R.dimen.space_xxs))
    }

    private fun toast(message: String) =
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    companion object {
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

        fun intent(context: Context) = Intent(context, SyncSettingsActivity::class.java)
    }
}
