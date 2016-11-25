package coffee.cypher.discordplayer

import com.google.common.io.CountingOutputStream
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
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.*
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.UnsupportedAudioFileException
import kotlin.concurrent.thread
import kotlin.system.exitProcess

class DiscordPlayer(configFile: Path) {
    constructor(configFile: String) : this(Paths.get(configFile))
    constructor(configFile: File) : this(configFile.toPath())

    companion object {
        val LOG = LoggerFactory.getLogger("Player Log")
    }

    val config = ConfigurationProviderBuilder()
            .withConfigurationSource(FilesConfigurationSource { listOf(configFile.toAbsolutePath()) })
            .build()!!

    val client = ClientBuilder().withToken(config.get("token")).build()!!
    var open = false
    val db = MusicDB(config.get<String>("database.file"))

    fun start() {
        client.login()

        with(client.dispatcher) {
            on<MentionEvent> {
                val commandText = message.content.substringAfter("<@${client.ourUser.id}>").trim()

                processCommand(commandText, message)
            }

            on<MessageReceivedEvent> {
                if (message.content.trim().matches("^${Regex.escape(config.get("command.marker"))}\\S+[\\S\\s]+".toRegex())) {
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

        //TODO: INIT DB

        Runtime.getRuntime().addShutdownHook(Thread {
            stop()
        })
    }

    fun processCommand(commandText: String, message: IMessage) {
        val words = commandText.split(' ')

        val player = message.guild?.let { AudioPlayer.getAudioPlayerForGuild(it) }

        when (words[0]) {
            "leave", "GTFO", "gtfo" -> {
                client.connectedVoiceChannels.find { it.guild == message.guild }?.leave()
                message.respond("bb")
            }

            "next", "skip" -> {
                player?.skip()
            }

            "pause" -> player?.isPaused = true

            "resume" -> player?.isPaused = false

            "list" -> message.respond(db.musicList.sortedBy(MusicFile::index).joinToString("\n").run {
                if (isEmpty())
                    "No playlists available"
                else
                    this
            })

            "quit" -> {
                stop()
                exitProcess(0)
            }

            "stop" -> {
                player?.isPaused = true
                player?.currentTrack?.rewindTo(0)
            }

            "volume" -> {
                if (words.size == 1) {
                    player?.let { message.respond(it.volume.toString()) }
                } else when (words[1]) {
                    "+" -> player?.let { it.volume += words[2].toFloat() }
                    "-" -> player?.let { it.volume -= words[2].toFloat() }
                    else -> player?.let { it.volume = words[1].toFloat() }
                }
            }

            "remove" -> {
                db.musicList.findIndex(words[1].toInt())?.let {
                    db.removeFile(it)
                    db.playlists.forEach { p -> p.tracks -= it.index }
                    File(db.musicFolder, it.path).delete()
                }

                db.commit()
            }

            "find" -> {
                val key = commandText.substringAfter(words[0]).trim().toLowerCase()
                val found = db.musicList.filter { key in it.toString().toLowerCase() }.sortedBy(MusicFile::index)

                if (found.isEmpty()) {
                    message.respond("No matching tracks found")
                } else {
                    message.respondList(found.sortedBy(MusicFile::index).map { it.toString() },
                            prefix = "${found.size} match${if (found.size > 1) "es" else ""} found:\n```\n",
                            suffix = "```")
                }
            }

            "current" -> {
                val a = player?.currentTrack?.metadata?.get("file")
                if (a is File) {
                    message.respond(a.name)
                } else {
                    message.respond("I dunno :(")
                }
            }

            "queue_now" -> {
                val queue = ArrayList<AudioPlayer.Track>()
                player?.playlist?.forEach {
                    queue.add(it)
                }
                val channel = message.author.connectedVoiceChannels.firstOrNull {
                    it.guild == message.guild
                }

                if (channel == null) {
                    message.respond("You need to join a voice channel to play music")
                } else {
                    player?.clear()
                    if (!channel.isConnected) {
                        channel.join()
                    }
                    words.drop(1).forEach {
                        player?.queue(File(db.musicFolder, db.musicList.findIndex(it.toInt())!!.path))
                    }
                    queue.forEach {
                        player?.queue(it)
                    }
                }
            }

            "queue_clear" -> {
                player?.clear()
                client.connectedVoiceChannels.find { it.guild == message.guild }?.leave()
            }

            "queue" -> {
                if (words.size == 1) {
                    if (player?.playlist?.size == 0) {
                        message.respond("Queue is empty")
                    }

                    message.respondList(player?.playlist?.map {
                        val a = it.metadata?.get("file") as File
                        val found = db.musicList.filter { File(db.musicFolder, it.path).path == a.path }
                        if (found.size == 1) {
                            found[0].toString()
                        } else {
                            "I dunno :("
                        }
                    } ?: ArrayList<String>())

                } else {
                    val channel = message.author.connectedVoiceChannels.firstOrNull {
                        it.guild == message.guild
                    }

                    if (channel == null) {
                        message.respond("You need to join a voice channel to play music")
                    } else {
                        if (!channel.isConnected) {
                            channel.join()
                            player?.let { it.volume = 0.1F }
                        }
                        val addedList = ArrayList<String>()
                        words.drop(1).forEach {
                            val file = db.musicList.findIndex(it.toInt())
                            player?.queue(File(db.musicFolder, file!!.path))
                            addedList.add(file.toString())
                        }
                        message.respondList(addedList, prefix = "Added :\n")
                    }
                }
            }

            "file", "move", "rename" -> {
                val newName = commandText.substringAfter(words[1]).trim()
                db.musicList.findIndex(words[1].toInt())?.let {
                    db.musicList.remove(it)
                    Files.move(Paths.get(db.musicFolder.path, it.path), Paths.get(db.musicFolder.canonicalPath, newName))
                    db.musicList.add(MusicFile(it.index, it.artist, it.name, File(db.musicFolder, newName).relativeTo(db.musicFolder).path))
                }
                db.commit()
            }

            "load" -> {
                loadFile(words[1], message)
            }

            "load_youtube" -> {
                loadFile("http://www.youtubeinmp3.com/fetch/?video=" + words[1], message)
            }

            "leave_channel" -> client.connectedVoiceChannels.find { it.guild == message.guild }?.leave()

            "artist" -> {
                db.musicList.findIndex(words[1].toInt())?.let {
                    db.musicList.remove(it)
                    db.musicList.add(MusicFile(it.index, commandText.substringAfter(words[1]).trim(), it.name, it.path))
                }
                db.commit()
            }

            "shuffle" -> {
                player?.shuffle()
            }

            "loop" -> {
                player?.setLoop(true)
            }

            "no-loop" -> {
                player?.setLoop(false)
            }

            "name" -> {
                db.musicList.findIndex(words[1].toInt())?.let {
                    db.musicList.remove(it)
                    db.musicList.add(MusicFile(it.index, it.artist, commandText.substringAfter(words[1]).trim(), it.path))
                }
                db.commit()
            }

            "create_playlist" -> {
                val name = commandText.substringAfter(words[0]).trim()
                val lists = db.playlists.toList()

                if (lists.any { it.name == name }) {
                    message.respond("Playlist with that name already exists")
                } else {
                    val new = Playlist(db.nextPLIndex.get(), name)
                    db.playlists.add(new)
                    message.respond("Playlist created: $new")

                    if (lists.size == db.nextPLIndex.get()) {
                        db.nextPLIndex.andIncrement
                    } else {
                        var next = db.nextPLIndex.get() + 1
                        val set = lists.associateBy { it.index }
                        while (set[next] != null) {
                            next++
                        }

                        db.nextPLIndex.set(next)
                    }
                }

                db.commit()
            }

            "playlists" -> message.respond(db.playlists.sortedBy(Playlist::index).joinToString("\n").run {
                db.commit()
                if (isEmpty())
                    "No playlists available"
                else
                    this
            })

            "playlist_add" -> {
                val list = db.playlists.findIndex(words[1].toInt()) ?: return
                list.tracks += words.drop(2).map(String::toInt)
                db.playlists.remove(list)
                db.playlists.add(list)
                db.commit()
            }

            "playlist_remove" -> {
                val list = db.playlists.findIndex(words[1].toInt()) ?: return
                list.tracks -= words.drop(2).map(String::toInt)
                db.playlists.remove(list)
                db.playlists.add(list)
                db.commit()
            }

            "queue_playlist" -> {
                val channel = message.author.connectedVoiceChannels.firstOrNull {
                    it.guild == message.guild
                }

                if (channel == null) {
                    message.respond("You need to join a voice channel to play music")
                } else {
                    if (!channel.isConnected) {
                        channel.join()
                        player?.let { it.volume = 0.1F }
                    }
                    db.playlists.findIndex(words[1].toInt())?.tracks?.forEach {
                        player?.queue(File(db.musicFolder, db.musicList.findIndex(it)!!.path))
                    }
                }
            }

            "after_this" -> {
                client.dispatcher.once<TrackFinishEvent> {
                    processCommand(commandText.substringAfter(words[0]).trim(), message)
                }
            }

            "{" -> commandText.drop(1).substringBeforeLast('}').split('|').forEach { processCommand(it.trim(), message) }

            else -> message.respond("Unknown command: ${words[0]}")
        }
    }

    fun Collection<MusicFile>.findIndex(index: Int) = find { it.index == index }

    fun Collection<Playlist>.findIndex(index: Int) = find { it.index == index }

    fun stop() {
        open = false
        db.close()
        client.logout()
    }

    fun loadFile(link: String, message: IMessage) {
        fun response(progress: Int) = if (progress >= 0)
            "Loading file: `[${"■".repeat(progress)}${" ".repeat(5 - progress)}]`"
        else
            "Loading file: progress unknown"

        val connection = URL(link).openConnection()
        if (connection is HttpURLConnection && connection.responseCode / 100 != 2) {
            message.respond("Could not open connection: ${connection.responseCode} ${connection.responseMessage}")
        } else {
            //DEBUG: message.respondList(connection.headerFields.toString())
            if (!connection.getHeaderField("Content-Type").contains("audio")) {
                message.respond("Something went wrong.:BrokeBack:")
                return
            }
            val name = connection.getHeaderField("Content-Disposition")
                    ?.let { dispositionMatcher.find(it)?.groupValues?.get(1) }
                    ?: link.substringAfterLast('/').substringBefore('?')
            val size = connection.getHeaderFieldLong("Content-Length", -1)
            if (size > 64 * (1 shl 20)) {
                message.respond("File is larger than 64 MB, try different format or reduce bitrate")
                return
            }

            val progress = message.respond(response(if (size > 0) 0 else -1))

            val file = run {
                var current = File(db.musicFolder, name)
                val end = if (current.extension.isEmpty()) "" else ".${current.extension}"
                var count = 1

                while (current.exists()) {
                    current = File(db.musicFolder, "${current.nameWithoutExtension}(${count++})$end")
                }

                current
            }

            val stream = CountingOutputStream(file.outputStream())
            thread(start = true) {
                var last = 0
                do {
                    val new = Math.round((stream.count.toDouble() / size.toDouble()) * 5).toInt()
                    if (new != last) {
                        last = new
                        progress.edit(response(new))
                    }
                    Thread.sleep(200)
                } while (stream.count < size)

                if (last < 5) {
                    progress.edit(response(5))
                }

            }

            connection.inputStream.copyTo(stream)
            stream.close()
            try {
                AudioSystem.getAudioInputStream(file)
                val new = db.addFile(file)
                message.respond("Saved as ($new)")
            } catch (e: UnsupportedAudioFileException) {
                message.respond("This audio format is not supported\nPlease try one of the following: wav, mp3")
                file.delete()
            }
        }
    }
}