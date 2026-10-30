plugins {
	alias(libs.plugins.android.application)
}

android {
	namespace = "enk.danwills.android.enkishadereditor"

	compileSdk = 37

	defaultConfig {
		minSdk = 24
		targetSdk = compileSdk

		versionCode = 97
		versionName = "2.38.0"

		vectorDrawables {
			useSupportLibrary = true
		}
	}

	signingConfigs {
		create("release") {
			keyAlias = System.getenv("ANDROID_KEY_ALIAS")
			keyPassword = System.getenv("ANDROID_KEY_PASSWORD")
			storePassword = System.getenv("ANDROID_STORE_PASSWORD")
			storeFile = System.getenv("ANDROID_KEYFILE")?.let { file(it) }
		}
	}

	// Two apps from one codebase: Enki Shader Editor, and Ultra Rest Area
	// (docs/ultra-rest-area.md) - every screen a shader, no Android UI.
	// The flavour's own source set (src/ultrarestarea) adds its activity
	// and takes away Enki's editor, launcher and permissions.
	flavorDimensions += "app"
	productFlavors {
		create("enki") {
			dimension = "app"
		}
		create("ultrarestarea") {
			dimension = "app"
			applicationId = "enk.danwills.ultrarestarea"
		}
	}

	buildTypes {
		debug {
			applicationIdSuffix = ".debug"
		}

		release {
			isMinifyEnabled = true
			isShrinkResources = true
			signingConfig = signingConfigs["release"]
		}
	}

	buildFeatures {
		buildConfig = true
		viewBinding = true
	}

	compileOptions {
		sourceCompatibility = JavaVersion.VERSION_17
		targetCompatibility = JavaVersion.VERSION_17
	}
}

dependencies {
	implementation(libs.androidx.appcompat)
	implementation(libs.material)
	implementation(libs.androidx.preference)

	implementation(libs.androidx.camera.core)
	implementation(libs.androidx.camera.camera2)
	implementation(libs.androidx.camera.lifecycle)
	compileOnly(libs.androidx.concurrent.futures)

	debugImplementation(libs.leakcanary.android)
}
