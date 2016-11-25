package coffee.cypher.discordplayer

import org.cfg4j.provider.ConfigurationProvider
import org.cfg4j.provider.GenericType
import sx.blah.discord.api.events.Event
import sx.blah.discord.api.events.EventDispatcher
import sx.blah.discord.api.events.IListener
import sx.blah.discord.handle.obj.IMessage

//TODO: get left time.
//TODO: Exceptions can be thrown to console

fun main(args: Array<String>) {
    val player = DiscordPlayer("player.properties")
    player.start()
}

val dispositionMatcher = "(?i)filename=\"([^\"]+)\"".toRegex()

inline fun <reified T : Event> EventDispatcher.on(noinline callback: T.() -> Unit) = registerListener(IListener(callback))

inline fun <reified T : Event> EventDispatcher.once(noinline callback: T.() -> Unit) = registerTemporaryListener(IListener(callback))

fun IMessage.respond(message: String, mention: Boolean = false): IMessage = channel.sendMessage("${if (mention) author.mention() else ""} $message")

fun IMessage.respondList(list: List<String>, prefix: String = "", suffix: String = "", separator: String = "\n", mention: Boolean = false): IMessage {
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
    return respond("$prefix$result$suffix$extra", mention)
}

inline fun <reified T> ConfigurationProvider.get(name: String): T = getProperty(name, object : GenericType<T>() {})