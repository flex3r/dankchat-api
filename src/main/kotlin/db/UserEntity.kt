package db

import org.jetbrains.exposed.dao.IntEntity
import org.jetbrains.exposed.dao.IntEntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.IntIdTable

object Users : IntIdTable() {
    val seChannelId = varchar("seId", 50).uniqueIndex()
    val twitchId = varchar("twitchId", 50).uniqueIndex()
    val name = varchar("name", 255)
}


class User(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<User>(Users)

    var seChannelId by Users.seChannelId
    var twitchId by Users.twitchId
    var name by Users.name
}