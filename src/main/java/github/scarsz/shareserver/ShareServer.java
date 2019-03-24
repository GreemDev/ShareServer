package github.scarsz.shareserver;

import org.apache.commons.lang3.RandomStringUtils;
import spark.Spark;
import spark.utils.IOUtils;
import spark.utils.StringUtils;
import javax.servlet.MultipartConfigElement;
import javax.servlet.http.Part;
import java.io.File;
import java.io.InputStream;
import java.sql.*;

public class ShareServer {

    private final Connection conn;
    static void start(String[] args) throws SQLException {
        new ShareServer(
                args.length >= 1 ? args[0] : null,
                args.length >= 2 ? Integer.parseInt(args[1]) : 8082
        );
    }

    private ShareServer(String key, int port) throws SQLException {
        boolean isProduction = !System.getProperty("os.name").contains("Windows");
        if (key == null) throw new IllegalArgumentException("No key given");

        this.conn = DriverManager.getConnection("jdbc:h2:" + new File("share").getAbsolutePath());
        this.conn.prepareStatement("CREATE TABLE IF NOT EXISTS `files` (" +
                "`id` VARCHAR NOT NULL, " +
                "`filename` VARCHAR NOT NULL, " +
                "`hits` INT NOT NULL DEFAULT 0, " +
                "`type` VARCHAR NOT NULL, " +
                "`data` BLOB NOT NULL, " +
                "PRIMARY KEY (`id`), UNIQUE KEY id (`id`))").executeUpdate();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                this.conn.close();
            } catch (SQLException e) {
                e.printStackTrace();
            }
        }));

        Spark.port(port);

        // gzip where possible
        Spark.after((request, response) ->
                response.header("Content-Encoding", "gzip"));

        // logging
        Spark.afterAfter((request, response) -> {
            String method = request.requestMethod();
            String location = request.url().replace("http://localhost:8082", "https://img.greemdev.net")
                    + (StringUtils.isNotBlank(request.queryString()) ? "?" + request.queryString() : "");
            System.out.println(method + " " + location + " -> " + response.status());
        });

        // redirect /id -> /id/filename.ext
        Spark.get("/:id", (request, response) -> {
            PreparedStatement statement = this.conn.prepareStatement("SELECT `filename` FROM `files` WHERE `id` = ? LIMIT 1");
            statement.setString(1, request.params("id"));
            ResultSet res = statement.executeQuery();
            if (res.next()) {
                response.redirect("/" + request.params(":id") + "/" + res.getString("filename"), 301);
            } else {
                Spark.halt(404, "Not found");
            }
            return null;
        });
        // serve files from db
        Spark.get("/:id/*", (request, response) -> {
            PreparedStatement statement = this.conn.prepareStatement("SELECT `filename`, `type`, `data` FROM `files` WHERE `id` = ? LIMIT 1");
            statement.setString(1, request.params(":id"));
            ResultSet result = statement.executeQuery();
            if (result.next()) {
                if (request.splat().length == 0 || !result.getString("filename").equals(request.splat()[0])) {
                    // redirect to correct filename
                    response.redirect("/" + request.params(":id") + "/" + result.getString("filename"));
                    return null;
                }

                response.status(200);
                InputStream data = result.getBlob("data").getBinaryStream();
                response.type(result.getString("type"));
                IOUtils.copy(data, response.raw().getOutputStream());
                response.raw().getOutputStream().flush();
                response.raw().getOutputStream().close();
                return response.raw();
            } else {
                Spark.halt(404, "Not found");
                response.status(404);
                return "404";
            }
        });

        // hit counting
        Spark.after("/:id/*", (request, response) -> {
            if (response.status() == 200) {
                PreparedStatement statement = this.conn.prepareStatement("SELECT `hits` FROM `files` WHERE `id` = ?");
                statement.setString(1, request.params(":id"));
                ResultSet result = statement.executeQuery();
                if (result.next()) {
                    System.out.println(request.params(":id") + " is now at " + (result.getInt("hits") + 1) + " hits");
                    statement = this.conn.prepareStatement("UPDATE `files` SET `hits` = `hits` + 1 WHERE `id` = ?");
                    statement.setString(1, request.params(":id"));
                    statement.executeUpdate();
                }
            }
        });

        // file uploading
        Spark.put("/", (request, response) -> {
            request.attribute("org.eclipse.jetty.multipartConfig", new MultipartConfigElement("/temp"));
            String givenKey = request.raw().getPart("key") != null
                    ? IOUtils.toString(request.raw().getPart("key").getInputStream())
                    : null;
            if (StringUtils.isBlank(givenKey) || !givenKey.equals(key)) {
                Spark.halt(403, "Forbidden");
            }

            try {
                Part part = request.raw().getPart("file");
                if (part == null) {
                    Spark.halt(400, "File form name configured in ShareX should be \"file\"; nothing else.");
                    return "400";
                }
                InputStream input = part.getInputStream();
                String fileName = part.getSubmittedFileName();
                String type = part.getContentType();
                String id = RandomStringUtils.randomAlphabetic(10);

                PreparedStatement statement = this.conn.prepareStatement("INSERT INTO `files` (`id`, `filename`, `type`, `data`) VALUES (?, ?, ?, ?)");
                statement.setString(1, id);
                statement.setString(2, fileName);
                statement.setString(3, type);
                statement.setBlob(4, input);
                statement.executeUpdate();

                if (isProduction) {
                    return "https://img.greemdev.net/" + id + "/" + fileName;
                } else {
                    return request.url() + id + "/" + fileName;
                }
            } catch (Exception ignored) {
                return "500";
            }
        });
    }

}
