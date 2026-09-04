# R8 rules for the release build.
#
# The app's own code needs nothing here: it has no reflection, no dynamic class
# loading and no serialization of its own. Everything below is about the
# dependency graph Quartz brings, which does use reflection — and where R8 is
# right to be suspicious, because most of that graph is genuinely unused and
# should be removed.
#
# The rule of thumb applied: keep what is reached reflectively or from native
# code, and let R8 delete the rest. Anything kept here should say why, so a later
# reader can tell a real requirement from a rule someone added to make a crash
# go away.
#
# IMPORTANT: R8 changes what runs. A release build must be smoke-tested on a
# device — sign in, approve a relay, read a feed, open a picture, publish a note
# — before it is distributed. `assembleRelease` succeeding proves only that
# shrinking completed, not that the result works.

# ---- Stack traces worth reading -------------------------------------------

# Line numbers survive so a crash report can be mapped back. The source file
# name is renamed rather than kept, which is what stops it leaking paths.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Generic signatures and annotations: Jackson and kotlinx-serialization both
# read types at runtime, and stripping these makes them fail in ways that look
# like data corruption rather than like a missing keep rule.
-keepattributes Signature,InnerClasses,EnclosingMethod
-keepattributes *Annotation*,RuntimeVisibleAnnotations,AnnotationDefault

# ---- secp256k1, through JNI ------------------------------------------------

# Native code looks these up by name. R8 cannot see a call from C, so without
# this the methods signing and verifying every event are removed or renamed and
# the app cannot sign anything.
-keep class fr.acinq.secp256k1.** { *; }
-keep class fr.acinq.bitcoin.** { *; }

# ---- Jackson, which Quartz parses events with ------------------------------

# Databind constructs and populates types by reflection: the field names in the
# class *are* the wire format. Quartz's event model is the part that matters —
# a renamed field there means an event that no longer round-trips, which would
# show up as signatures failing to verify rather than as a parse error.
-keep class com.vitorpamplona.quartz.**$* { *; }
-keepclassmembers class com.vitorpamplona.quartz.** {
    <init>(...);
    <fields>;
}

-keep class com.fasterxml.jackson.databind.** { *; }
-keepclassmembers class com.fasterxml.jackson.** { *; }
-dontwarn com.fasterxml.jackson.databind.ext.**

# Jackson's optional integrations are referenced but not shipped. Naming them
# here is the difference between R8 warning about the whole graph and R8
# removing them quietly, which is what should happen.
-dontwarn com.fasterxml.jackson.datatype.**
-dontwarn com.fasterxml.jackson.module.**

# ---- kotlinx-serialization -------------------------------------------------

# Generated serializers are found through a companion, which is a reflective
# lookup R8 cannot follow.
-keepclassmembers class **$$serializer { *; }
-keepclasseswithmembers class ** {
    kotlinx.serialization.KSerializer serializer(...);
}
-dontwarn kotlinx.serialization.**

# ---- OkHttp and Okio -------------------------------------------------------

# Both reference optional platform pieces (Conscrypt, BouncyCastle, GraalVM,
# Animal Sniffer) that are absent on Android. These are warnings to silence, not
# classes to keep.
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn okio.**
-dontwarn org.codehaus.mojo.animal_sniffer.**

# ---- androidx.sqlite, which arrives through Quartz and is not used ----------

# Quartz brings a storage layer this app never touches. Warning-only: nothing is
# kept, so R8 is free to remove all of it, which is the intent.
-dontwarn androidx.sqlite.**
-dontwarn org.slf4j.**

# ---- Kotlin coroutines -----------------------------------------------------

-dontwarn kotlinx.coroutines.**
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}

# ---- Compose ---------------------------------------------------------------

# AGP ships Compose's own rules with the library; nothing extra is needed here.
# This note exists so the absence reads as considered rather than forgotten.
