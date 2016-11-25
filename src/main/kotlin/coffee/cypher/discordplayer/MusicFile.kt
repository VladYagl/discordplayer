package coffee.cypher.discordplayer

import org.mapdb.DataInput2
import org.mapdb.DataOutput2

data class MusicFile(val index: Int, val artist: String?, val name: String?, val path: String) {
    override fun toString(): String {
        val fileName = path
                .replace("[^\\p{Print}]+".toRegex(), "#")
                .replace(".mp3$".toRegex(), "")
                .replace("\\[SaveFrom\\.online\\]".toRegex(), "")
        return "$index: ${if (artist == null && name == null) fileName else "${artist ?: ""} ${name ?: ""} ($fileName)"}"
    }

    object Serializer : org.mapdb.Serializer<MusicFile> {
        override fun serialize(out: DataOutput2, value: MusicFile) {
            val artist = value.artist
            val name = value.name
            val path = value.path
            val index = value.index

            out.writeInt(index)

            if (artist != null) {
                out.writeBoolean(true)
                out.writeUTF(artist)
            } else {
                out.writeBoolean(false)
            }

            if (name != null) {
                out.writeBoolean(true)
                out.writeUTF(name)
            } else {
                out.writeBoolean(false)
            }

            out.writeUTF(path)
        }

        override fun deserialize(input: DataInput2, available: Int): MusicFile {
            val index = input.readInt()

            val artist = if (input.readBoolean()) {
                input.readUTF()
            } else {
                null
            }

            val name = if (input.readBoolean()) {
                input.readUTF()
            } else {
                null
            }

            val path = input.readUTF()

            return MusicFile(index, artist, name, path)
        }

    }
}