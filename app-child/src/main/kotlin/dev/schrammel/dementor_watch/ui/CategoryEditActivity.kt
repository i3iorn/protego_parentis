package dev.schrammel.dementor_watch.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import dev.schrammel.dementor_watch.R
import dev.schrammel.dementor_watch.model.Defaults
import dev.schrammel.dementor_watch.model.PackageName
import dev.schrammel.dementor_watch.rules.LocalPolicyStore
import dev.schrammel.dementor_watch.rules.StoredCategory
import dev.schrammel.dementor_watch.usage.InstalledApps
import java.util.UUID

/**
 * Creates or edits one category: a name, a daily limit, and the apps in it.
 *
 * An app may be picked here and in any number of other categories — membership is
 * many-to-many by design, and every category it lands in is enforced separately.
 */
class CategoryEditActivity : AppCompatActivity() {

    private lateinit var store: LocalPolicyStore
    private lateinit var installedApps: InstalledApps

    private var categoryId: String = ""
    private var packages = linkedSetOf<String>()

    private lateinit var nameInput: EditText
    private lateinit var limitValue: TextView
    private lateinit var limitBar: SeekBar
    private lateinit var appsSummary: TextView

    private val defaultLimitMinutes = Defaults.CATEGORY_LIMIT.inWholeMinutes.toInt()

    private val pickApps = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        packages = LinkedHashSet(AppPickerActivity.resultFrom(result.data))
        refreshAppsSummary()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = LocalPolicyStore(this)
        installedApps = InstalledApps(this)

        val existing = intent.getStringExtra(EXTRA_ID)
            ?.let { id -> store.categories.firstOrNull { it.id == id } }

        categoryId = existing?.id ?: UUID.randomUUID().toString()
        packages = LinkedHashSet(existing?.packages.orEmpty())

        setContentView(buildLayout(existing))
        refreshAppsSummary()
    }

    private fun buildLayout(existing: StoredCategory?): ScrollView {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dimen(R.dimen.screen_padding)
            setPadding(pad, pad, pad, pad)
        }

        content.addView(heading(getString(R.string.category_title), 28f))

        nameInput = EditText(this).apply {
            hint = getString(R.string.category_name_hint)
            setText(existing?.name.orEmpty())
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
        }
        content.addView(nameInput)

        content.addView(heading(getString(R.string.category_limit_header), 20f))
        limitValue = TextView(this).apply {
            styleAs(R.style.TextAppearance_Dementor_Metric)
            text = Format.limit(existing?.limitMinutes ?: defaultLimitMinutes)
        }
        content.addView(limitValue)

        limitBar = SeekBar(this).apply {
            max = LimitSlider.MAX_STEPS
            progress = LimitSlider.progressFor(existing?.limitMinutes ?: defaultLimitMinutes)
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                    limitValue.text = Format.limit(LimitSlider.minutesFor(progress))
                }

                override fun onStartTrackingTouch(bar: SeekBar) = Unit
                override fun onStopTrackingTouch(bar: SeekBar) = Unit
            })
        }
        content.addView(limitBar)

        content.addView(heading(getString(R.string.category_apps_header), 20f))
        appsSummary = TextView(this).apply { styleAs(R.style.TextAppearance_Dementor_Body) }
        content.addView(appsSummary)

        content.addView(
            Button(this).apply {
                setText(R.string.category_choose_apps)
                layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
                    .apply { topMargin = dimen(R.dimen.space_sm) }
                setOnClickListener {
                    pickApps.launch(
                        AppPickerActivity.intent(
                            this@CategoryEditActivity,
                            getString(R.string.category_apps_header),
                            packages,
                        ),
                    )
                }
            },
        )

        content.addView(
            Button(this).apply {
                setText(R.string.category_save)
                layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
                    .apply { topMargin = dimen(R.dimen.section_spacing) }
                setOnClickListener { save() }
            },
        )

        if (existing != null) {
            content.addView(
                Button(this).apply {
                    setText(R.string.category_delete)
                    layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
                        .apply { topMargin = dimen(R.dimen.space_sm) }
                    setOnClickListener {
                        store.deleteCategory(categoryId)
                        finish()
                    }
                },
            )
        }

        return ScrollView(this).apply {
            layoutParams = ViewGroup.LayoutParams(MATCH, MATCH)
            addView(content)
        }
    }

    private fun save() {
        val name = nameInput.text.toString().trim()
        if (name.isEmpty()) {
            Toast.makeText(this, R.string.category_name_required, Toast.LENGTH_SHORT).show()
            return
        }

        store.upsertCategory(
            StoredCategory(
                id = categoryId,
                name = name,
                limitMinutes = LimitSlider.minutesFor(limitBar.progress),
                packages = packages.toSet(),
            ),
        )
        finish()
    }

    private fun refreshAppsSummary() {
        appsSummary.text = when {
            packages.isEmpty() -> getString(R.string.category_no_apps)
            else -> packages
                .map { installedApps.labelFor(PackageName(it)) }
                .sorted()
                .joinToString(", ")
        }
    }

    private fun heading(text: String, size: Float) = TextView(this).apply {
        this.text = text
        textSize = size
        setPadding(0, dimen(R.dimen.screen_padding), 0, dimen(R.dimen.space_xs))
    }

    companion object {
        private const val EXTRA_ID = "categoryId"
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

        fun intent(context: Context, categoryId: String? = null): Intent =
            Intent(context, CategoryEditActivity::class.java)
                .apply { categoryId?.let { putExtra(EXTRA_ID, it) } }
    }
}
