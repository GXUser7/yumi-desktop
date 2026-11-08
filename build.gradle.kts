plugins {
    kotlin("jvm") version "2.3.10" apply false
    kotlin("plugin.serialization") version "2.3.10" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.10" apply false
    id("org.jetbrains.compose") version "1.11.1" apply false
}
allprojects { group = "com.yumi.desktop"; version = "0.2.1" }
