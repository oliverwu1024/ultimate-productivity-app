plugins {
    id("com.android.application") version "8.13.2" apply false
    id("org.jetbrains.kotlin.android") version "2.2.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.21" apply false
    id("com.google.devtools.ksp") version "2.3.9" apply false
    // §9.8 — Firebase Cloud Messaging needs the google-services plugin to
    // process `app/google-services.json` at build time (generates the Firebase
    // initializer resources). Declared `apply false` here; applied in app/.
    id("com.google.gms.google-services") version "4.4.4" apply false
}
