package coffee.cypher.discordplayer

import org.mapdb.Atomic
import org.mapdb.DB
import org.mapdb.DBMaker
import java.io.File

class MusicDB() {

    private lateinit var db: DB

    lateinit var musicList: MutableSet<MusicFile>
    lateinit var playlists: MutableSet<Playlist>
    lateinit var musicFolder: File
    lateinit var nextIndex: Atomic.Integer
    lateinit var nextPLIndex: Atomic.Integer

    constructor(dbFile: String) : this() {
        db = DBMaker.fileDB(dbFile).transactionEnable().closeOnJvmShutdown().make()
        musicList = db.hashSet("music", MusicFile.Serializer).counterEnable().createOrOpen()
        playlists = db.hashSet("playlists", Playlist.Serializer).counterEnable().createOrOpen()
        musicFolder = File(db.atomicString("music.folder", "music").createOrOpen().get())
        nextIndex = db.atomicInteger("index", 1).createOrOpen()
        nextPLIndex = db.atomicInteger("plIndex", 1).createOrOpen()
        populateDB()
    }

    fun close() {
        db.close()
    }

    fun commit() {
        db.commit()
    }

    fun populateDB() {
        val old = musicList.toSet()

        musicFolder.listFiles()
                .asSequence()
                .filter { old.none { f -> f.path == it.relativeTo(musicFolder).path } }
                .forEach { addFile(it) }

        db.commit()
    }

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
}