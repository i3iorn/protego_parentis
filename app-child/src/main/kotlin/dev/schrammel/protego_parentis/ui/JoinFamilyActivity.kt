package dev.schrammel.protego_parentis.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import dev.schrammel.protego_parentis.R
import dev.schrammel.protego_parentis.audit.Audit
import dev.schrammel.protego_parentis.model.AuditAction
import dev.schrammel.protego_parentis.model.AuditActor
import dev.schrammel.protego_parentis.model.Passphrases
import dev.schrammel.protego_parentis.security.DeviceRole
import dev.schrammel.protego_parentis.security.PinLock
import dev.schrammel.protego_parentis.security.RoleStore
import dev.schrammel.protego_parentis.sync.NtfyCredentials
import dev.schrammel.protego_parentis.sync.RealtimeSyncService
import dev.schrammel.protego_parentis.sync.SyncConfigStore
import dev.schrammel.protego_parentis.sync.SyncCoordinator
import dev.schrammel.protego_parentis.sync.SyncGraph
import dev.schrammel.protego_parentis.sync.SyncSettings
import dev.schrammel.protego_parentis.sync.core.Band
import dev.schrammel.protego_parentis.sync.core.FamilyExport
import dev.schrammel.protego_parentis.sync.core.FamilyExports
import dev.schrammel.protego_parentis.sync.core.FamilyToken
import dev.schrammel.protego_parentis.sync.core.FamilyTokens
import dev.schrammel.protego_parentis.sync.core.MessageCrypto
import dev.schrammel.protego_parentis.sync.core.PassphraseStrength
import dev.schrammel.protego_parentis.sync.core.PassphraseVerdict
import dev.schrammel.protego_parentis.sync.core.TransportKind
import dev.schrammel.protego_parentis.sync.core.Weakness

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
class JoinFamilyActivity : ParentGatedActivity() {
    // An onboarding step: its sequence has its own forward flow, no wandering back.
    override val showsBackButton = false

    private lateinit var config: SyncConfigStore
    private lateinit var sync: SyncCoordinator
    private lateinit var column: LinearLayout

    private lateinit var tokenInput: EditText
    private lateinit var passphraseInput: EditText
    private lateinit var strengthNote: TextView
    private lateinit var startServerInput: EditText
    private lateinit var formError: TextView

    /** Set once a post-join reachability check comes back unreachable; null otherwise. */
    private var reachabilityWarning: String? = null

    /** A sealed export waiting for the person to pick where to save it. */
    private var exportEnvelope: String? = null

    /** An export file body waiting for its passphrase. */
    private var pendingRestore: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The passphrase field can hold a suggestion in clear text, because one
        // that cannot be read cannot be read out. That makes this screen as
        // sensitive as the PIN screen — the base class already applies the same
        // FLAG_SECURE treatment: no recents thumbnail, no screenshot, no screen
        // recording.
        config = SyncConfigStore(this)
        sync = SyncGraph.get(this)

