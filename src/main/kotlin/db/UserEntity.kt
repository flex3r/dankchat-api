package db

import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.jetbrains.exposed.v1.dao.IntEntity
import org.jetbrains.exposed.v1.dao.IntEntityClass

object Users : IntIdTable() {
    val seChannelId = varchar("seId", 50).uniqueIndex()
    val twitchId = varchar("twitchId", 50).uniqueIndex()
    val name = varchar("NAME", 255)
}


class User(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<User>(Users)

    var seChannelId by Users.seChannelId
    var twitchId by Users.twitchId
    var name by Users.name
}
