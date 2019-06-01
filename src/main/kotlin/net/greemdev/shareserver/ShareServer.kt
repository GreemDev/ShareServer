package net.greemdev.shareserver

import org.apache.commons.lang3.RandomStringUtils
import spark.Filter
import spark.Spark
import spark.utils.IOUtils
import spark.utils.StringUtils
import javax.servlet.MultipartConfigElement
import java.io.File
import java.io.InputStream
import java.lang.Exception
import java.lang.IllegalArgumentException
import java.sql.*
import java.sql.ResultSet
import java.sql.PreparedStatement

public class ShareServer private constructor(key: String?, port: Int) {
    private var conn: Connection
    companion object {
        internal fun start(args: Array<String>) {
            ShareServer(if (args.isNotEmpty()) args[0] else null,
                        if (args.size >= 2) Integer.parseInt(args[1]) else 8082)
        }
    }

    init {
        val isProd: Boolean = !System.getProperty("os.name").contains("Windows")
        if (key == null) throw IllegalArgumentException("No key given")

        this.conn = DriverManager.getConnection("jdbc:h2:" + File("share").absolutePath)
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

        //listen on given, or default, port
        Spark.port(port)

        //use gzip
        Spark.after(Filter{ _, response ->
            response.header("Content-Encoding", "gzip")
        })

        //logging
        Spark.afterAfter{request, response ->
            val method: String = request.requestMethod()
            val location: String = if (isProd) {
                request.url().replace(
                    "http://localhost:$port",
                    "https://img.greemdev.net"
                ) + if (StringUtils.isNotBlank(request.queryString())) "?" + request.queryString() else ""
            } else {
                request.url() + if (StringUtils.isNotBlank(request.queryString())) "?" + request.queryString() else ""
            }
            println("$method $location -> ${response.status()}")
        }

        //redirect /id to /id/filename.ext
        Spark.get("/:id") { request, response ->
            val statement: PreparedStatement = this.conn.prepareStatement("SELECT `filename` FROM `files` WHERE `id` == ? LIMIT 1")
            statement.setString(1, request.params("id"))
            val res: ResultSet = statement.executeQuery()
            if (res.next())
                response.redirect("/${request.params("id")}/${res.getString("filename")}", 301)
            else
                Spark.halt(404, "Not found")
        }

        //serve files from database
        Spark.get("/:id/*") {request, response ->
            val statement: PreparedStatement = this.conn.prepareStatement("SELECT `filename`, `type`, `data` FROM `files` WHERE `id` = ? LIMIT 1")
            statement.setString(1, request.params(":id"))
            val result: ResultSet = statement.executeQuery()
            if (result.next()) {
                if (request.splat().isEmpty() || result.getString("filename").equals(request.splat()[0])) {
                    response.redirect("/${request.params(":id")}")
                }

                response.status(200)
                val data: InputStream = result.getBlob("data").binaryStream
                response.type(result.getString("type"))
                IOUtils.copy(data, response.raw().outputStream)
                response.raw().outputStream.flush()
                response.raw().outputStream.close()
            } else {
                Spark.halt(404, "Not found")
                response.status(404)
            }
        }

        //hit counts
        Spark.after("/:id/*") {request, response ->
            if (response.status() == 200) {
                var statement = this.conn.prepareStatement("SELECT `hits` FROM `files` WHERE `id` = ?")
                statement.setString(1, request.params(":id"))
                val result = statement.executeQuery()
                if (result.next()) {
                    println("${request.params(":id")} is now at ${result.getInt("hits") + 1} hits.")
                    statement = this.conn.prepareStatement("UPDATE `files` SET `hits` = `hits` + 1 WHERE `id` = ?")
                    statement.setString(1, request.params(":id"))
                    statement.executeUpdate()
                }
            }
        }

        //file uploading
        Spark.put("/") { request, response ->
            request.attribute("org.eclise.jetty.multipartConfig", MultipartConfigElement("/temp"))
            val givenKey: String? = if (request.raw().getPart("key") != null)
                IOUtils.toString(request.raw().getPart("key").inputStream)
            else
                null
            if (StringUtils.isBlank(givenKey) || !givenKey.equals(key)) {
                Spark.halt(403, "Forbidden")
            }

            try {
                val part = request.raw().getPart("file")
                if (part == null) {
                    Spark.halt(400, "File form name configured in ShareX should be \"file\"; nothing else.")
                }
                val input = part!!.inputStream
                val fileName = part.submittedFileName
                val type = part.contentType
                val id = RandomStringUtils.randomAlphabetic(10)

                val statement =
                    this.conn.prepareStatement("INSERT INTO `files` (`id`, `filename`, `type`, `data`) VALUES (?, ?, ?, ?)")
                statement.setString(1, id)
                statement.setString(2, fileName)
                statement.setString(3, type)
                statement.setBlob(4, input)
                statement.executeUpdate()

                response.redirect(if (isProd)
                    "https://img.greemdev.net/$id/$fileName"
                else
                    "${request.url()}$id/$fileName"
                )
            } catch (ignored: Exception) {
                response.status(500)
            }
        }

        //redirect GET / to the github
        Spark.get("/") {_, response ->
            response.redirect("https://github.com/GreemDev/ShareServer")
        }
    }
}
