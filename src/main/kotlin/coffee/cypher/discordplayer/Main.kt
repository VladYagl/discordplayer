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
    val client = DiscordClient("player.properties")
    client.start()
}

val dispositionMatcher = "(?i)filename=\"([^\"]+)\"".toRegex()

inline fun <reified T : Event> EventDispatcher.on(noinline callback: T.() -> Unit) = registerListener(IListener(callback))

inline fun <reified T : Event> EventDispatcher.once(noinline callback: T.() -> Unit) = registerTemporaryListener(IListener(callback))

inline fun <reified T> ConfigurationProvider.get(name: String): T = getProperty(name, object : GenericType<T>() {})

fun IMessage.respond(message: String, mention: Boolean = false): IMessage = channel.sendMessage("${if (mention) author.mention() else ""} $message")
