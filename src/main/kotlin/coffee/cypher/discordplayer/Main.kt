package coffee.cypher.discordplayer

//TODO: get left time.
//TODO: Exceptions can be thrown to console

fun main(args: Array<String>) {
    val player = DiscordPlayer("player.properties")
    player.start()
}