        tokenInput = EditText(this).apply {
            hint = getString(R.string.join_token_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
        }
        passphraseInput = field()
        strengthNote = captionText("")
        // A toast for a form error is gone by the time someone looks back at the
        // screen — this is the persistent version, sitting under the button that
        // triggers each error and staying put until the next attempt.
        formError = captionText("").apply {
            setTextColor(ContextCompat.getColor(this@JoinFamilyActivity, R.color.state_critical))
            visibility = View.GONE
        }
        // Pre-filled with the default rather than left blank: leaving it exactly
        // as shown is the common case, and a blank field would make "unset" and
        // "delete the default on purpose" look the same.
        startServerInput = EditText(this).apply {
            hint = getString(R.string.backend_server_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setText(SyncSettings.DEFAULT_SERVER)
        }

        column = contentColumn()
        showColumn(column)
    }

    override fun onGatedContentVisible() = render()

    override fun onGatedContentHidden() {
        column.removeAllViews()
    }

    /**
     * Who has to prove themselves before touching this.
     *
     * The managed phone always. Everyone else once there is a family to change —
     * a parent's own phone with nothing configured has nothing to protect yet.
     */
    override fun requiresPin(): Boolean {
        if (RoleStore(this).roleOrChild == DeviceRole.CHILD) return PinLock(this).isSet
        return config.load().isUsable && PinLock(this).isSet
    }

    /**
     * Whether this is the setup flow rather than a visit to the settings.
     *
     * The difference is only what happens next: during setup this screen owns the
     * task and has to hand off to the right home screen, and it offers a way past
     * itself for somebody setting up the first phone in a family with nobody to
     * join yet.
     */
    private val onboarding: Boolean by lazy { intent.getBooleanExtra(EXTRA_ONBOARDING, false) }

    private fun render() {
        column.removeAllViews()
        val settings = config.load()

        if (onboarding && settings.isUsable) {
            // Joined during setup: there is nothing left to do here.
            goHome()
            return
        }

        if (settings.isUsable) {
            column.addView(screenTitle(getString(R.string.join_connected_title)))
            column.addView(connectedCard())
            reachabilityWarning?.let { warning ->
                column.addView(
                    captionText(warning).apply {
                        setTextColor(ContextCompat.getColor(this@JoinFamilyActivity, R.color.state_critical))
                    },
                )
            }
            column.addView(
                actionButton().apply {
                    setText(R.string.join_leave)
                    layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
                        .apply { topMargin = dimen(R.dimen.space_lg) }
                    setOnClickListener { confirmLeave() }
                },
            )

            if (RoleStore(this).roleOrChild == DeviceRole.PARENT) {
                column.addView(
                    actionButton().apply {
                        setText(R.string.join_disband)
                        layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
                            .apply { topMargin = dimen(R.dimen.space_sm) }
                        setOnClickListener { confirmDisband() }
                    },
                )
            }

        // The relay lives here rather than beside this screen. Which server
        // carries the messages is part of the family connection, not a sibling of
        // it, and on the hub it took a top-level row for something set once.
        addSection(
            column,
            getString(R.string.join_relay_header),
            listOf(
                navigationRow(
                    getString(R.string.backend_title),
                    getString(R.string.backend_row_detail),
                ) { startActivity(SyncBackendActivity.intent(this)) },
            ),
        )

        addSection(
            column,
            getString(R.string.export_header),
            listOf(
                navigationRow(
                    getString(R.string.export_title),
                    getString(R.string.export_row_detail),
                ) { askExportPassphrase() },
            ),
        )
        return
        }

        if (onboarding) column.addView(stepIndicator(3))
        column.addView(screenTitle(getString(R.string.join_title)))
        column.addView(bodyText(getString(R.string.join_intro)))
        if (onboarding) column.addView(captionText(getString(R.string.join_onboarding_hint)))

        column.addView(sectionHeader(getString(R.string.join_token_header)))
        column.addView(tokenInput)
        column.addView(
            actionButton().apply {
                setText(R.string.join_scan)
                setOnClickListener { scan.launch(ScanQrActivity.intent(context)) }
            },
        )
        column.addView(
            actionButton().apply {
                setText(R.string.join_find_nearby)
                setOnClickListener { findNearby.launch(LanFindActivity.intent(context)) }
            },
        )
        column.addView(sectionHeader(getString(R.string.join_passphrase_header)))
        column.addView(passphraseInput)
        column.addView(strengthNote)
        passphraseInput.addTextChangedListener(SimpleWatcher { showStrength(it) })
        formError.visibility = View.GONE
        (formError.parent as? ViewGroup)?.removeView(formError)
        column.addView(formError)

        column.addView(
            actionButton().apply {
                setText(R.string.join_action)
                layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
                    .apply { topMargin = dimen(R.dimen.space_md) }
                setOnClickListener { join() }
            },
        )

        addSection(
            column,
            getString(R.string.restore_header),
            listOf(
                navigationRow(
                    getString(R.string.restore_title),
                    getString(R.string.restore_row_detail),
                ) { pickRestoreFile.launch(arrayOf("*/*")) },
            ),
        )

        column.addView(sectionHeader(getString(R.string.join_start_header)))
        column.addView(captionText(getString(R.string.join_start_hint)))
        column.addView(
            actionButton().apply {
                setText(R.string.join_suggest_passphrase)
                setOnClickListener { suggestPassphrase() }
            },
        )
        (startServerInput.parent as? ViewGroup)?.removeView(startServerInput)
        column.addView(startServerInput)
        column.addView(captionText(getString(R.string.join_start_server_hint)))
        column.addView(
            actionButton().apply {
                setText(R.string.join_start)
                setOnClickListener { startFamily() }
            },
        )

        // No link to the relay screen here, deliberately — before there is a
        // family it has nothing to do (see its own "nothing to move" state), and
        // now that the server is part of starting one, sending someone there
        // first would be a detour to a screen that turns them straight back.

        // A way past, and only during setup. Connecting phones is the point of
        // this screen, but a family that has not decided yet should not be stuck
        // on it — and everything below works alone, measuring and enforcing on
        // one phone, until they do.
        if (onboarding) {
            column.addView(
                actionButton().apply {
                    setText(R.string.join_later)
                    setOnClickListener { goHome() }
                },
            )
        }
    }

    /**
     * Ends the setup flow on the right screen for this phone.
     *
     * The task is cleared because this screen was started as its own: leaving the
     * role question and the PIN screen on the stack would let a back press walk
     * somebody back into choosing a role they have already chosen.
     */
    private fun goHome() {
        val home = when (RoleStore(this).roleOrChild) {
            DeviceRole.CHILD -> Intent(this, HomeActivity::class.java)
            DeviceRole.PARENT -> ParentHomeActivity.intent(this)
        }
        startActivity(
            home.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        finish()
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

        // The same string, as a picture. Sized from the screen rather than fixed:
        // a code that is meant to be read by another phone's camera should be as
        // large as the card allows, and 320dp on a tablet is a stamp.
        addView(
            actionButton().apply {
                setText(R.string.join_offer_nearby)
                setOnClickListener {
                    startActivity(LanOfferActivity.intent(this@JoinFamilyActivity))
                }
            },
        )

        token?.let(FamilyTokens::encode)?.let { code ->
            val side = (resources.displayMetrics.widthPixels * QR_SCREEN_FRACTION).toInt()
            FamilyQr.encode(code, side)?.let { bitmap ->
                addView(
                    ImageView(this@JoinFamilyActivity).apply {
                        setImageBitmap(bitmap)
                        adjustViewBounds = true
                        layoutParams = LinearLayout.LayoutParams(side, side).apply {
                            gravity = Gravity.CENTER_HORIZONTAL
                            topMargin = dimen(R.dimen.space_md)
                        }
                        // Read aloud as the code it is, for anyone who cannot see it.
                        contentDescription = getString(R.string.join_qr_description)
                    },
                )
                addView(captionText(getString(R.string.join_qr_hint)))
            }
        }
        addView(statRow(getString(R.string.join_backend), settings.transport.name))
        addView(statRow(getString(R.string.join_server), settings.serverUrl))
    }

    /**
     * Takes the token from a phone on the same network.
     *
     * Identical in effect to the scanner: it fills the code field and stops. The
     * passphrase is still typed, because it does not travel over the network any
     * more than it travels in a QR code.
     */
    private val findNearby = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode != RESULT_OK) return@registerForActivityResult
        val code = result.data?.getStringExtra(LanFindActivity.EXTRA_CODE)
            ?: return@registerForActivityResult
        tokenInput.setText(code)
        passphraseInput.requestFocus()
    }

