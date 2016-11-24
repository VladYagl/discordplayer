package coffee.cypher.discordplayer

import org.mapdb.DataInput2
import org.mapdb.DataOutput2

data class Playlist(val index: Int, val name: String) {
    val tracks: MutableSet<Int> = mutableSetOf()

    override fun toString(): String {
        return "$index: $name (${tracks.size} track${if (tracks.size != 1) "s" else ""})"
    }

    object Serializer : org.mapdb.Serializer<Playlist> {
        override fun deserialize(input: DataInput2, available: Int): Playlist {
            val id = input.readInt()
            val name = input.readUTF()

            return Playlist(id, name).apply {
                (1..input.readInt()).forEach {
                    tracks += input.readInt()
                }
            }
        }

        override fun serialize(out: DataOutput2, value: Playlist) {
            with(value) {
                out.writeInt(index)
                out.writeUTF(name)
                out.writeInt(tracks.size)
                tracks.forEach { out.writeInt(it) }
            }
        }
    }
}