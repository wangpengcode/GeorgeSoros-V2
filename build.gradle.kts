import org.gradle.api.tasks.Exec

plugins {
    kotlin("jvm") version "2.0.21"
    kotlin("plugin.spring") version "2.0.21"
    kotlin("plugin.jpa") version "2.0.21"
    id("org.springframework.boot") version "3.4.1"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "com.soros"
version = "0.1.0"

// SB 3.4.1 托管 testcontainers 1.20.4：其 docker-java 用 v1.32 API 协商，本机 Docker 29
// 已砍掉 <1.44 的旧 API（curl 实测 /v1.32→400、/v1.44→200），必须升 1.21.x
extra["testcontainers.version"] = "1.21.4"

kotlin {
    jvmToolchain(21) // 路径由 gradle.properties 的 installations.paths 显式指定（brew openjdk@21 keg-only）
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-actuator") // §13.3 /actuator/health + metrics
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-reactor")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql") // Flyway 10+ PostgreSQL 独立模块
    runtimeOnly("org.postgresql:postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:postgresql")
    testImplementation("org.testcontainers:junit-jupiter")
}

tasks.test {
    useJUnitPlatform()
}

// 命名字典 CI 校验（§2.4 铁律的机器兜底）：
// schema.sql 全部列名 ↔ docs/design/naming-dictionary.md 第五节枚举表双向 diff，不一致即构建失败。
val namingCheck by tasks.registering(Exec::class) {
    group = "verification"
    description = "schema.sql 与命名字典枚举表一致性校验（新列先增册再用名）"
    commandLine("python3", "scripts/check_naming.py")
}
tasks.named("check") {
    dependsOn(namingCheck)
}
