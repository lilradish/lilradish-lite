package com.lilradish.lite.app.files;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
final class DatabaseFileContentStore implements FileContentStore {

    private final JdbcTemplate database;

    DatabaseFileContentStore(DataSource source) {
        this.database = new JdbcTemplate(source);
    }

    @Override
    public void put(UUID id, InputStream content) {
        database.update(connection -> {
            PreparedStatement statement =
                    connection.prepareStatement("insert into file_contents (file_id, content) values (?, ?)");
            statement.setObject(1, id);
            statement.setBinaryStream(2, content);
            return statement;
        });
    }

    @Override
    public InputStream open(UUID id) {
        byte[] bytes = database.queryForObject(
                "select content from file_contents where file_id = ?",
                (ResultSet result, int row) -> result.getBytes(1),
                id);
        if (bytes == null) {
            throw new IllegalStateException("File content missing for held file");
        }
        return new ByteArrayInputStream(bytes);
    }
}
