package db

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction

object Database {

    fun init() {
        Database.connect(hikari())
        transaction {
            SchemaUtils.create(Users)
        }
    }

    private fun hikari(): HikariDataSource {
        val config = HikariConfig().apply {
            driverClassName = "org.h2.Driver"
            jdbcUrl = "jdbc:h2:file:~/opt/dankchat-api/db"
            validate()
        }
        return HikariDataSource(config)
    }
}