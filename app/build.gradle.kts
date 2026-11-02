plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "vn.airplayrx"
    compileSdk = 34
    defaultConfig { applicationId = "vn.airplayrx"; minSdk = 28; targetSdk = 34; versionCode = 1; versionName = "1.0" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    packaging { resources { excludes += setOf("META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*", "META-INF/INDEX.LIST", "META-INF/AL2.0", "META-INF/LGPL2.1") } }
}
dependencies {
    implementation("androidx.media3:media3-exoplayer:1.3.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.3.1")
    implementation("androidx.media3:media3-ui:1.3.1")
    implementation("com.googlecode.plist:dd-plist:1.28")
    implementation("org.jmdns:jmdns:3.5.9")
    implementation("org.slf4j:slf4j-nop:1.7.36")
}
