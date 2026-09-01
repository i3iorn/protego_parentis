# Rules for the release build.
#
# R8 works out what to keep from the manifest and from ordinary call graphs, and
# gets nearly all of this right on its own. What it cannot see is anything the
# platform reaches by name at runtime. Everything below is one of those cases, and
# each entry says which platform mechanism needs it — a keep rule with no reason
# attached is one nobody can ever safely delete.

# ---------------------------------------------------------------- device policy
#
# The admin receiver is named in AndroidManifest.xml and in res/xml/device_admin,
# so R8 keeps the class. Renaming it would be worse than useless in any case: a
# device that is already provisioned records the component name it was set up
# with, and an update that moves it would leave a phone owned by a component that
# no longer exists, with no way back short of a factory reset.
-keep class dev.schrammel.protego_parentis.dpc.DementorDeviceAdminReceiver { *; }

# ---------------------------------------------------------------- WorkManager
#
# Workers are constructed reflectively from a class name that WorkManager wrote
# into its own database when the work was scheduled. A parent's phone that
# upgrades while a poll is enqueued would otherwise wake up to a name that no
# longer resolves, and the work would be dropped rather than retried.
-keep class * extends androidx.work.ListenableWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}

# ---------------------------------------------------------------- org.json
#
# Android's own org.json is in the platform, not the app, so nothing here needs
# keeping — but the stores serialise through it and a missing class would only
# show up at runtime. Silence the note rather than leaving it to be rediscovered.
-dontwarn org.json.**

# ---------------------------------------------------------------- diagnostics
#
# Line numbers survive so a stack trace from a tester is worth reading, and the
# source file attribute is renamed rather than kept, which is what makes the
# mapping file the only way to turn one back into names.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
