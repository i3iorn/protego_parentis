package dev.schrammel.protego_parentis.sync

import dev.schrammel.protego_parentis.model.CategoryId
import dev.schrammel.protego_parentis.model.GrantTarget
import dev.schrammel.protego_parentis.model.PackageName

/**
 * How a [GrantTarget] travels between phones.
 *
 * Distinct from the on-disk encoding in `TargetJson` on purpose. This one is a
 * wire format: once a family has two phones on different builds, changing it
 * means the older phone silently mishandling a target, so it is kept small,
 * explicit, and separate from a storage format that is free to evolve.
 *
 * The earlier version wrote a bare identifier, which could not tell an app from
 * a category. Nothing consumed it yet, so it is corrected here rather than
 * carried forward.
 */
object SyncTargets {

    fun encode(target: GrantTarget): String = when (target) {
        is GrantTarget.Device -> DEVICE
        is GrantTarget.App -> "$APP$SEPARATOR${target.pkg.value}"
        is GrantTarget.Category -> "$CATEGORY$SEPARATOR${target.id.value}"
    }

    /** Null for anything unrecognised — assume a newer sender, not a valid target. */
    fun decode(value: String?): GrantTarget? {
        if (value.isNullOrBlank()) return null
        if (value == DEVICE) return GrantTarget.Device

        val at = value.indexOf(SEPARATOR)
        if (at <= 0 || at == value.lastIndex) return null
        val id = value.substring(at + 1)

        return when (value.substring(0, at)) {
            APP -> GrantTarget.App(PackageName(id))
            CATEGORY -> GrantTarget.Category(CategoryId(id))
            else -> null
        }
    }

    private const val SEPARATOR = ':'
    private const val DEVICE = "device"
    private const val APP = "app"
    private const val CATEGORY = "category"
}
