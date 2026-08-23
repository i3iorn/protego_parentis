package dev.schrammel.dementor_watch.rules

import dev.schrammel.dementor_watch.model.CategoryId
import dev.schrammel.dementor_watch.model.GrantTarget
import dev.schrammel.dementor_watch.model.PackageName
import org.json.JSONObject

/**
 * Serialisation for [GrantTarget], shared by everything that stores one.
 *
 * Grants and requests both point at "the whole device, one app, or one category".
 * Writing that encoding twice would mean two chances for the formats to drift
 * apart, and a request whose target no longer matches its grant is a silent bug.
 */
internal object TargetJson {

    fun write(json: JSONObject, target: GrantTarget): JSONObject = json.apply {
        when (target) {
            is GrantTarget.Device -> put(KEY_KIND, DEVICE)
            is GrantTarget.App -> {
                put(KEY_KIND, APP)
                put(KEY_ID, target.pkg.value)
            }

            is GrantTarget.Category -> {
                put(KEY_KIND, CATEGORY)
                put(KEY_ID, target.id.value)
            }
        }
    }

    /** Null when the stored value is unrecognised, so one bad row is skipped. */
    fun read(json: JSONObject): GrantTarget? {
        val id = json.optString(KEY_ID).takeIf(String::isNotBlank)
        return when (json.optString(KEY_KIND)) {
            DEVICE -> GrantTarget.Device
            APP -> id?.let { GrantTarget.App(PackageName(it)) }
            CATEGORY -> id?.let { GrantTarget.Category(CategoryId(it)) }
            else -> null
        }
    }

    private const val KEY_KIND = "target"
    private const val KEY_ID = "targetId"
    private const val DEVICE = "device"
    private const val APP = "app"
    private const val CATEGORY = "category"
}
