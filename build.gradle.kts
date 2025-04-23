plugins {
    id("java")
}

group = "org.example"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {

    testImplementation(platform("org.junit:junit-bom:5.10.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    implementation ("org.eclipse.paho:org.eclipse.paho.client.mqttv3:1.2.5")
    implementation ("com.fasterxml.jackson.core:jackson-databind:2.15.2")
    implementation ("com.opencsv:opencsv:5.9")

}

tasks.test {
    useJUnitPlatform()
}