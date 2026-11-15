import org.jetbrains.compose.desktop.application.dsl.TargetFormat
plugins { kotlin("jvm"); id("org.jetbrains.kotlin.plugin.compose"); id("org.jetbrains.compose") }
kotlin {
    jvmToolchain(21)
    compilerOptions.freeCompilerArgs.addAll(
        "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
        "-opt-in=androidx.compose.material3.ExperimentalMaterial3ExpressiveApi"
    )
}
dependencies {
    implementation(project(":logic")); implementation(project(":platform"))
    implementation(compose.desktop.currentOs)
    implementation("org.jetbrains.compose.material3:material3:1.11.0-alpha07")
    implementation("org.jetbrains.compose.material:material-icons-extended:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.10.0")
    testImplementation(kotlin("test"))
    testImplementation(compose.desktop.uiTestJUnit4)
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
}

// The tunnel service binary rides inside the package, in the resources directory the launcher
// names at runtime (compose.application.resources.dir); see DesktopPlatform.serviceExecutable.
// Which binary depends on the host the package is built on — jpackage cannot cross-compile —
// and the build scripts put it in runtime/ before calling any packaging task.
val hostOs = System.getProperty("os.name").lowercase()
val serviceOs = when {
    "win" in hostOs -> "windows"
    "mac" in hostOs -> "macos"
    else -> "linux"
}
val serviceName = if ("win" in hostOs) "yumi-service.exe" else "yumi-service"
val stageServiceBinary = tasks.register<Copy>("stageServiceBinary") {
    from(rootProject.layout.projectDirectory.file("runtime/$serviceName"))
    into(layout.projectDirectory.dir("resources/$serviceOs/service"))
    rename { serviceName }
}
tasks.whenTaskAdded {
    if (name in setOf("createDistributable", "packageDistributionForCurrentOS", "packageMsi", "packageDeb", "packageRpm", "packageDmg", "packagePkg")) {
        dependsOn(stageServiceBinary)
    }
    // prepareAppResources copies the resources tree into the image, so the service binary has to
    // be staged before it runs, not after.
    if (name == "prepareAppResources") { dependsOn(stageServiceBinary) }
}

compose.desktop {
    application {
        mainClass = "com.yumi.desktop.MainKt"
        nativeDistributions {
            // One format per platform: Msi for Windows (wrapped by the Inno installer), deb and
            // rpm for Linux, Dmg to hand someone and Pkg for the updater to install on macOS.
            targetFormats(TargetFormat.Msi, TargetFormat.Deb, TargetFormat.Rpm, TargetFormat.Dmg, TargetFormat.Pkg)
            packageName = "Yumi"
            packageVersion = "0.3.0"
            includeAllModules = true
            appResourcesRootDir.set(project.layout.projectDirectory.dir("resources"))
            windows { menu = true; shortcut = true; upgradeUuid = "e8cb03d0-c852-4375-bf1b-a83f09e118b7"
                iconFile.set(project.file("icon/yumi.ico")) }
            linux {
                iconFile.set(project.file("icon/yumi-256.png"))
                debMaintainer = "yumi-desktop@users.noreply.github.com"
            }
            macOS {
                iconFile.set(project.file("icon/yumi.icns"))
                bundleID = "app.yumi.desktop"
                dockName = "Yumi"
            }
        }
    }
}
