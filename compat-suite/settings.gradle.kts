// Standalone build: run it with the repository wrapper, `./gradlew -p compat-suite <task>`.
// It is deliberately not part of the paperweight build in the repository root, so a fixture compiled
// against a 2015 API never shares a classpath or toolchain with the server.
rootProject.name = "lecithin-compat-suite"

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        maven("https://repo.papermc.io/repository/maven-public/")
        // Historical Bukkit/Spigot API snapshots (spigot-api 1.8.8, 1.16.5, bungeecord-chat).
        maven("https://hub.spigotmc.org/nexus/content/repositories/snapshots/")
    }
}

include("runner")

// Every directory under fixtures/ that has a fixture.properties is one API era.
file("fixtures").listFiles()
    ?.filter { File(it, "fixture.properties").isFile }
    ?.sortedBy { it.name }
    ?.forEach { include("fixtures:${it.name}") }
