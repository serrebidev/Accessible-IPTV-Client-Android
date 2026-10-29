plugins {
	alias(libs.plugins.android.app)
}

kotlin {
	jvmToolchain(libs.versions.java.jdk.get().toInt())
}

android {
	namespace = "dev.serrebi.accessibleiptv"
	compileSdk = libs.versions.android.compileSdk.get().toInt()

	defaultConfig {
		applicationId = "dev.serrebi.accessibleiptv"
		minSdk = libs.versions.android.minSdk.get().toInt()
		targetSdk = libs.versions.android.targetSdk.get().toInt()
		versionCode = 25
		versionName = "0.2.23"
	}

	buildFeatures {
		buildConfig = true
	}

	// Android 13+ per-app language picker (desktop: Options, Language) lists every translated locale.
	androidResources {
		generateLocaleConfig = true
	}

	// Robolectric runs the Android-side tests (SQLite guide, JSON cache, screens) on the JVM.
	testOptions {
		unitTests.isIncludeAndroidResources = true
		// Robolectric's SDK 36 sandbox reaches into FileDescriptor internals; JDK 21 closes them by default.
		unitTests.all { it.jvmArgs("--add-opens=java.base/jdk.internal.access=ALL-UNNAMED") }
	}

	buildTypes {
		release {
			isMinifyEnabled = false
			// Release is signed with the owner's debug key (as Beluga): same key every
			// release, so in-app updates install over the previous version.
			signingConfig = signingConfigs.getByName("debug")
		}
	}
}

dependencies {
	implementation(libs.androidx.appcompat)
	implementation(libs.androidx.recyclerview)
	implementation(libs.androidx.core.ktx)
	implementation(libs.androidx.lifecycle.runtime.ktx)
	implementation(libs.kotlinx.coroutines.android)
	implementation(libs.media3.exoplayer)
	// HLS (.m3u8) needs its own module; MPEG-TS ships inside media3-exoplayer.
	implementation(libs.media3.exoplayer.hls)
	implementation(libs.media3.ui)
	// Casting: Media3 CastPlayer + the route chooser dialog (Google Play services at runtime).
	implementation(libs.media3.cast)
	implementation(libs.okhttp)
	implementation(libs.androidx.mediarouter)
	testImplementation(libs.junit)
	testImplementation(libs.robolectric)
}
