pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        exclusiveContent {
            forRepository {
                ivy {
                    name = "sherpaRelease"
                    url = uri("https://github.com/k2-fsa/sherpa-onnx/releases/download")
                    patternLayout { artifact("v[revision]/[artifact]-[revision].[ext]") }
                    metadataSources { artifact() }
                }
            }
            filter { includeGroup("com.k2fsa") }
        }
        exclusiveContent {
            forRepository {
                ivy {
                    name = "sileroWeights"
                    url = uri("https://raw.githubusercontent.com/snakers4/silero-vad")
                    patternLayout { artifact("[revision]/src/silero_vad/data/[artifact].[ext]") }
                    metadataSources { artifact() }
                }
            }
            filter { includeGroup("org.silero") }
        }
    }
}

rootProject.name = "PhoneMemory"
include(":app", ":core")
