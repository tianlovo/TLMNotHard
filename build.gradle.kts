
plugins {
    id("com.gtnewhorizons.gtnhconvention")
}

tasks.withType<Jar>().configureEach {
    from("licenses") { into("META-INF/licenses") }
    from("THIRD_PARTY_NOTICES.md") { into("META-INF") }
}
