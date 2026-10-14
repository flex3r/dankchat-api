package db

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.io.File
import org.jetbrains.exposed.v1.jdbc.Database as ExposedDatabase

object Database {

    fun init(dataDir: File) {
        ExposedDatabase.connect(hikari(dataDir))
        transaction {
            SchemaUtils.create(Users)
        }
    }

    private fun hikari(dataDir: File): HikariDataSource {
        val config = HikariConfig().apply {
            driverClassName = "org.h2.Driver"
            jdbcUrl = "jdbc:h2:file:${dataDir.resolve("db").absolutePath}"
            validate()
        }
        return HikariDataSource(config)
    }
}
