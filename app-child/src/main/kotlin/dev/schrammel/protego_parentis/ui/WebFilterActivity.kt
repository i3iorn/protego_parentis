package dev.schrammel.protego_parentis.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Toast
import dev.schrammel.protego_parentis.R
import dev.schrammel.protego_parentis.audit.Audit
import dev.schrammel.protego_parentis.enforcement.EnforcementService
import dev.schrammel.protego_parentis.model.AuditAction
import dev.schrammel.protego_parentis.model.AuditActor
import dev.schrammel.protego_parentis.rules.DnsProfile
import dev.schrammel.protego_parentis.rules.WebFilterStore
import dev.schrammel.protego_parentis.rules.isPlausibleHost

/**
 * Web filtering (WF-1, WF-2).
 *
 * Two mechanisms with very different reach, presented separately because
 * conflating them would over-promise. Pointing the device at a filtering resolver
 * covers every app including WebViews and costs nothing in battery, but only
 * blocks whole domains. Browser policy is finer but reaches exactly one browser,
 * and the screen says so.
 *
 * Neither touches what happens *inside* an app: a filtered resolver does nothing
 * about a video recommended in a feed, and the copy here does not pretend
 * otherwise. Over-promising on filtering is how a family stops checking.
 */
class WebFilterActivity : ParentGatedActivity() {

    private lateinit var store: WebFilterStore
    private lateinit var column: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = WebFilterStore(this)
        column = contentColumn()
        showColumn(column)
    }

    override fun onGatedContentVisible() = render()

    override fun onGatedContentHidden() {
        column.removeAllViews()
    }

    private fun render() {
        column.removeAllViews()
        column.addView(screenTitle(getString(R.string.web_title)))
        column.addView(bodyText(getString(R.string.web_intro)))

        column.addView(sectionHeader(getString(R.string.web_dns_header)))
        column.addView(
            card {
                addView(captionText(getString(R.string.web_dns_hint)))
                addView(profiles())
            },
        )

        column.addView(sectionHeader(getString(R.string.web_browser_header)))
        column.addView(
            card {
                addView(captionText(getString(R.string.web_browser_hint)))
                addView(browserSwitches())
            },
        )

        column.addView(captionText(getString(R.string.web_limits_note)))
    }

    private fun profiles(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL

        val custom = EditText(this@WebFilterActivity).apply {
            hint = getString(R.string.web_dns_custom_hint)
            setText(store.customHost)
        }

        val group = RadioGroup(context).apply {
            orientation = RadioGroup.VERTICAL
            DnsProfile.entries.forEachIndexed { index, profile ->
                addView(
                    RadioButton(context).apply {
                        id = index + 1
                        setText(labelFor(profile))
                        isChecked = store.profile == profile
                    },
                )
            }
        }
        addView(group)
        addView(captionText(getString(R.string.web_dns_custom_label)))
        addView(custom)

        addView(
            Button(context).apply {
                setText(R.string.web_dns_save)
                layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply {
                    topMargin = dimen(R.dimen.space_sm)
                }
                setOnClickListener {
                    val chosen = DnsProfile.entries[group.checkedRadioButtonId - 1]
                    saveDns(chosen, custom.text.toString())
                }
            },
        )
    }

    private fun saveDns(profile: DnsProfile, host: String) {
        if (profile == DnsProfile.CUSTOM && !isPlausibleHost(host)) {
            Toast.makeText(this, R.string.web_dns_bad_host, Toast.LENGTH_LONG).show()
            return
        }

        store.customHost = host
        store.profile = profile
        Audit.record(
            context = this,
            actor = AuditActor.PARENT,
            action = AuditAction.WEB_FILTER_CHANGED,
            after = profile.name,
        )
        // Applied at once, and the result matters: Android refuses a host that
        // does not answer DNS-over-TLS, and the family needs to find out now
        // rather than believe the phone is filtered when it is not.
        EnforcementService.start(this)
        toast(R.string.web_dns_saved)
        render()
    }

    private fun browserSwitches(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        val policy = store.browserPolicy

        fun switch(labelRes: Int, checked: Boolean, apply: (Boolean) -> Unit) {
            addView(
                CheckBox(context).apply {
                    setText(labelRes)
                    isChecked = checked
                    setOnCheckedChangeListener { _, value ->
                        apply(value)
                        Audit.record(
                            context = this@WebFilterActivity,
                            actor = AuditActor.PARENT,
                            action = AuditAction.BROWSER_POLICY_CHANGED,
                            subject = getString(labelRes),
                            after = Audit.flag(value),
                        )
                        EnforcementService.start(this@WebFilterActivity)
                    }
                },
            )
        }

        switch(R.string.web_safe_search, policy.forceSafeSearch) {
            store.browserPolicy = store.browserPolicy.copy(forceSafeSearch = it)
        }
        switch(R.string.web_youtube, policy.restrictYouTube) {
            store.browserPolicy = store.browserPolicy.copy(restrictYouTube = it)
        }
        switch(R.string.web_incognito, policy.blockIncognito) {
            store.browserPolicy = store.browserPolicy.copy(blockIncognito = it)
        }
        switch(R.string.web_suspend_browsers, policy.suspendUnmanagedBrowsers) {
            store.browserPolicy = store.browserPolicy.copy(suspendUnmanagedBrowsers = it)
        }
        addView(captionText(getString(R.string.web_suspend_browsers_hint)))
    }

    private fun labelFor(profile: DnsProfile): Int = when (profile) {
        DnsProfile.OFF -> R.string.web_dns_off
        DnsProfile.MALWARE -> R.string.web_dns_malware
        DnsProfile.ADULT -> R.string.web_dns_adult
        DnsProfile.STRICT -> R.string.web_dns_strict
        DnsProfile.CUSTOM -> R.string.web_dns_custom
    }

    companion object {
        fun intent(context: Context) = Intent(context, WebFilterActivity::class.java)
    }
}
