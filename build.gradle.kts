import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("org.jetbrains.intellij.platform") version "2.18.1"
    kotlin("jvm") version "2.1.0"
}

group = "dev.begeistert.pymcu"
version = providers.gradleProperty("pluginVersion").getOrElse("0.1.0")

kotlin {
    jvmToolchain(21)
}

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        // useInstaller = false pulls the Maven artifact from the IntelliJ
        // repository instead of the OS installer from the download CDN, which
        // only carries the last few releases.
        pycharmCommunity(providers.gradleProperty("platformVersion").getOrElse("2024.3.6")) {
            useInstaller = false
        }
        bundledPlugin("PythonCore")
        testFramework(TestFrameworkType.Platform)
        pluginVerifier()
    }
    testImplementation("junit:junit:4.13.2")
}

intellijPlatform {
    pluginConfiguration {
        id = "dev.begeistert.pymcu"
        name = "PyMCU"
        version = project.version.toString()

        description = providers.fileContents(
            layout.projectDirectory.file("src/main/resources/META-INF/description.html")
        ).asText

        changeNotes = providers.fileContents(
            layout.projectDirectory.file("src/main/resources/META-INF/change-notes.html")
        ).asText

        vendor {
            name = "PyMCU"
            email = "begeistert@gmail.com"
            url = "https://github.com/PyMCU/PyMCU"
        }

        ideaVersion {
            sinceBuild = providers.gradleProperty("pluginSinceBuild").getOrElse("243")
            // Left open: the plugin binds only to stable platform API, so pinning
            // an upper bound would force a release for every IDE update.
            untilBuild = provider { null }
        }
    }

    // The plugin ships no searchable settings beyond a single page; building the
    // index costs a full IDE start per build for no benefit.
    buildSearchableOptions = false

    pluginVerification {
        ides {
            // Named builds rather than recommended()/select(): those resolve to
            // every release in the supported range, which is tens of gigabytes of
            // IDE downloads for a plugin whose API surface is this small. These
            // three cover the ends and the middle of the supported range.
            create(IntelliJPlatformType.PyCharmCommunity, "2024.3.6")
            create(IntelliJPlatformType.PyCharmCommunity, "2025.2.6")
            create(IntelliJPlatformType.PyCharmCommunity, "2026.1.5")
        }
    }

    signing {
        certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN")
        privateKey = providers.environmentVariable("PRIVATE_KEY")
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
    }

    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
        // Any version containing a hyphen (0.1.0-eap.1) publishes to a pre-release
        // channel rather than to everyone on stable.
        channels = listOf(
            project.version.toString().substringAfter('-', "").substringBefore('.')
                .ifEmpty { "default" }
        )
    }
}

tasks {
    test {
        useJUnit()
    }

    // Open a project straight away instead of landing on the welcome screen:
    //   ./gradlew runIde -PrunIdeProject=/path/to/a/pymcu/project
    // The tool window panels only exist once a project is open, so this is the
    // difference between smoke-testing the plugin and smoke-testing the IDE.
    runIde {
        providers.gradleProperty("runIdeProject").orNull?.let { args(it) }
    }
}
