plugins {
    id("java-library")
    id("com.vanniktech.maven.publish") version "0.34.0"
}

fun isReleaseBuild() = hasProperty("RELEASE")

group = property("GROUP") as String
version = property("VERSION") as String + (if (isReleaseBuild()) "" else "-SNAPSHOT")

repositories {
    mavenCentral()
}

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

dependencies {
    api("org.json:json:20250517")
    implementation("org.semver4j:semver4j:6.0.0")
    implementation("com.badlogicgames.jnigen:jnigen-commons:3.1.1")
    testImplementation(platform("org.junit:junit-bom:5.10.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
}

tasks.test {
    useJUnitPlatform()
}

mavenPublishing {
    publishToMavenCentral()
    if (isReleaseBuild())
        signAllPublications()
}