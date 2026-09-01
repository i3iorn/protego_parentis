package dev.schrammel.protego_parentis.model

/** An Android package name, e.g. `com.google.android.youtube`. */
@JvmInline
value class PackageName(val value: String) {
    override fun toString(): String = value
}

/** Stable identifier for a child profile within a family. */
@JvmInline
value class ChildId(val value: String) {
    override fun toString(): String = value
}

/** Stable identifier for a named schedule block. */
@JvmInline
value class ScheduleId(val value: String) {
    override fun toString(): String = value
}

/** Stable identifier for a category of apps. */
@JvmInline
value class CategoryId(val value: String) {
    override fun toString(): String = value
}

/** Stable identifier for a bonus-time grant. */
@JvmInline
value class GrantId(val value: String) {
    override fun toString(): String = value
}
