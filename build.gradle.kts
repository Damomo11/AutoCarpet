plugins {
    id("net.fabricmc.fabric-loom") version "1.18.+"
}

val minecraftVersion = providers.gradleProperty("minecraft_version").get()
val fabricLoaderVersion = providers.gradleProperty("fabric_loader_version").get()
val fabricApiVersion = providers.gradleProperty("fabric_api_version").get()
val malilibVersion = providers.gradleProperty("malilib_version").get()
val litematicaVersion = providers.gradleProperty("litematica_version").get()
val modmenuVersion = providers.gradleProperty("modmenu_version").get()
val jdkVersion = providers.gradleProperty("jdk_version").get().toInt()
val modVersion = providers.gradleProperty("mod_version").get()

base {
    archivesName = "autocarpet"
    group = "com.autocarpet"
    version = modVersion
}

repositories {
    maven {
        name = "Fabric"
        url = uri("https://maven.fabricmc.net/")
    }
    mavenCentral()
    maven {
        name = "Babbaj"
        url = uri("https://babbaj.github.io/maven/")
    }
    maven {
        name = "TerraformersMC"
        url = uri("https://maven.terraformersmc.com/releases/")
    }
    maven {
        name = "Masa"
        url = uri("https://masa.dy.fi/maven/sakura-ryoko")
        content {
            includeGroupAndSubgroups("fi.dy.masa")
        }
    }
    maven {
        name = "Fallenbreath"
        url = uri("https://maven.fallenbreath.me/releases")
        content {
            includeGroupAndSubgroups("me.fallenbreath")
        }
    }
}

dependencies {
    minecraft("com.mojang:minecraft:${minecraftVersion}")
    implementation("net.fabricmc:fabric-loader:${fabricLoaderVersion}")
    compileOnly("net.fabricmc.fabric-api:fabric-api:${fabricApiVersion}")
    compileOnly("fi.dy.masa.malilib:malilib-fabric-${minecraftVersion}:${malilibVersion}")
    compileOnly("fi.dy.masa.litematica:litematica-fabric-${minecraftVersion}:${litematicaVersion}")
    compileOnly("com.terraformersmc:modmenu:${modmenuVersion}")
    compileOnly("dev.babbaj:nether-pathfinder:1.4.1")
    include("dev.babbaj:nether-pathfinder:1.4.1")
    compileOnly("com.google.code.findbugs:jsr305:3.0.2")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(jdkVersion))
    }
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xmaxerrs", "1000"))
}
