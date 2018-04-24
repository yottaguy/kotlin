
plugins {
    kotlin("jvm")
    id("jps-compatible")
}

jvmTarget = "1.6"

dependencies {
    compile(project(":compiler:frontend.common"))
    compile(project(":compiler:frontend.java"))
    compile(project(":compiler:light-classes"))
    compile(project(":compiler:fir:resolve"))
    compile(project(":compiler:fir:tree"))

    compileOnly(intellijCoreDep()) { includeJars("intellij-core", "annotations") }
}


sourceSets {
    "main" { projectDefault() }
    "test" {}
}