plugins {
    id("java")
    application
}

group = "org.example"
version = "1.0-SNAPSHOT"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

repositories {
    mavenCentral()
}

dependencies {

    testImplementation(platform("org.junit:junit-bom:5.10.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    implementation ("org.eclipse.paho:org.eclipse.paho.client.mqttv3:1.2.5")
    implementation ("com.fasterxml.jackson.core:jackson-databind:2.15.2")
    implementation ("com.opencsv:opencsv:5.9")
    implementation ("com.google.guava:guava:32.1.2-jre")
    implementation("javax.jms:javax.jms-api:2.0.1")
    implementation("org.apache.activemq:activemq-client:5.17.4")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.19.0")
    implementation("org.apache.commons:commons-csv:1.10.0")


}

application {
    mainClass.set("org.example.mqtt.edge.EdgeMqttLoadSimulator")
}

tasks.jar {
    manifest { attributes["Main-Class"] = application.mainClass.get() }

    // pull in every runtime dependency
    from({
        configurations.runtimeClasspath.get()
            .filter { it.name.endsWith("jar") }
            .map { zipTree(it) }
    })

    archiveBaseName.set("iotLoadTest")
    archiveClassifier.set("all")          // iotLoadTest-all.jar
    archiveVersion.set(project.version.toString())



}


tasks.test {
    useJUnitPlatform()
}
