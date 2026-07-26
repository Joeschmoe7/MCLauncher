# ---------------------------------------------------------------------------
# Release (R8/minified) rules.
#
# The debug build sets isMinifyEnabled = false, so none of this applies there —
# which is exactly why the gap went unnoticed. The note that used to sit here
# said "rules for kotlinx.serialization will be added in P2 once DataStore +
# JSON persistence land." P2 landed; the rules never did. LauncherConfig /
# RowConfig / ShortcutConfig and the settings model all round-trip through
# kotlinx.serialization, and R8 renames the generated $$serializer classes and
# Companion.serializer() accessors it can't see referenced. Without these, a
# release build can fail to read saved rows — and this app is the Home
# launcher, so a crash on boot is awkward to back out of.
# ---------------------------------------------------------------------------

-dontwarn kotlinx.coroutines.**

# --- kotlinx.serialization -------------------------------------------------
# The canonical rule set from the kotlinx.serialization README. The library
# does ship consumer rules of its own, but these are belt-and-braces and cost
# nothing: the serialized model here is a handful of small data classes.

# Keep `serializer()` on the companions of @Serializable classes.
-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    static <1>$Companion Companion;
}

# Keep the generated serializer for @Serializable classes.
-if @kotlinx.serialization.Serializable class ** {
    static **$* *;
}
-keepclassmembers class <2>$<3> {
    kotlinx.serialization.KSerializer serializer(...);
}

-keepclasseswithmembers class ** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Belt-and-braces for this app's own serialized model.
-keep,includedescriptorclasses class com.wmc.mediacenter.data.**$$serializer { *; }
-keepclassmembers class com.wmc.mediacenter.data.** {
    *** Companion;
}

# --- Compose / TV ----------------------------------------------------------
# Compose and tv-material ship their own consumer rules; nothing extra needed.
# Noted explicitly so the next person doesn't wonder whether it was forgotten
# the way the serialization rules were.
