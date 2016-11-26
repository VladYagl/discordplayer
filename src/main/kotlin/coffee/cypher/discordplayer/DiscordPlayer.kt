package coffee.cypher.discordplayer

import com.google.common.io.CountingOutputStream
import org.cfg4j.provider.ConfigurationProviderBuilder
import org.cfg4j.source.files.FilesConfigurationSource
import sx.blah.discord.handle.obj.IMessage
import sx.blah.discord.util.audio.AudioPlayer
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.*
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.UnsupportedAudioFileException
import kotlin.concurrent.thread

class DiscordPlayer(configFile: Path) {

    val config = ConfigurationProviderBuilder()
            .withConfigurationSource(FilesConfigurationSource { listOf(configFile.toAbsolutePath()) })
            .build()!!
    val db = MusicDB(config.get<String>("database.file"))
    var player: AudioPlayer? = null
    var respond: ((String) -> Unit) = {}
    var volume: Float
        get(): Float {
            return player?.volume ?: 0F
        }
        set(volume) {
            player?.volume = volume
        }

    fun respondList(list: List<String>, prefix: String = "", suffix: String = "", separator: String = "\n") {
        var extra = ""
        var result = ""

        var count = 0
        list.forEach {
            if (result.length + it.length < 1800) {
                count++
                result += it + separator
            } else {
                extra = "\n And ${list.size - count} more..."
            }
        }
        respond("$prefix$result$suffix$extra")
    }

    fun Collection<MusicFile>.findIndex(index: Int) = find { it.index == index }

    fun Collection<Playlist>.findIndex(index: Int) = find { it.index == index }

    fun close() {
        db.close()
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
                throw IOException()
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
                respond("Saved as ($new)")
            } catch (e: UnsupportedAudioFileException) {
                message.respond("This audio format is not supported\nPlease try one of the following: wav, mp3")
                file.delete()
            }
        }
    }

    fun list() {
        respond(db.musicList.sortedBy(MusicFile::index).joinToString("\n").run {
            if (isEmpty())
                "No playlists available"
            else
                this
        })
    }

    fun stop() {
        player?.isPaused = true
        player?.currentTrack?.rewindTo(0)
    }

    fun remove(index: Int) {
        var name: String = ""
        db.musicList.findIndex(index)?.let {
            name = it.toString()
            db.removeFile(it)
            db.playlists.forEach { p -> p.tracks -= it.index }
            File(db.musicFolder, it.path).delete()
        }

        db.commit()
        respond("Removed song: " + name)
    }

    fun find(text: String) {
        val key = text.trim().toLowerCase()
        val found = db.musicList.filter { key in it.toString().toLowerCase() }.sortedBy(MusicFile::index)

        if (found.isEmpty()) {
            respond("No matching tracks found")
        } else {
            respondList(found.sortedBy(MusicFile::index).map { it.toString() },
                    prefix = "${found.size} match${if (found.size > 1) "es" else ""} found:\n```\n",
                    suffix = "```")
        }
    }

    fun printCurrentSong() {
        val a = player?.currentTrack?.metadata?.get("file")
        if (a is File) {
            respond(a.name)
        } else {
            respond("I dunno :(")
        }
    }

    fun printQueue() {
        if (player?.playlist?.size == 0) {
            respond("Queue is empty")
        }

        respondList(player?.playlist?.map {
            val a = it.metadata?.get("file") as File
            val found = db.musicList.filter { File(db.musicFolder, it.path).path == a.path }
            if (found.size == 1) {
                found[0].toString()
            } else {
                "I dunno :("
            }
        } ?: ArrayList<String>())
    }

    fun queue(indexes: List<Int>) {
        val addedList = ArrayList<String>()
        indexes.forEach {
            val file = db.musicList.findIndex(it.toInt())
            player?.queue(File(db.musicFolder, file!!.path))
            addedList.add(file.toString())
        }
        respondList(addedList, prefix = "Added :\n")
    }

    fun queueNow(indexes: List<Int>) {
        val queue = ArrayList<AudioPlayer.Track>()
        player?.playlist?.forEach {
            queue.add(it)
        }
        player?.clear()
        indexes.forEach {
            player?.queue(File(db.musicFolder, db.musicList.findIndex(it.toInt())!!.path))
        }
        queue.forEach {
            player?.queue(it)
        }
    }

    fun queueClear() {
        player?.clear()
    }

    fun renameFile(index: Int, newName: String) {
        db.musicList.findIndex(index)?.let {
            db.musicList.remove(it)
            Files.move(Paths.get(db.musicFolder.path, it.path), Paths.get(db.musicFolder.canonicalPath, newName))
            db.musicList.add(MusicFile(it.index, it.artist, it.name, File(db.musicFolder, newName).relativeTo(db.musicFolder).path))
        }
        db.commit()
        respond("New name: " + db.musicList.findIndex(index).toString())
    }

    fun renameArtist(index: Int, newName: String) {
        db.musicList.findIndex(index)?.let {
            db.musicList.remove(it)
            db.musicList.add(MusicFile(it.index, newName, it.name, it.path))
        }
        db.commit()
        respond("New name: " + db.musicList.findIndex(index).toString())
    }

    fun renameSong(index: Int, newName: String) {
        db.musicList.findIndex(index)?.let {
            db.musicList.remove(it)
            db.musicList.add(MusicFile(it.index, it.artist, newName, it.path))
        }
        db.commit()
        respond("New name: " + db.musicList.findIndex(index).toString())
    }

    fun newPlaylist(name: String) {
        val lists = db.playlists.toList()
        if (lists.any { it.name == name }) {
            respond("Playlist with that name already exists")
        } else {
            val new = Playlist(db.nextPLIndex.get(), name)
            db.playlists.add(new)
            respond("Playlist created: $new")

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

    fun printPlaylists() {
        respond(db.playlists.sortedBy(Playlist::index).joinToString("\n").run {
            db.commit()
            if (isEmpty())
                "No playlists available"
            else
                this
        })
    }

    fun addToPlayList(plIndex:Int, indexes: List<Int>) {
        val list = db.playlists.findIndex(plIndex) ?: return
        list.tracks += indexes
        db.playlists.remove(list)
        db.playlists.add(list)
        db.commit()
        respondList(indexes.map{ db.musicList.findIndex(it).toString() }, prefix = "Added to playlist '" + db.playlists.findIndex(plIndex).toString() + "':\n")
    }

    fun removeFromPlayList(plIndex: Int, indexes: List<Int>) {
        val list = db.playlists.findIndex(plIndex) ?: return
        list.tracks -= indexes
        db.playlists.remove(list)
        db.playlists.add(list)
        db.commit()
        respondList(indexes.map{ db.musicList.findIndex(it).toString() }, prefix = "Removed from playlist '" + db.playlists.findIndex(plIndex).toString() + "':\n")
    }

    fun queuePlaylist(plIndex: Int) {
        db.playlists.findIndex(plIndex)?.tracks?.forEach {
            player?.queue(File(db.musicFolder, db.musicList.findIndex(it)!!.path))
        }
    }
}