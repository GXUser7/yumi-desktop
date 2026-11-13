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
compose.desktop {
    application {
        mainClass = "com.yumi.desktop.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Msi)
            packageName = "Yumi"
            packageVersion = "0.2.5"
            includeAllModules = true
            windows { menu = true; shortcut = true; upgradeUuid = "e8cb03d0-c852-4375-bf1b-a83f09e118b7"
                iconFile.set(project.file("icon/yumi.ico")) }
        }
    }
}
