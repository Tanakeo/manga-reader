package io.github.mangareader.library;

import io.github.mangareader.error.ApiException;
import io.github.mangareader.error.ApiException.FieldError;
import java.text.Normalizer;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class LibrarySections {
    private static final Set<String> RESERVED = Set.of("все тайтлы");
    private static final String VIEW = """
            SELECT id, name, system_key, ARRAY(
                SELECT entry_id FROM library_section_entries WHERE section_id = library_sections.id ORDER BY entry_id
            ) AS entry_ids
            FROM library_sections
            """;

    private final JdbcClient jdbc;

    LibrarySections(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record Section(UUID id, String name, List<UUID> entryIds, String systemKey) {}

    List<Section> list() {
        return query(jdbc.sql(VIEW + "ORDER BY created_at, id")).list();
    }

    @Transactional
    Section create(String requestedName) {
        String name = validateName(requestedName);
        UUID id = UUID.randomUUID();
        try {
            jdbc.sql("INSERT INTO library_sections (id, name, normalized_name) VALUES (:id, :name, :key)")
                    .param("id", id).param("name", name).param("key", key(name)).update();
        } catch (DuplicateKeyException exception) {
            throw nameConflict();
        }
        return new Section(id, name, List.of(), null);
    }

    @Transactional
    Section rename(UUID id, String requestedName) {
        String name = validateName(requestedName);
        try {
            int changed = jdbc.sql("UPDATE library_sections SET name = :name, normalized_name = :key WHERE id = :id")
                    .param("id", id).param("name", name).param("key", key(name)).update();
            if (changed == 0) throw notFound();
        } catch (DuplicateKeyException exception) {
            throw nameConflict();
        }
        return get(id);
    }

    @Transactional
    void delete(UUID id) {
        lock(id);
        if (jdbc.sql("DELETE FROM library_sections WHERE id = :id").param("id", id).update() == 0) {
            throw notFound();
        }
    }

    @Transactional
    Section addEntries(UUID id, List<UUID> entryIds) {
        lock(id);
        var entries = jdbc.sql("SELECT id FROM library_entries WHERE id IN (:entryIds) ORDER BY id FOR KEY SHARE")
                .param("entryIds", entryIds).query(UUID.class).list();
        if (entries.size() != entryIds.size()) {
            throw ApiException.problem(HttpStatus.NOT_FOUND, "LIBRARY_ENTRY_NOT_FOUND",
                    "Один из выбранных тайтлов больше не находится в библиотеке.");
        }
        for (UUID entryId : entries) {
            jdbc.sql("""
                    INSERT INTO library_section_entries (section_id, entry_id)
                    VALUES (:section, :entry) ON CONFLICT DO NOTHING
                    """).param("section", id).param("entry", entryId).update();
        }
        return get(id);
    }

    @Transactional
    Section removeEntry(UUID id, UUID entryId) {
        lock(id);
        jdbc.sql("DELETE FROM library_section_entries WHERE section_id = :section AND entry_id = :entry")
                .param("section", id).param("entry", entryId).update();
        return get(id);
    }

    private void lock(UUID id) {
        jdbc.sql("SELECT id FROM library_sections WHERE id = :id FOR UPDATE").param("id", id)
                .query(UUID.class).optional().orElseThrow(LibrarySections::notFound);
        if (get(id).systemKey() != null) {
            throw ApiException.problem(HttpStatus.UNPROCESSABLE_ENTITY, "SYSTEM_SECTION_ACTION_UNSUPPORTED",
                    "Эта секция заполняется автоматически. Можно изменить её название.");
        }
    }

    Section get(UUID id) {
        return query(jdbc.sql(VIEW + "WHERE id = :id").param("id", id))
                .optional().orElseThrow(LibrarySections::notFound);
    }

    private static JdbcClient.MappedQuerySpec<Section> query(JdbcClient.StatementSpec statement) {
        return statement.query((row, index) -> new Section(row.getObject("id", UUID.class), row.getString("name"),
                Arrays.stream((Object[]) row.getArray("entry_ids").getArray())
                        .map(value -> UUID.fromString(value.toString())).toList(), row.getString("system_key")));
    }

    private static String validateName(String requestedName) {
        String name = Normalizer.normalize(requestedName.strip(), Normalizer.Form.NFC);
        if (name.codePointCount(0, name.length()) > 80) {
            throw invalidName("TOO_LONG", "Введите не более 80 символов.");
        }
        if (name.codePoints().allMatch(Character::isSpaceChar) || name.codePoints().anyMatch(Character::isISOControl)) {
            throw invalidName("INVALID_FORMAT", "Введите название секции без управляющих символов.");
        }
        if (RESERVED.contains(key(name))) {
            throw invalidName("RESERVED_NAME", "Это название используется в библиотеке.");
        }
        return name;
    }

    private static String key(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    private static ApiException invalidName(String code, String message) {
        return ApiException.validation(List.of(new FieldError("name", code, message)));
    }

    private static ApiException nameConflict() {
        return ApiException.problem(HttpStatus.CONFLICT, "LIBRARY_SECTION_NAME_EXISTS", "Секция с таким названием уже есть.");
    }

    private static ApiException notFound() {
        return ApiException.problem(HttpStatus.NOT_FOUND, "LIBRARY_SECTION_NOT_FOUND", "Секция библиотеки не найдена.");
    }
}
