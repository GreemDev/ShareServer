package net.greemdev.shareserver

import org.apache.commons.lang3.RandomStringUtils
import spark.Request
import spark.Response
import spark.Spark
import spark.utils.IOUtils
import spark.utils.StringUtils
import javax.servlet.MultipartConfigElement
import javax.servlet.http.Part
import java.io.File
import java.io.InputStream
import java.lang.IllegalArgumentException
import java.sql.*

public class ShareServer private constructor(key: String?, port: Int) {
    private lateinit var conn: Connection
    companion object {
        public fun start(args: Array<String>) {
            ShareServer(if (args.size >= 1) args[0] else null,
                        if (args.size >= 2) Integer.parseInt(args[1]) else 8082)
        }
    }

    init {
        val isProd: Boolean = !System.getProperty("os.name").contains("Windows")
        if (key == null) throw IllegalArgumentException("No key given")

        this.conn = DriverManager.getConnection("jdbc:h2: " + File("share").absolutePath)
        this.conn.prepareStatement("CREATE TABLE IF NOT EXISTS `files` (" +
                "`id` VARCHAR NOT NULL, " +
                "`filename` VARCHAR NOT NULL, " +
                "`hits` INT NOT NULL DEFAULT 0, " +
                "`type` VARCHAR NOT NULL, " +
                "`data` BLOB NOT NULL, " +
                "PRIMARY KEY (`id`), UNIQUE KEY id (`id`))").executeUpdate()
        Runtime.getRuntime().addShutdownHook(Thread {
            try {
                this.conn.close()
            } catch (e: SQLException) {
                e.printStackTrace()
            }
        })

        Spark.port(port)

        Spark.after({ ->

        })
    }
}
