package dev.schrammel.dementor_watch.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import dev.schrammel.dementor_watch.R
import dev.schrammel.dementor_watch.usage.InstalledApps

/**
 * Multi-select list of installed apps.
 *
 * Shared by "apps with no limit" and by category membership, because both are the
 * same question — pick some apps — and duplicating the list twice would mean two
 * places to fix when it grows search or sorting.
 */
class AppPickerActivity : AppCompatActivity() {

    private val selected = linkedSetOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        selected += intent.getStringArrayListExtra(EXTRA_SELECTED).orEmpty()
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()

        setContentView(buildLayout(title))
    }

    private fun buildLayout(title: String): ScrollView {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dimen(R.dimen.screen_padding)
            setPadding(pad, pad, pad, pad)
        }

        content.addView(
            TextView(this).apply {
                text = title
                styleAs(R.style.TextAppearance_Dementor_Headline)
                setPadding(0, 0, 0, dimen(R.dimen.space_md))
            },
        )

        InstalledApps(this).launchable().forEach { app ->
            content.addView(row(app.pkg.value, app.label, app.icon))
        }

        return ScrollView(this).apply {
            layoutParams = ViewGroup.LayoutParams(MATCH, MATCH)
            addView(content)
        }
    }

    private fun row(
        pkg: String,
        label: String,
        icon: android.graphics.drawable.Drawable?,
    ): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dimen(R.dimen.space_xs), 0, dimen(R.dimen.space_xs))

        addView(
            ImageView(context).apply {
                layoutParams = LinearLayout.LayoutParams(dimen(R.dimen.row_icon), dimen(R.dimen.row_icon))
                    .apply { marginEnd = dimen(R.dimen.space_md) }
                setImageDrawable(icon)
            },
        )
        addView(
            TextView(context).apply {
                text = label
                styleAs(R.style.TextAppearance_Dementor_ListItem)
                layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
            },
        )
        addView(
            CheckBox(context).apply {
                isChecked = pkg in selected
                setOnCheckedChangeListener { _, checked ->
                    if (checked) selected += pkg else selected -= pkg
                }
            },
        )

        setOnClickListener {
            // Tapping the row toggles too — the checkbox alone is a small target.
            (getChildAt(2) as CheckBox).toggle()
        }
    }

    override fun finish() {
        setResult(
            Activity.RESULT_OK,
            Intent().putStringArrayListExtra(EXTRA_SELECTED, ArrayList(selected)),
        )
        super.finish()
    }

    companion object {
        const val EXTRA_SELECTED = "selected"
        private const val EXTRA_TITLE = "title"
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

        fun intent(context: Context, title: String, selected: Collection<String>): Intent =
            Intent(context, AppPickerActivity::class.java)
                .putExtra(EXTRA_TITLE, title)
                .putStringArrayListExtra(EXTRA_SELECTED, ArrayList(selected))

        fun resultFrom(data: Intent?): List<String> =
            data?.getStringArrayListExtra(EXTRA_SELECTED).orEmpty()
    }
}
