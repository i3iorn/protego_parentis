package dev.schrammel.protego_parentis.rules

import dev.schrammel.protego_parentis.model.AutoApproval
import dev.schrammel.protego_parentis.model.ContextRule
import dev.schrammel.protego_parentis.model.PlaceId
import dev.schrammel.protego_parentis.model.PlaceRef
import org.json.JSONArray
import org.json.JSONObject

/**
 * A family's complete rule set, packed to share with another family.
 *
 * Everything a rule can depend on rides in the bundle: the category/app
 * policy ([snapshot]), the context rules themselves, the [schedules] and
 * [autoApprovals] rules refer to, and the [placeRefs] their conditions
 * resolve against. Not in the bundle, on purpose: device identity, the
 * family token and passphrase, relay credentials and the Nightscout login —
 * those belong to one family, not to a ruleset. A glucose rule imported
 * without a Nightscout login simply stays a rule the new family must
 * connect before it can fire.
 *
 * Plain JSON, readable and diffable — which is also why the privacy note
 * lives on the share dialog: place names and coordinates are in here, so
 * a ruleset is something to hand to people you trust, not to post.
 */
internal data class RuleBundle(
    val snapshot: PolicySnapshot,
    val contextRules: List<ContextRule>,
    val schedules: List<StoredSchedule>,
    val placeRefs: List<PlaceRef>,
    val autoApprovals: List<AutoApproval>,
) {
    fun toJson(): String = JSONObject().apply {
        put(KEY_FORMAT, FORMAT)
        put(KEY_SNAPSHOT, snapshot.toJson())
        // The list codecs already speak their own JSON; embedded as strings,
        // so each store keeps being the single writer of its own shape.
        put(KEY_CONTEXT_RULES, ContextRuleStore.encodeRules(contextRules))
        put(KEY_SCHEDULES, ScheduleStore.encode(schedules))
        put(KEY_AUTO_APPROVALS, AutoApprovalStore.encode(autoApprovals))
        put(KEY_PLACE_REFS, JSONArray(placeRefs.map(::encodePlaceRef)))
    }.toString()

    companion object {
        internal const val FORMAT = 1
        internal const val FILE_NAME = "protego-rules.json"

        private const val KEY_FORMAT = "format"
        private const val KEY_SNAPSHOT = "snapshot"
        private const val KEY_CONTEXT_RULES = "context_rules"
        private const val KEY_SCHEDULES = "schedules"
        private const val KEY_AUTO_APPROVALS = "auto_approvals"
        private const val KEY_PLACE_REFS = "place_refs"

        /**
         * Null unless the file is a ruleset this build understands: a
         * hand-edited or foreign file must be refused outright, never
         * half-applied.
         */
        internal fun decode(raw: String?): RuleBundle? {
            if (raw.isNullOrBlank()) return null
            val root = runCatching { JSONObject(raw) }.getOrNull() ?: return null
            if (root.optInt(KEY_FORMAT) != FORMAT) return null
            val snapshot = PolicySnapshot.decode(root.optString(KEY_SNAPSHOT))
                ?: return null
            return runCatching {
                RuleBundle(
                    snapshot = snapshot,
                    contextRules = ContextRuleStore.decodeRules(root.optString(KEY_CONTEXT_RULES)),
                    schedules = ScheduleStore.decode(root.optString(KEY_SCHEDULES)),
                    autoApprovals = AutoApprovalStore.decode(root.optString(KEY_AUTO_APPROVALS)),
                    placeRefs = decodePlaceRefs(root.optJSONArray(KEY_PLACE_REFS)),
                )
            }.getOrNull()
        }

        private fun decodePlaceRefs(raw: JSONArray?): List<PlaceRef> {
            if (raw == null) return emptyList()
            return (0 until raw.length()).mapNotNull { index ->
                val json = raw.optJSONObject(index) ?: return@mapNotNull null
                runCatching {
                    PlaceRef(
                        id = PlaceId(json.optString("id").takeIf(String::isNotBlank) ?: return@mapNotNull null),
                        name = json.optString("name"),
                        radiusMetres = json.optInt("radiusMetres"),
                    )
                }.getOrNull()
            }
        }

        private fun encodePlaceRef(ref: PlaceRef): JSONObject = JSONObject().apply {
            put("id", ref.id.value)
            put("name", ref.name)
            put("radiusMetres", ref.radiusMetres)
        }
    }
}
