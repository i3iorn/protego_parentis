package dev.schrammel.dementor_watch.sync

import android.content.Context

/**
 * Everything a phone knows because it was in a family.
 *
 * Gathered in one place so that leaving one can actually mean leaving. Clearing
 * the connection settings alone left behind, on a parent's phone, the child's last
 * position, where they had recently arrived and departed, the sites they had
 * reached, their requests, and every family device's public key and name — a
 * complete little dossier on a phone that had walked away from the family.
 *
 * Deliberately not the child's own records. Their usage history, rules, goals and
 * shared history are theirs and describe their own device; they survive leaving a
 * family exactly as they survive never joining one. What goes is what arrived from
 * somebody else, or what was only ever kept in order to talk to them.
 */
object FamilyData {

    /**
     * Forgets the family and everything learned from it.
     *
     * Ordered so the connection goes first: if this is interrupted halfway, a
     * phone with stale records and no way to reach the family is a much better
     * outcome than a phone still talking to a family it has been removed from.
     */
    fun forget(context: Context) {
        RosterStore(context).forgetAll()
        HeartbeatStore(context).latest = null
        InboxStore(context).forgetAll()
        NewAppStore(context).forgetAll()
        DailySummaryStore(context).latest = null
        FamilyEventStore(context).forgetAll()
        // The conversation goes with the family. A phone that has left holding a
        // transcript of what was said inside it is the dossier problem again, in
        // the most personal form this app has.
        ChatStore(context).forgetAll()
    }
}
