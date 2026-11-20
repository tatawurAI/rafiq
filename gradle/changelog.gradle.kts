// Release helper: turns the fragments in changelog.d/ into a new CHANGELOG.md section.
//
// Apply it from the root build with: apply(from = "gradle/changelog.gradle.kts")
// Run it with:                       ./gradlew assembleChangelog -Pversion=X.Y.Z
// Optional:                          -PreleaseDate=YYYY-MM-DD (defaults to today)
//
// Fragments are named <slug>.<category>.md and hold one sentence each. The task
// writes "## [X.Y.Z] - YYYY-MM-DD" under the Unreleased heading, grouped in Keep a
// Changelog order, then deletes the fragments it consumed.

import java.time.LocalDate

abstract class AssembleChangelog : DefaultTask() {
    @get:Input
    @get:Optional
    abstract val releaseVersion: Property<String>

    @get:Input
    @get:Optional
    abstract val releaseDate: Property<String>

    @get:Internal
    abstract val fragmentsDir: DirectoryProperty

    @get:Internal
    abstract val changelogFile: RegularFileProperty

    private val categories = listOf("added", "changed", "deprecated", "removed", "fixed", "security")
    private val fragmentName = Regex("""[a-z0-9][a-z0-9-]*\.(added|changed|deprecated|removed|fixed|security)\.md""")
    private val semver = Regex("""\d+\.\d+\.\d+(-[0-9A-Za-z.-]+)?""")

    @TaskAction
    fun assemble() {
        val version =
            releaseVersion.orNull
                ?: throw GradleException("Pass the release version, for example -Pversion=1.2.3.")
        if (!semver.matches(version)) {
            throw GradleException("Version '$version' is not X.Y.Z (an optional -suffix is allowed).")
        }
        val date = releaseDate.orNull ?: LocalDate.now().toString()
        LocalDate.parse(date)

        val fragments =
            fragmentsDir
                .get()
                .asFile
                .listFiles { file -> file.isFile && file.name != ".gitkeep" }
                .orEmpty()
                .sortedBy { it.name }
        if (fragments.isEmpty()) {
            throw GradleException("No fragments in changelog.d, so there is nothing to release.")
        }
        val misnamed = fragments.filterNot { fragmentName.matches(it.name) }
        if (misnamed.isNotEmpty()) {
            throw GradleException(
                "Fragments must be named <slug>.<category>.md with category one of $categories. " +
                    "Fix: ${misnamed.joinToString { it.name }}",
            )
        }

        val byCategory = fragments.groupBy { it.name.removeSuffix(".md").substringAfterLast('.') }
        val section =
            buildString {
                append("## [$version] - $date\n")
                for (category in categories) {
                    val files = byCategory[category] ?: continue
                    append("\n### ${category.replaceFirstChar { it.uppercase() }}\n\n")
                    files.forEach { append("- ${sentence(it)}\n") }
                }
            }

        val changelog = changelogFile.get().asFile
        val existing =
            if (changelog.exists()) {
                changelog.readText()
            } else {
                "# Changelog\n\nAll notable changes to this project are documented in this file.\n\n## [Unreleased]\n"
            }
        val lines = existing.lines().toMutableList()
        if (lines.any { it.startsWith("## [$version]") }) {
            throw GradleException("CHANGELOG.md already has a section for $version.")
        }

        val unreleased = lines.indexOfFirst { it.trim() == "## [Unreleased]" }
        val block = section.trimEnd().lines()
        if (unreleased >= 0) {
            lines.addAll(unreleased + 1, listOf("") + block + listOf(""))
        } else {
            val firstRelease = lines.indexOfFirst { it.startsWith("## [") }
            lines.addAll(if (firstRelease >= 0) firstRelease else lines.size, block + listOf(""))
        }
        changelog.writeText(lines.joinToString("\n").replace(Regex("\n{3,}"), "\n\n").trimEnd() + "\n")

        fragments.forEach { it.delete() }
        logger.lifecycle("CHANGELOG.md: added $version with ${fragments.size} entries.")
    }

    private fun sentence(file: File): String {
        val text =
            file
                .readLines()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .joinToString(" ")
                .removePrefix("- ")
                .trim()
        if (text.isEmpty()) throw GradleException("Fragment ${file.name} is empty.")
        return text
    }
}

tasks.register<AssembleChangelog>("assembleChangelog") {
    group = "release"
    description = "Moves changelog.d fragments into a new CHANGELOG.md section. Needs -Pversion=X.Y.Z."
    releaseVersion.set(providers.gradleProperty("version"))
    releaseDate.set(providers.gradleProperty("releaseDate"))
    fragmentsDir.set(rootProject.layout.projectDirectory.dir("changelog.d"))
    changelogFile.set(rootProject.layout.projectDirectory.file("CHANGELOG.md"))
}
