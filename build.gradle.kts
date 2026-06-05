plugins {
 id("com.android.application") version "8.2.2" apply false
 id("org.jetbrains.kotlin.android") version "1.9.22" apply false
}

subprojects {
    // JAXB fix for JDK 17+ — javax.xml.bind was removed from JDK 11 onwards
    // Android Gradle Plugin 8.x needs it internally during build
    configurations.all {
        resolutionStrategy {
            force "javax.xml.bind:jaxb-api:2.3.1"
            force "org.glassfish.jaxb:jaxb-runtime:2.3.9"
        }
    }
}
