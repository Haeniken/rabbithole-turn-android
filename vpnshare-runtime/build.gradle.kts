import java.io.File
import java.util.Properties
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.jvm.tasks.Jar

abstract class BuildVpnHotspotDaemonTask : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sourceDir: DirectoryProperty

    @get:Input
    abstract val cargoProfile: Property<String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Internal
    abstract val targetDir: DirectoryProperty

    @get:Internal
    abstract val ndkRoot: DirectoryProperty

    @TaskAction
    fun buildDaemon() {
        val cargoDir = sourceDir.get().asFile
        val cargoTargetDir = targetDir.get().asFile
        outputDir.get().asFile.apply {
            deleteRecursively()
            mkdirs()
        }
        val profile = cargoProfile.get()
        val targets = listOf(
            "arm64-v8a" to "aarch64-linux-android",
            "armeabi-v7a" to "armv7-linux-androideabi",
        )
        for ((abi, rustTarget) in targets) {
            val command = mutableListOf(
                "cargo", "ndk", "--target", abi, "--platform", "29",
                "build", "--locked", "--bin", "vpnhotspotd",
            ).apply {
                if (profile == "release") add("--release")
            }
            val process = ProcessBuilder(command)
                .directory(cargoDir)
                .redirectErrorStream(true)
                .apply {
                    environment()["CARGO_BUILD_TARGET_DIR"] = cargoTargetDir.absolutePath
                    environment()["ANDROID_NDK_HOME"] = ndkRoot.get().asFile.absolutePath
                    environment()["ANDROID_NDK_ROOT"] = ndkRoot.get().asFile.absolutePath
                }
                .start()
            val output = process.inputStream.bufferedReader().readText()
            check(process.waitFor() == 0) { "cargo build failed for $rustTarget\n$output" }
            val binary = cargoTargetDir.resolve("$rustTarget/$profile/vpnhotspotd")
            check(binary.isFile) { "Missing VPNHotspot daemon: ${binary.absolutePath}" }
            outputDir.file("$abi/libvpnhotspotd.so").get().asFile.apply {
                parentFile.mkdirs()
                binary.copyTo(this, overwrite = true)
            }
        }
    }
}

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.parcelize)
    alias(libs.plugins.wire)
}

android {
    namespace = "be.mygod.vpnhotspot"
    compileSdk = 36

    defaultConfig {
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

val vpnHotspotRoot = layout.projectDirectory.dir("../external/VPNHotspot/mobile")
val includedSources = listOf(
    "be/mygod/vpnhotspot/io/Utils.kt",
    "be/mygod/vpnhotspot/net/IpSecForwardPolicyCommand.kt",
    "be/mygod/vpnhotspot/net/Netd.kt",
    "be/mygod/vpnhotspot/net/NetlinkNeighbour.kt",
    "be/mygod/vpnhotspot/net/Routing.kt",
    "be/mygod/vpnhotspot/net/monitor/TrafficRecorder.kt",
    "be/mygod/vpnhotspot/net/monitor/Upstreams.kt",
    "be/mygod/vpnhotspot/root/RootManager.kt",
    "be/mygod/vpnhotspot/root/daemon/DaemonAbi.kt",
    "be/mygod/vpnhotspot/root/daemon/DaemonCommands.kt",
    "be/mygod/vpnhotspot/root/daemon/DaemonController.kt",
    "be/mygod/vpnhotspot/root/daemon/DaemonIpc.kt",
    "be/mygod/vpnhotspot/util/Services.kt",
    "be/mygod/vpnhotspot/util/Utils.kt",
)

val copyVpnHotspotSources by tasks.registering(Sync::class) {
    from(vpnHotspotRoot.dir("src/main/java")) {
        includedSources.forEach(::include)
    }
    into(layout.buildDirectory.dir("generated/vpnhotspot/java"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.java?.addStaticSourceDirectory(copyVpnHotspotSources.get().destinationDir.absolutePath)
        val sdkDir = run {
            val localProperties = rootProject.file("local.properties")
            val configured = if (localProperties.exists()) Properties().also {
                localProperties.inputStream().use(it::load)
            }.getProperty("sdk.dir") else null
            configured ?: System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT")
            ?: error("Android SDK not found")
        }
        val ndkRoot = File(sdkDir, "ndk").listFiles()
            ?.filter(File::isDirectory)
            ?.maxByOrNull(File::getName)
            ?: error("Android NDK not found")
        val variantName = variant.name.replaceFirstChar(Char::uppercase)
        val daemonTask = tasks.register<BuildVpnHotspotDaemonTask>("build${variantName}VpnHotspotDaemon") {
            sourceDir.set(vpnHotspotRoot.dir("src/main/rust/vpnhotspotd"))
            cargoProfile.set(if (variant.buildType == "release") "release" else "debug")
            outputDir.set(layout.buildDirectory.dir("generated/jni/${variant.name}"))
            targetDir.set(layout.buildDirectory.dir("rust/vpnhotspotd"))
            this.ndkRoot.set(ndkRoot)
        }
        variant.sources.jniLibs?.addGeneratedSourceDirectory(daemonTask, BuildVpnHotspotDaemonTask::outputDir)
    }
}

afterEvaluate {
    tasks.matching {
        it.name.startsWith("compile") || it.name.startsWith("lint") || it.name.startsWith("extract")
    }.configureEach { dependsOn(copyVpnHotspotSources) }
}

wire {
    kotlin {
        enumMode = "sealed_class"
        rpcRole = "none"
    }
    sourcePath {
        srcDir(vpnHotspotRoot.dir("src/main/proto"))
    }
}

val hiddenApiStubAnnotations by configurations.creating
val compileHiddenApiStubs by tasks.registering(JavaCompile::class) {
    source(vpnHotspotRoot.dir("src/hiddenApiStubs/java"))
    classpath = files(androidComponents.sdkComponents.bootClasspath) + hiddenApiStubAnnotations
    destinationDirectory.set(layout.buildDirectory.dir("hidden-api-stubs/classes"))
    sourceCompatibility = "17"
    targetCompatibility = "17"
}
val hiddenApiStubsJar by tasks.registering(Jar::class) {
    from(compileHiddenApiStubs.flatMap { it.destinationDirectory })
    archiveFileName.set("hidden-api-stubs.jar")
    destinationDirectory.set(layout.buildDirectory.dir("hidden-api-stubs"))
}

dependencies {
    compileOnly(files(hiddenApiStubsJar.flatMap { it.archiveFile }))
    hiddenApiStubAnnotations(libs.androidx.annotation)
    implementation(libs.androidx.annotation)
    implementation(libs.androidx.collection)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.preference.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.librootkotlinx)
    implementation(libs.ktor.io)
    implementation(libs.okio)
    implementation(libs.timber)
    implementation(libs.wire.runtime)
    implementation("androidx.browser:browser:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-collections-immutable:0.5.0")
    implementation("org.lsposed.hiddenapibypass:hiddenapibypass:6.1")
}
