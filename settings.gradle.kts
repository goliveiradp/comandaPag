pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Repositório Maven da biblioteca PlugPagServiceWrapper (PagBank).
        // Mesmo conteúdo de https://github.com/pagseguro/PlugPagServiceWrapper/raw/master
        exclusiveContent {
            forRepository {
                maven("https://raw.githubusercontent.com/pagseguro/PlugPagServiceWrapper/master")
            }
            filter { includeGroupByRegex("br\\.com\\.uol\\.pagseguro.*") }
        }
    }
}

rootProject.name = "comandaPag"
include(":core", ":app")
