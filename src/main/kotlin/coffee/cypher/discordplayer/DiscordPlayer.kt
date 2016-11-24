package coffee.cypher.discordplayer

import com.google.common.io.CountingOutputStream
import org.cfg4j.provider.ConfigurationProvider
import org.cfg4j.provider.ConfigurationProviderBuilder
import org.cfg4j.provider.GenericType
import org.cfg4j.source.files.FilesConfigurationSource
import org.mapdb.Atomic
import org.mapdb.DB
import org.mapdb.DBMaker
import org.slf4j.LoggerFactory
import sx.blah.discord.api.ClientBuilder
import sx.blah.discord.api.events.Event
import sx.blah.discord.api.events.EventDispatcher
import sx.blah.discord.api.events.IListener
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
    private val dispositionMatcher = "(?i)filename=\"([^\"]+)\"".toRegex()

    private inline fun <reified T : Event> EventDispatcher.on(noinline callback: T.() -> Unit) = registerListener(IListener(callback))

    private inline fun <reified T : Event> EventDispatcher.once(noinline callback: T.() -> Unit) = registerTemporaryListener(IListener(callback))

    private fun IMessage.respond(message: String, mention: Boolean = false): IMessage = channel.sendMessage("${if (mention) author.mention() else ""} $message")

    private inline fun <reified T> ConfigurationProvider.get(name: String): T = getProperty(name, object : GenericType<T>() {})

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

    lateinit var db: DB
    lateinit var musicList: MutableSet<MusicFile>
    lateinit var playlists: MutableSet<Playlist>
    lateinit var musicFolder: File
    lateinit var nextIndex: Atomic.Integer
    lateinit var nextPLIndex: Atomic.Integer

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

        db = DBMaker.fileDB(config.get<String>("database.file")).transactionEnable().closeOnJvmShutdown().make()
        musicList = db.hashSet("music", MusicFile.Serializer).counterEnable().createOrOpen()
        playlists = db.hashSet("playlists", Playlist.Serializer).counterEnable().createOrOpen()
        musicFolder = File(db.atomicString("music.folder", "music").createOrOpen().get())
        nextIndex = db.atomicInteger("index", 1).createOrOpen()
        nextPLIndex = db.atomicInteger("plIndex", 1).createOrOpen()
        populateDB()

        Runtime.getRuntime().addShutdownHook(Thread {
            stop()
        })
    }

    fun populateDB() {
        val old = musicList.toSet()

        musicFolder.listFiles()
                .asSequence()
                .filter { old.none { f -> f.path == it.relativeTo(musicFolder).path } }
                .forEach { addFile(it) }

        db.commit()
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

            "list" -> message.respond(musicList.sortedBy(MusicFile::index).joinToString("\n").run {
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
                musicList.findIndex(words[1].toInt())?.let {
                    removeFile(it)
                    playlists.forEach { p -> p.tracks -= it.index }
                    File(musicFolder, it.path).delete()
                }

                db.commit()
            }

            "find" -> {
                val key = commandText.substringAfter(words[0]).trim().toLowerCase()
                /*if (key.isBlank()) {
                    message.respond("No search query provided")
                    return
                }*/
                val found = musicList.filter { key in it.toString().toLowerCase() }

                if (found.isEmpty()) {
                    message.respond("No matching tracks found")
                } else {
                    var extra = ""
                    var result = ""
                    found.forEach {
                        if (it.toString().length + result.length < 1800) {
                            result += it.toString() + "\n"
                        } else {
                            extra = "\n Found so much. Can't show them all..."
                        }
                    }
                    message.respond("${found.size} match${if (found.size > 1) "es" else ""} found:\n```\n$result```$extra")
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
                        player?.queue(File(musicFolder, musicList.findIndex(it.toInt())!!.path))
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
                    player?.playlist?.take(5)?.forEach {
                        val a = it.metadata?.get("file")
                        if (a is File) {
                            val found = musicList.filter { File(musicFolder, it.path).path == a.path }
                            if (found.size == 1) {
                                message.respond(found[0].toString())
                            } else {
                                message.respond("I dunno :(")
                            }
                        } else {
                            message.respond("I dunno :(")
                        }
                    }
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
                            val file = musicList.findIndex(it.toInt())
                            player?.queue(File(musicFolder, file!!.path))
                            addedList.add(file.toString())
                        }
                        message.respond(addedList.joinToString(prefix = "Added :\n", postfix = "", separator = "\n"))
                    }
                }
            }

            "queue_all" -> {
                val channel = message.author.connectedVoiceChannels.firstOrNull {
                    it.guild == message.guild
                }

                if (channel == null) {
                    message.respond("You need to join a voice channel to play music")
                } else {
                    if (!channel.isConnected) {
                        channel.join()
                    }
                    musicList.forEach {
                        player?.queue(File(musicFolder, it.path))
                    }
                }
            }

            "file", "move", "rename" -> {
                val newName = commandText.substringAfter(words[1]).trim()
                musicList.findIndex(words[1].toInt())?.let {
                    musicList.remove(it)
                    Files.move(Paths.get(musicFolder.path, it.path), Paths.get(musicFolder.canonicalPath, newName))
                    musicList.add(MusicFile(it.index, it.artist, it.name, File(musicFolder, newName).relativeTo(musicFolder).path))
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
                musicList.findIndex(words[1].toInt())?.let {
                    musicList.remove(it)
                    musicList.add(MusicFile(it.index, commandText.substringAfter(words[1]).trim(), it.name, it.path))
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
                musicList.findIndex(words[1].toInt())?.let {
                    musicList.remove(it)
                    musicList.add(MusicFile(it.index, it.artist, commandText.substringAfter(words[1]).trim(), it.path))
                }
                db.commit()
            }

            "create_playlist" -> {
                val name = commandText.substringAfter(words[0]).trim()
                val lists = playlists.toList()

                if (lists.any { it.name == name }) {
                    message.respond("Playlist with that name already exists")
                } else {
                    val new = Playlist(nextPLIndex.get(), name)
                    playlists.add(new)
                    message.respond("Playlist created: $new")

                    if (lists.size == nextPLIndex.get()) {
                        nextPLIndex.andIncrement
                    } else {
                        var next = nextPLIndex.get() + 1
                        val set = lists.associateBy { it.index }
                        while (set[next] != null) {
                            next++
                        }

                        nextPLIndex.set(next)
                    }
                }

                db.commit()
            }

            "playlists" -> message.respond(playlists.sortedBy(Playlist::index).joinToString("\n").run {
                db.commit()
                if (isEmpty())
                    "No playlists available"
                else
                    this
            })

            "playlist_add" -> {
                val list = playlists.findIndex(words[1].toInt()) ?: return
                list.tracks += words.drop(2).map(String::toInt)
                playlists.remove(list)
                playlists.add(list)
                db.commit()
            }

            "playlist_remove" -> {
                val list = playlists.findIndex(words[1].toInt()) ?: return
                list.tracks -= words.drop(2).map(String::toInt)
                playlists.remove(list)
                playlists.add(list)
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
                    playlists.findIndex(words[1].toInt())?.tracks?.forEach {
                        player?.queue(File(musicFolder, musicList.findIndex(it)!!.path))
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

    fun addFile(file: File): MusicFile {
        val added = MusicFile(nextIndex.get(), null, null, file.relativeTo(musicFolder).path)
        musicList.add(added)
        if (musicList.size == nextIndex.get()) {
            nextIndex.andIncrement
        } else {
            var next = nextIndex.get() + 1
            val set = musicList.toSet().associateBy { it.index }
            while (set[next] != null) {
                next++
            }

            nextIndex.set(next)
        }

        db.commit()

        return added
    }

    fun removeFile(file: MusicFile) {
        val list = musicList.toList()

        if (file in list) {
            musicList.remove(file)
            nextIndex.set(Math.min(file.index, nextIndex.get()))
        }
        db.commit()
    }

    fun stop() {
        open = false
        db.close()
        client.logout()
    }

    fun response(progress: Int) = if (progress >= 0)
        "Loading file: `[${"■".repeat(progress)}${" ".repeat(5 - progress)}]`"
    else
        "Loading file: progress unknown"

    fun loadFile(link: String, message: IMessage) {
        val connection = URL(link).openConnection()
        if (connection is HttpURLConnection && connection.responseCode / 100 != 2) {
            message.respond("Could not open connection: ${connection.responseCode} ${connection.responseMessage}")
        } else {
            //DEBUG: message.respond(connection.headerFields.toString())
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
                var current = File(musicFolder, name)
                val end = if (current.extension.isEmpty()) "" else ".${current.extension}"
                var count = 1

                while (current.exists()) {
                    current = File(musicFolder, "${current.nameWithoutExtension}(${count++})$end")
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
                val new = addFile(file)
                message.respond("Saved as ($new)")
            } catch (e: UnsupportedAudioFileException) {
                message.respond("This audio format is not supported\nPlease try one of the following: wav, mp3")
                file.delete()
            }
        }
    }
}