import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.mordant.rendering.TextColors.brightGreen
import com.github.ajalt.mordant.rendering.TextColors.brightYellow
import com.github.ajalt.mordant.table.verticalLayout
import com.github.ajalt.mordant.terminal.Terminal
import data.GitHubImpl
import data.InstallerManifestData
import data.PreviousManifestData
import data.SharedManifestData
import data.YamlConfig
import data.shared.PackageIdentifier.packageIdentifierPrompt
import data.shared.PackageVersion.packageVersionPrompt
import data.shared.Url.installerDownloadPrompt
import input.PromptType
import input.Prompts
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import org.koin.core.component.inject
import schemas.DefaultLocaleManifest
import schemas.InstallerManifest
import schemas.LocaleManifest
import schemas.SchemasImpl
import schemas.TerminalInstance
import schemas.VersionManifest
import kotlin.system.exitProcess

class QuickUpdate : CliktCommand(name = "update"), KoinComponent {
    private val urls: List<String>? by argument("--urls").multiple().optional()

    private val installerManifestData: InstallerManifestData by inject()
    private val sharedManifestData: SharedManifestData by inject()
    private lateinit var previousManifestData: PreviousManifestData

    override fun run(): Unit = runBlocking {
        with(get<TerminalInstance>().terminal) {
            packageIdentifierPrompt()
            previousManifestData = get()
            exitIfNotInWingetPkgs()
            launch {
                packageVersionPrompt()
                previousManifestData.remoteInstallerDataJob.join()
                val remoteInstallers = previousManifestData.remoteInstallerData?.installers
                do {
                    remoteInstallers?.forEachIndexed { index, installer ->
                        println(
                            verticalLayout {
                                cell(brightGreen("Installer Entry ${index.inc()}/${remoteInstallers.size}"))
                                listOf(
                                    PromptType.Architecture to installer.architecture,
                                    PromptType.InstallerType to installer.installerType,
                                    PromptType.Scope to installer.scope,
                                    PromptType.InstallerLocale to installer.installerLocale
                                ).forEach { (promptType, value) ->
                                    value?.let {
                                        cell(brightYellow("${" ".repeat(Prompts.optionIndent)} $promptType: $it"))
                                    }
                                }
                                cell("")
                            }
                        )
                        installerDownloadPrompt()
                        installerManifestData.installers += installer.copy(
                            installerUrl = installerManifestData.installerUrl,
                            installerSha256 = installerManifestData.installerSha256,
                            productCode = installerManifestData.productCode,
                        )
                    }
                } while (
                    (previousManifestData.remoteInstallerData?.installers?.size ?: 0) <
                    installerManifestData.installers.size
                )
                promptToCommit()
            }
        }
    }

    private suspend fun Terminal.promptToCommit() {
        previousManifestData.remoteVersionDataJob.join()
        sharedManifestData.defaultLocale = previousManifestData.remoteVersionData!!.defaultLocale
        previousManifestData.remoteLocaleDataJob.join()
        previousManifestData.remoteDefaultLocaleDataJob.join()
        val githubImpl = get<GitHubImpl>()
        val repository = githubImpl.getWingetPkgsFork(this)
        val ref = githubImpl.createBranch(repository)
        githubImpl.commitFiles(
            repository = repository,
            branch = ref,
            files = listOf(
                githubImpl.installerManifestGitHubPath to previousManifestData.remoteInstallerData?.copy(
                    packageIdentifier = sharedManifestData.packageIdentifier,
                    packageVersion = sharedManifestData.packageVersion,
                    installers = installerManifestData.installers,
                    manifestVersion = "1.4.0"
                )?.let {
                    githubImpl.buildManifestString(get<SchemasImpl>().installerSchema.id) {
                        appendLine(YamlConfig.installer.encodeToString(InstallerManifest.serializer(), it))
                    }
                },
                githubImpl.defaultLocaleManifestGitHubPath to previousManifestData.remoteDefaultLocaleData?.copy(
                    packageIdentifier = sharedManifestData.packageIdentifier,
                    packageVersion = sharedManifestData.packageVersion,
                    manifestVersion = "1.4.0"
                )?.let {
                    githubImpl.buildManifestString(get<SchemasImpl>().defaultLocaleSchema.id) {
                        appendLine(YamlConfig.other.encodeToString(DefaultLocaleManifest.serializer(), it))
                    }
                },
                githubImpl.versionManifestGitHubPath to previousManifestData.remoteVersionData?.copy(
                    packageIdentifier = sharedManifestData.packageIdentifier,
                    packageVersion = sharedManifestData.packageVersion,
                    manifestVersion = "1.4.0"
                )?.let {
                    githubImpl.buildManifestString(get<SchemasImpl>().versionSchema.id) {
                        appendLine(YamlConfig.other.encodeToString(VersionManifest.serializer(), it))
                    }
                }
            ) + previousManifestData.remoteLocaleData?.map { localeManifest ->
                githubImpl.getLocaleManifestGitHubPath(localeManifest.packageLocale) to localeManifest.copy(
                    packageIdentifier = sharedManifestData.packageIdentifier,
                    packageVersion = sharedManifestData.packageVersion,
                    manifestVersion = "1.4.0"
                ).let {
                    githubImpl.buildManifestString(get<SchemasImpl>().localeSchema.id) {
                        appendLine(YamlConfig.other.encodeToString(LocaleManifest.serializer(), it))
                    }
                }
            } as List<Pair<String, String?>>
        )
    }

    private fun Terminal.exitIfNotInWingetPkgs() {
        if (sharedManifestData.isNewPackage) {
            println(
                verticalLayout {
                    cell(
                        brightYellow(
                            "${sharedManifestData.packageIdentifier} is not in the ${GitHubImpl.wingetpkgs} repository."
                        )
                    )
                    cell(brightYellow("Please use the 'new' command to create a new manifest."))
                }
            )
            exitProcess(0)
        }
    }
}