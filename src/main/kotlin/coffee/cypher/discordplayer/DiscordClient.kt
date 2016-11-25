package coffee.cypher.discordplayer

import org.cfg4j.provider.ConfigurationProviderBuilder
import org.cfg4j.source.files.FilesConfigurationSource
import java.io.File
import java.nio.file.Path
import java.nio.file.Paths

class DiscordClient(configFile: Path) {
    constructor(configFile: String) : this(Paths.get(configFile))
    constructor(configFile: File) : this(configFile.toPath())

    val discordPlayer = DiscordPlayer(configFile)

    val config = ConfigurationProviderBuilder()
            .withConfigurationSource(FilesConfigurationSource { listOf(configFile.toAbsolutePath()) })
            .build()!!
}