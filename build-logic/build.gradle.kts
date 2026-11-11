plugins {
    `java-gradle-plugin`
}

dependencies {
    implementation(localGroovy())
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(17)
}

gradlePlugin {
    plugins {
        register("jabelJavaConventions") {
            id = "tlmnh.jabel-java-conventions"
            implementationClass = "com.tianluoqaq.tlmnh.buildlogic.JabelJavaConventionsPlugin"
        }
        register("releaseVerification") {
            id = "tlmnh.release-verification"
            implementationClass = "com.tianluoqaq.tlmnh.buildlogic.ReleaseVerificationPlugin"
        }
    }
}
