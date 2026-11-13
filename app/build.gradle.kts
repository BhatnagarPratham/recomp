import java.net.URI
import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.room)
}

android {
    namespace = "app.recomp"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.recomp"
        minSdk = 34
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // M0: debug-signed so the minified build installs for the R8 spike. A personal keystore replaces this before real use.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        // Robolectric gives the DAO and migration tests a real Android context and SQLite.
        unitTests.isIncludeAndroidResources = true
    }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "META-INF/DEPENDENCIES",
                "META-INF/INDEX.LIST",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
            )
        }
    }
}

room {
    schemaDirectory("$projectDir/schemas")
}

// INDB, the Indian Nutrient Databank (TRD §6.4). Its repository declares no licence, so the data is never
// committed: each build downloads it from the authors at a pinned commit, checks it and converts it into
// the food asset under build/. `-Precomp.indb=false` builds without it; any APK published from this repo
// must be built that way unless the authors agree to bundling.
val indbCommit = "fbdb62bec519c6e2468582a799fb47e194b42558"
val indbFiles = mapOf(
    "INDB.xlsx" to "65f91911de09ebdb4b80c4a3dc3ff372a35420e05444239d8df8a42e8b5b9ecf",
    "recipes.xlsx" to "5cc0e914c2660b165f25b539e12e7cfba092379a76504d8f068c0f23f7b64aff",
)
// Only true or false: a mistyped opt-out must never quietly bundle the data.
val bundleIndb = when (val raw = providers.gradleProperty("recomp.indb").orNull?.trim()) {
    null -> true
    else -> raw.lowercase().toBooleanStrictOrNull()
        ?: throw GradleException("-Precomp.indb must be true or false, not \"$raw\". Use -Precomp.indb=false to build without INDB.")
}
logger.lifecycle(
    if (bundleIndb) "Recomp: this build bundles INDB (personal use only; pass -Precomp.indb=false for an APK you share)."
    else "Recomp: this build doesn't bundle INDB.",
)

/** Downloads INDB's files once into Gradle's user home, and only uses them when their SHA-256 matches. */
abstract class DownloadIndb : DefaultTask() {
    @get:Input abstract val commit: Property<String>
    @get:Input abstract val sha256: MapProperty<String, String>
    @get:Internal abstract val cacheDir: DirectoryProperty
    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    @TaskAction
    fun download() {
        sha256.get().forEach { (name, expected) ->
            val cached = cacheDir.file("$expected/$name").get().asFile
            if (!cached.isFile || sha(cached) != expected) {
                cached.parentFile.mkdirs()
                val part = File(cached.parentFile, "$name.part")
                val url = URI("https://raw.githubusercontent.com/lindsayjaacks/Indian-Nutrient-Databank-INDB-/${commit.get()}/$name").toURL()
                try {
                    val connection = url.openConnection().apply { connectTimeout = 30_000; readTimeout = 120_000 }
                    connection.getInputStream().use { input -> part.outputStream().use { input.copyTo(it) } }
                } catch (e: java.io.IOException) {
                    part.delete()
                    throw GradleException("Couldn't download INDB's $name ($e). To build without INDB, pass -Precomp.indb=false.", e)
                }
                val actual = sha(part)
                if (actual != expected) {
                    part.delete()
                    throw GradleException("INDB's $name has SHA-256 $actual, not the pinned $expected, so it isn't used.")
                }
                check(part.renameTo(cached)) { "Couldn't move $part into the cache" }
            }
            cached.copyTo(outputDir.file(name).get().asFile, overwrite = true)
        }
    }

    private fun sha(file: File): String = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
}

/** Runs the converter in :domain (`app.recomp.domain.food.indb`), which writes `food/indb.json`. */
abstract class ConvertIndb : JavaExec() {
    @get:InputDirectory abstract val xlsxDir: DirectoryProperty
    @get:Input abstract val dataVersion: Property<String>
    @get:OutputDirectory abstract val assetsDir: DirectoryProperty

    init {
        mainClass.set("app.recomp.domain.food.indb.IndbConvert")
        argumentProviders.add(
            CommandLineArgumentProvider {
                listOf(
                    xlsxDir.file("INDB.xlsx").get().asFile.path,
                    xlsxDir.file("recipes.xlsx").get().asFile.path,
                    dataVersion.get(),
                    assetsDir.file("food/indb.json").get().asFile.path,
                )
            },
        )
    }
}

val indbTool: Configuration = configurations.create("indbTool") {
    isCanBeConsumed = false
    isCanBeResolved = true
    attributes {
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
        attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.LIBRARY))
        attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements.JAR))
        attribute(TargetJvmEnvironment.TARGET_JVM_ENVIRONMENT_ATTRIBUTE, objects.named(TargetJvmEnvironment.STANDARD_JVM))
    }
}

val downloadIndb = tasks.register<DownloadIndb>("downloadIndb") {
    description = "Downloads INDB's workbooks at the pinned commit and checks their SHA-256 (TRD §6.4)."
    commit.set(indbCommit)
    sha256.set(indbFiles)
    cacheDir.set(File(gradle.gradleUserHomeDir, "caches/recomp-indb"))
    outputDir.set(layout.buildDirectory.dir("indb/xlsx"))
}

val fetchIndb = tasks.register<ConvertIndb>("fetchIndb") {
    group = "build"
    description = "Downloads INDB at the pinned commit, checks it, and converts it into the food asset under build/ (TRD §6.4)."
    classpath = indbTool
    xlsxDir.set(downloadIndb.flatMap { it.outputDir })
    dataVersion.set("indb-${indbCommit.take(12)}")
    assetsDir.set(layout.buildDirectory.dir("generated/indb"))
}

androidComponents {
    onVariants { variant ->
        if (bundleIndb) variant.sources.assets?.addGeneratedSourceDirectory(fetchIndb, ConvertIndb::assetsDir)
    }
}

dependencies {
    implementation(project(":domain"))
    indbTool(project(":domain"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.navigation.compose)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.hilt.android)
    implementation(libs.androidx.hilt.lifecycle.viewmodel.compose)
    ksp(libs.hilt.compiler)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    implementation(libs.datastore.preferences)
    implementation(libs.health.connect)
    implementation(libs.anthropic.java)
    implementation(libs.tink.android)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    // Compose UI tests of the workout screen run on Robolectric (TRD §10, M6).
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
}