    /**
     * Reads a family code off another phone's screen.
     *
     * It fills the code field and stops there. The passphrase is still typed,
     * because it is not in the code — see [FamilyQr] for why putting it there
     * would trade the security model for a step of typing.
     */
    private val scan = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode != RESULT_OK) return@registerForActivityResult
        val code = result.data?.getStringExtra(ScanQrActivity.EXTRA_CODE) ?: return@registerForActivityResult
        tokenInput.setText(code)
        // Straight to the half that is still missing, so it is obvious that
        // scanning did not finish the job.
        passphraseInput.requestFocus()
    }

    private fun join() {
        val token = FamilyTokens.decode(tokenInput.text.toString())
        if (token == null) {
            showFormError(getString(R.string.join_bad_token))
            return
        }
        connect(token, passphraseInput.text.toString())
    }

    // ---------- export ----------

    /**
     * Asks what encrypts the export file.
     *
     * Deliberately a separate passphrase from the family's, and typed rather
     * than reused: the file is the one artifact likely to be emailed or left in
     * a downloads folder, and a single secret protecting both the topic and the
     * file would mean one leaked passphrase compromises both at once.
     */
    private fun askExportPassphrase() {
        val input = EditText(this).apply {
            hint = getString(R.string.export_passphrase_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.export_dialog_title)
            .setMessage(R.string.export_passphrase_detail)
            .setView(input)
            .setPositiveButton(R.string.export_action, null)
            .setNegativeButton(android.R.string.cancel, null)
            .show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val passphrase = input.text.toString()
            if (passphrase.length < MessageCrypto.MIN_PASSPHRASE_LENGTH) {
                input.error = getString(
                    R.string.export_passphrase_too_short,
                    MessageCrypto.MIN_PASSPHRASE_LENGTH,
                )
                return@setOnClickListener
            }
            val settings = config.load()
            val envelope = runCatching {
                FamilyExports.seal(
                    FamilyExport(
                        token = FamilyToken(settings.transport, settings.serverUrl, settings.topic),
                        passphrase = settings.passphrase,
                        relayToken = (settings.credentials as? NtfyCredentials.Token)?.token.orEmpty(),
                        relayUsername = (settings.credentials as? NtfyCredentials.Basic)?.username.orEmpty(),
                        relayPassword = (settings.credentials as? NtfyCredentials.Basic)?.password.orEmpty(),
                    ),
                    passphrase,
                )
            }.getOrElse {
                toast(R.string.export_failed)
                return@setOnClickListener
            }
            dialog.dismiss()
            exportEnvelope = envelope
            createExportFile.launch(getString(R.string.export_file_name))
        }
    }

    private val createExportFile = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri ->
        val envelope = exportEnvelope
        exportEnvelope = null
        if (uri == null || envelope == null) return@registerForActivityResult
        val written = runCatching {
            contentResolver.openOutputStream(uri)?.use { stream ->
                stream.write(envelope.toByteArray(Charsets.UTF_8))
            } ?: throw IllegalStateException("no stream")
        }.isSuccess
        Toast.makeText(
            this,
            if (written) R.string.export_done else R.string.export_file_error,
            Toast.LENGTH_LONG,
        ).show()
    }

    // ---------- restore ----------

    private val pickRestoreFile = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        val body = runCatching {
            contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
        }.getOrNull()
        if (body.isNullOrBlank()) {
            toast(R.string.restore_bad_file)
            return@registerForActivityResult
        }
        pendingRestore = body
        askRestorePassphrase()
    }

    /** Same shape as the export dialog, and the same minimum length. */
    private fun askRestorePassphrase() {
        val input = EditText(this).apply {
            hint = getString(R.string.export_passphrase_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.restore_dialog_title)
            .setView(input)
            .setPositiveButton(R.string.restore_action, null)
            .setNegativeButton(android.R.string.cancel, null)
            .show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val body = pendingRestore ?: return@setOnClickListener
            val export = runCatching {
                FamilyExports.open(body, input.text.toString())
            }.getOrNull()
            when {
                export == null -> input.error = getString(R.string.restore_open_failed)
                !config.canKeepSecrets() -> {
                    dialog.dismiss()
                    toast(R.string.join_no_keystore)
                }
                else -> {
                    dialog.dismiss()
                    pendingRestore = null
                    restore(export)
                }
            }
        }
    }

    /**
     * Joins the family the export describes.
     *
     * Identical in effect to typing the code and the passphrase by hand: the
     * phone arrives as a new device — the export carries no signing key — and
     * waits in the enrolment queue like any other newcomer.
     */
    private fun restore(export: FamilyExport) {
        sync.join(export.token, export.passphrase)
        config.saveCredentials(
            token = export.relayToken.takeIf { it.isNotBlank() },
            username = export.relayUsername.takeIf { it.isNotBlank() },
            password = export.relayPassword.takeIf { it.isNotBlank() },
        )
        export.deviceName.trim().takeIf { it.isNotBlank() }?.let { config.deviceName = it }
        Audit.record(
            context = this,
            actor = AuditActor.PARENT,
            action = AuditAction.SYNC_CONFIGURED,
            after = Audit.flag(true),
        )
        sync.announceSelf(force = true)
        RealtimeSyncService.start(this)
        reachabilityWarning = null
        Toast.makeText(this, R.string.restore_done, Toast.LENGTH_LONG).show()
        render()
    }

    /** Sets a form error that stays on screen, unlike a toast that is gone by the time someone looks. */
    private fun showFormError(text: String) {
        formError.text = text
        formError.visibility = View.VISIBLE
    }

    /**
     * Fills the passphrase field with something worth using.
     *
     * Put where a family is *starting* one rather than joining, because that is
     * the only moment the passphrase is chosen; on the joining phone it is being
     * repeated, and a suggest button there would be an invitation to overwrite the
     * thing they were just told.
     *
     * Shown rather than hidden, and the field is made visible at the same time.
     * This has to be read aloud to a second phone, so a suggestion behind dots
     * would be a suggestion nobody could use.
     *
     * The field itself is still the one above, shared with "join" on this same
     * scrolling screen — confirmed first when it already holds something, since
     * whatever is there could just as easily be a code someone typed to join a
     * family rather than a suggestion of this button's own making.
     */
    private fun suggestPassphrase() {
        if (passphraseInput.text.isNotEmpty()) {
            confirmDialog(
                R.string.join_suggest_passphrase,
                R.string.join_suggest_overwrite_confirm,
                R.string.join_suggest_passphrase,
            ) { fillSuggestion() }
            return
        }
        fillSuggestion()
    }

    private fun fillSuggestion() {
        passphraseInput.transformationMethod = null
        passphraseInput.setText(Passphrases.suggest())
        passphraseInput.setSelection(passphraseInput.text.length)
        Toast.makeText(this, R.string.join_suggested_note, Toast.LENGTH_LONG).show()
    }

    /**
     * Starts a new family on this phone.
     *
     * A random topic, because on a public server the topic name is the only thing
     * between a stranger and a stream of encrypted family traffic — they still
     * could not read it, but they could watch it move.
     */
    private fun startFamily() {
        // Only here, and deliberately not in connect(). A phone *joining* is
        // repeating a passphrase the family already chose, and refusing it there
        // would lock a family out of adding a device over a judgement about a
        // secret they cannot change without re-pairing everything.
        val verdict = PassphraseStrength.assess(passphraseInput.text.toString())
        if (verdict is PassphraseVerdict.Unusable) {
            showFormError(refusalText(verdict.why))
            return
        }

        val server = startServerInput.text.toString().trim().trimEnd('/')
            .ifBlank { SyncSettings.DEFAULT_SERVER }
        val token = runCatching {
            FamilyToken(kind = TransportKind.NTFY, serverUrl = server, topic = sync.suggestTopic())
        }.getOrElse {
            toast(R.string.backend_bad_server)
            return
        }
        connect(token, passphraseInput.text.toString())
    }

    /**
     * Says what the typed passphrase is worth, as it is typed.
     *
     * Nothing is refused here — that happens once, on starting a family. This is
     * only so that replacing the suggestion with something weaker is a visible
     * act rather than a silent one.
     */
    private fun showStrength(text: String) {
        strengthNote.text = when {
            text.isEmpty() -> ""
            PassphraseStrength.isSuggested(text) -> getString(R.string.passphrase_band_suggested)
            else -> when (val verdict = PassphraseStrength.assess(text)) {
                is PassphraseVerdict.Unusable -> refusalText(verdict.why)
                is PassphraseVerdict.Usable -> when (verdict.band) {
                    Band.WEAK -> getString(R.string.passphrase_band_weak)
                    Band.FAIR -> getString(R.string.passphrase_band_fair)
                    Band.STRONG -> getString(R.string.passphrase_band_strong)
                }
            }
        }
    }

    private fun refusalText(why: Weakness): String = getString(
        when (why) {
            Weakness.TOO_SHORT -> R.string.passphrase_refused_short
            Weakness.TOO_FEW_DISTINCT -> R.string.passphrase_refused_few
            Weakness.REPEATED_UNIT -> R.string.passphrase_refused_repeated
            Weakness.RUN -> R.string.passphrase_refused_run
        },
    )

    private fun connect(token: FamilyToken, passphrase: String) {
        if (passphrase.length < MessageCrypto.MIN_PASSPHRASE_LENGTH) {
            showFormError(
                resources.getQuantityString(
                    R.plurals.sync_passphrase_too_short,
                    MessageCrypto.MIN_PASSPHRASE_LENGTH,
                    MessageCrypto.MIN_PASSPHRASE_LENGTH,
                ),
            )
            return
        }

        // Storing the passphrase can fail on a phone whose keystore has been
        // invalidated, and the store deliberately writes nothing rather than
        // falling back to plain text. Without this the screen would say "joined"
        // and the family would appear unconfigured a moment later.
        if (!config.canKeepSecrets()) {
            showFormError(getString(R.string.join_no_keystore))
            return
        }

        formError.visibility = View.GONE
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
        // Start streaming so messages land as soon as they are published.
        RealtimeSyncService.start(this)
        Toast.makeText(this, R.string.join_done, Toast.LENGTH_LONG).show()
        reachabilityWarning = null
        render()

        // "Joined" only means the config was accepted and stored — a mistyped
        // server or dead relay looks identical at this point and would otherwise
        // surface days later as "the family has been quiet," exactly the failure
        // SyncBackendActivity's own test button exists to catch before a move.
        // Checked here too, since this is the other place a family's address is
        // set for the first time, and asynchronously so a slow or unreachable
        // server doesn't hold up the screen that just said "joined."
        sync.testReachable(token) { result ->
            runOnUiThread {
                if (isFinishing || isDestroyed || result == "reachable") return@runOnUiThread
                reachabilityWarning = getString(R.string.join_unreachable_warning, result)
                render()
            }
        }
    }

    private fun confirmLeave() {
        confirmDialog(R.string.join_leave, R.string.join_leave_confirm, R.string.join_leave) {
            sync.leave()
            Audit.record(
                context = this,
                actor = AuditActor.PARENT,
                action = AuditAction.SYNC_CONFIGURED,
                after = Audit.flag(false),
            )
            render()
        }
    }

    /**
     * Ends the family for everyone, not just this phone.
     *
     * The message reaches every parent phone and the managed phone alike, but
     * only the parents act on it by leaving outright — see
     * [dev.schrammel.protego_parentis.sync.SyncReceiver] for why the managed
     * phone only hears about it instead.
     */
    private fun confirmDisband() {
        confirmDialog(R.string.join_disband, R.string.join_disband_confirm, R.string.join_disband) {
            sync.publishDisband()
            sync.leave()
            Audit.record(
                context = this,
                actor = AuditActor.PARENT,
                action = AuditAction.FAMILY_DISBANDED,
            )
            render()
        }
    }

    private fun field() = EditText(this).apply {
        hint = getString(R.string.join_passphrase_hint)
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
    }

    companion object {
        const val EXTRA_ONBOARDING = "onboarding"

        /** How much of the screen's width the code fills. */
        private const val QR_SCREEN_FRACTION = 0.62f

        fun intent(context: Context, onboarding: Boolean = false): Intent =
            Intent(context, JoinFamilyActivity::class.java)
                .putExtra(EXTRA_ONBOARDING, onboarding)
    }
}

