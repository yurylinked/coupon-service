import org.springframework.boot.gradle.plugin.SpringBootPlugin

plugins {
    java
    groovy
    alias(libs.plugins.spring.boot)
}

group = "pl.miskiewicz"
version = "0.1.0-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(platform(SpringBootPlugin.BOM_COORDINATES))

    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation(libs.geoip2)
    runtimeOnly("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")
}

testing {
    suites {
        named<JvmTestSuite>("test") {
            useSpock(libs.versions.spock)
            dependencies {
                implementation("org.springframework.boot:spring-boot-starter-test")
            }
        }

        register<JvmTestSuite>("integrationTest") {
            useSpock(libs.versions.spock)
            dependencies {
                implementation(project())
                implementation("org.springframework.boot:spring-boot-starter-test")
                implementation("org.springframework.boot:spring-boot-testcontainers")
                implementation("org.testcontainers:testcontainers-postgresql")
                implementation(libs.spock.spring)
                implementation("org.apache.groovy:groovy-json")
            }
            targets.all {
                testTask.configure {
                    shouldRunAfter(tasks.test)
                    options {
                        (this as JUnitPlatformOptions).excludeTags("benchmark")
                    }
                }
            }
        }
    }
}

val integrationTestSourceSet = sourceSets["integrationTest"]

tasks.register<Test>("benchmarkTest") {
    description = "Runs measurements of configurations we do not ship, e.g. the pool without a bulkhead."
    group = "verification"
    testClassesDirs = integrationTestSourceSet.output.classesDirs
    classpath = integrationTestSourceSet.runtimeClasspath
    useJUnitPlatform {
        includeTags("benchmark")
    }
}

configurations.named("integrationTestImplementation") {
    extendsFrom(configurations.implementation.get())
}

configurations.named("integrationTestRuntimeOnly") {
    extendsFrom(configurations.runtimeOnly.get())
}

tasks.named("check") {
    dependsOn(testing.suites.named("integrationTest"))
}

tasks.bootJar {
    archiveFileName = "app.jar"
}
