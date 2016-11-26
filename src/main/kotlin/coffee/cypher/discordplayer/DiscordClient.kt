package coffee.cypher.discordplayer

import org.cfg4j.provider.ConfigurationProviderBuilder
import org.cfg4j.source.files.FilesConfigurationSource
import org.slf4j.LoggerFactory
import sx.blah.discord.api.ClientBuilder
import sx.blah.discord.handle.impl.events.MentionEvent
import sx.blah.discord.handle.impl.events.MessageReceivedEvent
import sx.blah.discord.handle.impl.events.ReadyEvent
import sx.blah.discord.handle.obj.IMessage
import sx.blah.discord.handle.obj.IVoiceChannel
import sx.blah.discord.util.audio.AudioPlayer
import sx.blah.discord.util.audio.events.AudioPlayerEvent
import sx.blah.discord.util.audio.events.TrackFinishEvent
import sx.blah.discord.util.audio.events.TrackSkipEvent
import java.io.File
import java.io.IOException
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.system.exitProcess

class DiscordClient(val configFile: Path) {
    constructor(configFile: String) : this(Paths.get(configFile))
    constructor(configFile: File) : this(configFile.toPath())

    companion object {
        val LOG = LoggerFactory.getLogger("Player Log")
    }

    val discordPlayer = DiscordPlayer(configFile)

    val config = ConfigurationProviderBuilder()
            .withConfigurationSource(FilesConfigurationSource { listOf(configFile.toAbsolutePath()) })
            .build()!!

    val client = ClientBuilder().withToken(config.get("token")).build()!!
    var open = false

    fun start() {
        client.login()

        with(client.dispatcher) {
            on<MentionEvent> {
                val commandText = message.content.substringAfter("<@${client.ourUser.id}>").trim()

                processCommand(commandText, message)
            }

            on<MessageReceivedEvent> {
                if (message.content.trim().matches("^${kotlin.text.Regex.escape(config.get("command.marker"))}\\S+[\\S\\s]+".toRegex())) {
                    processCommand(message.content.trim().drop(config.get<String>("command.marker").length), message)
                }
            }

            on<ReadyEvent> {
                client.connectedVoiceChannels.forEach(IVoiceChannel::leave)
                open = true
            }

            val handler = { e: AudioPlayerEvent ->
                if (e.player.playlistSize == 0) {
                    client.connectedVoiceChannels.find { it.guild == e.player.guild }?.leave()
                }
            }

            on<TrackFinishEvent>(handler)
            on<TrackSkipEvent>(handler)
        }

        Runtime.getRuntime().addShutdownHook(Thread {
            stop()
        })
    }

    fun IMessage.joinChannel(ifJoined: () -> Unit) {
        val channel = author.connectedVoiceChannels.firstOrNull {
            it.guild == guild
        }
        if (channel == null) {
            respond("You need to join a voice channel to play music")
        } else {
            if (!channel.isConnected) {
                channel.join()
            }
            ifJoined()
        }
    }

    fun processCommand(commandText: String, message: IMessage) {
        val words = commandText.split(' ')

        val player = message.guild?.let { AudioPlayer.getAudioPlayerForGuild(it) }
        discordPlayer.player = player
        discordPlayer.respond = { text -> message.respond(text) }

        when (words[0]) {
            "leave", "GTFO", "gtfo", "leave_channel" -> {
                client.connectedVoiceChannels.find { it.guild == message.guild }?.leave()
                message.respond("bb")
            }

            "next", "skip" -> player?.skip()

            "pause" -> player?.isPaused = true

            "resume" -> player?.isPaused = false

            "list" -> discordPlayer.list()

            "quit" -> {
                stop()
                exitProcess(0)
            }

            "stop" -> discordPlayer.stop()

            "volume" ->
                if (words.size == 1) {
                    message.respond(discordPlayer.volume.toString())
                } else when (words[1]) {
                    "+" -> discordPlayer.volume += words[2].toFloat()
                    "-" -> discordPlayer.volume -= words[2].toFloat()
                    else -> discordPlayer.volume = words[1].toFloat()
                }

            "remove" -> discordPlayer.remove(words[1].toInt())

            "find" -> discordPlayer.find(commandText.substringAfter(words[0]))

            "current" -> discordPlayer.printCurrentSong()

            "queue_now" -> message.joinChannel { discordPlayer.queueNow(words.drop(1).map(String::toInt)) }

            "queue_clear" -> {
                discordPlayer.queueClear()
                client.connectedVoiceChannels.find { it.guild == message.guild }?.leave()
            }

            "queue" ->
                if (words.size == 1)
                    discordPlayer.printQueue()
                else
                    message.joinChannel { discordPlayer.queue(words.drop(1).map(String::toInt)) }

            "file", "move", "rename" -> discordPlayer.renameFile(words[1].toInt(), commandText.substringAfter(words[1]).trim())

            "artist" -> discordPlayer.renameArtist(words[1].toInt(), commandText.substringAfter(words[1]).trim())

            "name" -> discordPlayer.renameSong(words[1].toInt(), commandText.substringAfter(words[1]).trim())

            "load" -> discordPlayer.loadFile(words[1], message)

            "load_youtube", "youtube" -> try {
                discordPlayer.loadFile("http://www.youtubeinmp3.com/fetch/?video=" + words[1], message)
            } catch (e: IOException) {
                message.respond("Try this link: " + "http://www.youtubeinmp3.com/fetch/?video=" + words[1])
            }

            "shuffle" -> player?.shuffle()

            "loop" -> {
                player?.setLoop(true)
            }

            "no-loop" -> {
                player?.setLoop(false)
            }

            "create_playlist" -> discordPlayer.newPlaylist(commandText.substringAfter(words[0]).trim())

            "playlists" -> discordPlayer.printPlaylists()

            "playlist_add" -> discordPlayer.addToPlayList(words[1].toInt(), words.drop(2).map(String::toInt))

            "playlist_remove" -> discordPlayer.removeFromPlayList(words[1].toInt(), words.drop(2).map(String::toInt))

            "queue_playlist" -> message.joinChannel { discordPlayer.queuePlaylist(words[1].toInt()) }

            "after_this" -> client.dispatcher.once<TrackFinishEvent> {
                processCommand(commandText.substringAfter(words[0]).trim(), message)
            }

            "{" -> commandText.drop(1).substringBeforeLast('}').split('|').forEach { processCommand(it.trim(), message) }

            else -> message.respond("Unknown command: ${words[0]}")
        }
    }

    fun stop() {
        open = false
        discordPlayer.close()
        client.logout()
    }
}