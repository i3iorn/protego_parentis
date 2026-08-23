package dev.schrammel.dementor_watch.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.schrammel.dementor_watch.R
import dev.schrammel.dementor_watch.model.GoalAchievement
import dev.schrammel.dementor_watch.rules.GoalStore
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * "You did it." Shown the day after a goal is met.
 *
 * Deliberately a whole screen rather than a toast or a badge. The prize is the
 * one moment in this app that is unambiguously on the child's side, and burying
 * it in a corner of a dashboard would waste it.
 *
 * The prize text is whatever the parent wrote and is shown verbatim.
 */
class CelebrationActivity : DementorActivity() {

    private lateinit var store: GoalStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = GoalStore(this)

        val achievement = store.pending()
        if (achievement == null) {
            // Already celebrated, or opened by a stale notification.
            finish()
            return
        }
        setContentView(buildLayout(achievement))
    }

    private fun buildLayout(achievement: GoalAchievement): ScrollView {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            val pad = dimen(R.dimen.screen_padding)
            setPadding(pad, pad, pad, pad)
        }

        content.addView(
            TextView(this).apply {
                text = getString(R.string.celebration_headline)
                styleAs(R.style.TextAppearance_Dementor_Display)
                gravity = Gravity.CENTER
                setPadding(0, dimen(R.dimen.space_4xl), 0, dimen(R.dimen.space_md))
            },
        )

        content.addView(
            TextView(this).apply {
                text = getString(
                    R.string.celebration_summary,
                    Format.duration(achievement.used),
                    Format.duration(achievement.target),
                    DAY_FORMAT.format(achievement.day),
                )
                styleAs(R.style.TextAppearance_Dementor_Body)
                gravity = Gravity.CENTER
            },
        )

        // The prize gets its own surface: it is the point of the screen.
        content.addView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundResource(R.drawable.card)
                val pad = dimen(R.dimen.card_padding)
                setPadding(pad, pad, pad, pad)
                layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply {
                    topMargin = dimen(R.dimen.space_4xl)
                }

                addView(
                    TextView(context).apply {
                        setText(R.string.celebration_prize_label)
                        styleAs(R.style.TextAppearance_Dementor_Caption)
                        gravity = Gravity.CENTER
                    },
                )
                addView(
                    TextView(context).apply {
                        text = achievement.prize.ifBlank {
                            getString(R.string.celebration_prize_missing)
                        }
                        styleAs(R.style.TextAppearance_Dementor_Headline)
                        gravity = Gravity.CENTER
                        setPadding(0, dimen(R.dimen.space_sm), 0, 0)
                    },
                )
            },
        )

        content.addView(
            Button(this).apply {
                setText(R.string.celebration_dismiss)
                layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply {
                    topMargin = dimen(R.dimen.space_4xl)
                }
                setOnClickListener {
                    store.markCelebrated(achievement.day)
                    finish()
                }
            },
        )

        return ScrollView(this).apply {
            layoutParams = ViewGroup.LayoutParams(MATCH, MATCH)
            addView(content)
        }
    }

    /**
     * Backing out counts as seeing it. Leaving the prize pending would show it
     * again on the next unlock, which turns a reward into nagging.
     */
    override fun onPause() {
        super.onPause()
        if (isFinishing) store.pending()?.let { store.markCelebrated(it.day) }
    }

    companion object {
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        private val DAY_FORMAT: DateTimeFormatter =
            DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)

        fun intent(context: Context): Intent =
            Intent(context, CelebrationActivity::class.java)
    }
}
