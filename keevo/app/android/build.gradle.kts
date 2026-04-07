allprojects {
    repositories {
        google()
        mavenCentral()
    }
}

val newBuildDir: Directory =
    rootProject.layout.buildDirectory
        .dir("../../build")
        .get()
rootProject.layout.buildDirectory.value(newBuildDir)

subprojects {
    val newSubprojectBuildDir: Directory = newBuildDir.dir(project.name)
    project.layout.buildDirectory.value(newSubprojectBuildDir)
}
subprojects {
    project.evaluationDependsOn(":app")
}

// Force all Android library plugins (e.g. sqlcipher_flutter_libs) to compile
// against SDK 35 so that android:attr/lStar is available at resource link time.
subprojects {
    project.plugins.withType<com.android.build.gradle.LibraryPlugin> {
        project.extensions.configure<com.android.build.gradle.LibraryExtension> {
            compileSdk = 35
        }
    }
}

tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}
