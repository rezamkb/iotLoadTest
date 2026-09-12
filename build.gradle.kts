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
    mavenLocal()

    maven {
        url = uri("https://nrp.devpod.ir/repository/Maven-group-proxy")
    }

    maven {
        name = "nexus"
        url = uri("https://nexus.dotin.ir/nexus/content/groups/Core")

        credentials {
            username = "core"
            password = "fcb@core"
        }
    }
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

// ---------------------------------------------------------------------------
// cepbench: control plane for a CEP benchmark environment (Phase 1).
//
//   ./gradlew.bat cepbench -Pcommand=plan
//   ./gradlew.bat cepbench -Pcommand=provision -Pconfig=path/to/config.json
//
// provision, activate, deactivate and cleanup change the environment the config
// points at. Run plan first to see the target and the resource counts.
// ---------------------------------------------------------------------------

//   src/main/resources/cepbench.sandbox.json
//   src/main/resources/cepbench.local.json
val defaultCepBenchConfig = "src/main/resources/cepbench.sandbox.json"
val cepBenchMainClass = "org.example.cepbench.CepBenchMain"


tasks.register<JavaExec>("cepbench-plan") {
    group = "application"
    description = "Provision, activate, inspect and clean up a CEP benchmark environment"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set(cepBenchMainClass)
    args(
        providers.gradleProperty("command").getOrElse("plan"),
        providers.gradleProperty("config")
            .getOrElse(defaultCepBenchConfig)
    )
}

tasks.register<JavaExec>("cepbench-provision") {
    group = "application"
    description = "Provision, activate, inspect and clean up a CEP benchmark environment"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set(cepBenchMainClass)
    args(
        providers.gradleProperty("command").getOrElse("provision"),
        providers.gradleProperty("config")
            .getOrElse(defaultCepBenchConfig)
    )
}

tasks.register<JavaExec>("cepbench-status") {
    group = "application"
    description = "Provision, activate, inspect and clean up a CEP benchmark environment"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set(cepBenchMainClass)
    args(
        providers.gradleProperty("command").getOrElse("status"),
        providers.gradleProperty("config")
            .getOrElse(defaultCepBenchConfig)
    )
}


tasks.register<JavaExec>("cepbench-active") {
    group = "application"
    description = "Provision, activate, inspect and clean up a CEP benchmark environment"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set(cepBenchMainClass)
    args(
        providers.gradleProperty("command").getOrElse("activate"),
        providers.gradleProperty("config")
            .getOrElse(defaultCepBenchConfig)
    )
}

tasks.register<JavaExec>("cepbench-attach") {
    group = "application"
    description = "Provision, activate, inspect and clean up a CEP benchmark environment"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set(cepBenchMainClass)
    args(
        providers.gradleProperty("command").getOrElse("attach"),
        providers.gradleProperty("config")
            .getOrElse(defaultCepBenchConfig)
    )
}



tasks.register<JavaExec>("cepbench-export") {
    group = "application"
    description = "Provision, activate, inspect and clean up a CEP benchmark environment"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set(cepBenchMainClass)
    args(
        providers.gradleProperty("command").getOrElse("export"),
        providers.gradleProperty("config")
            .getOrElse(defaultCepBenchConfig)
    )
}


tasks.register<JavaExec>("cepbench-reconcile") {
    group = "application"
    description = "Provision, activate, inspect and clean up a CEP benchmark environment"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set(cepBenchMainClass)
    args(
        providers.gradleProperty("command").getOrElse("reconcile"),
        providers.gradleProperty("config")
            .getOrElse(defaultCepBenchConfig)
    )
}

tasks.register<JavaExec>("cepbench-run") {
    group = "application"
    description = "Provision, activate, inspect and clean up a CEP benchmark environment"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set(cepBenchMainClass)
    args(
        providers.gradleProperty("command").getOrElse("run"),
        providers.gradleProperty("config")
            .getOrElse(defaultCepBenchConfig)
    )
}
tasks.register<JavaExec>("cepbench-diagnostics") {
    group = "application"
    description = "Provision, activate, inspect and clean up a CEP benchmark environment"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set(cepBenchMainClass)
    args(
        providers.gradleProperty("command").getOrElse("diagnostics"),
        providers.gradleProperty("config")
            .getOrElse(defaultCepBenchConfig)
    )
}
tasks.register<JavaExec>("cepbench-diagnostics-facts") {
    group = "application"
    description = "Provision, activate, inspect and clean up a CEP benchmark environment"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set(cepBenchMainClass)
    args(
        providers.gradleProperty("command").getOrElse("diagnostics-facts"),
        providers.gradleProperty("config")
            .getOrElse(defaultCepBenchConfig)
    )
}




