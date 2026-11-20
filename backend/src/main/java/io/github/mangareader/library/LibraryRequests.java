package io.github.mangareader.library;

import io.github.mangareader.error.ApiException;
import io.github.mangareader.error.ApiException.FieldError;
import io.github.mangareader.error.ApiInputs;
import io.github.mangareader.library.LibraryModels.CreateEntry;
import io.github.mangareader.library.LibraryModels.Progress;
import io.github.mangareader.library.LibraryModels.UpdateEntry;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;

final class LibraryRequests {
    private LibraryRequests() {}

    static CreateEntry create(JsonNode body) {
        requireObject(body);
        var errors = new ArrayList<FieldError>();
        checkFields(body, Set.of("sourceId", "externalTitleId", "status", "note"), "", errors);
        String sourceId = identifier(body.get("sourceId"), "sourceId", ApiInputs.SOURCE_ID, 64, errors);
        String titleId = identifier(body.get("externalTitleId"), "externalTitleId", ApiInputs.EXTERNAL_ID, 200, errors);
        ReadingStatus status = status(body.get("status"), errors);
        String note = note(body.get("note"), errors);
        throwIfInvalid(errors);
        return new CreateEntry(sourceId, titleId, status, note);
    }

    static UpdateEntry update(JsonNode body) {
        requireObject(body);
        if (body.isEmpty()) {
            throw ApiException.validation(List.of(new FieldError("body", "EMPTY_PATCH", "Укажите поля для изменения.")));
        }
        var errors = new ArrayList<FieldError>();
        checkFields(body, Set.of("status", "note", "progress"), "", errors);
        ReadingStatus status = body.has("status") ? status(body.get("status"), errors) : null;
        String note = note(body.get("note"), errors);
        Progress progress = progress(body.get("progress"), errors);
        throwIfInvalid(errors);
        return new UpdateEntry(status, body.has("note"), note, body.has("progress"), progress);
    }

    static UUID entryId(String value) {
        return uuid(value, "entryId");
    }

    static UUID sectionId(String value) {
        return uuid(value, "sectionId");
    }

    private static UUID uuid(String value, String field) {
        try {
            UUID id = UUID.fromString(value);
            if (id.toString().equalsIgnoreCase(value)) {
                return id;
            }
        } catch (IllegalArgumentException ignored) {
            // All invalid representations receive the same field error.
        }
        throw ApiException.validation(List.of(new FieldError(field, "INVALID_FORMAT", "Укажите корректный UUID.")));
    }

    static String sectionName(JsonNode body) {
        requireObject(body);
        var errors = new ArrayList<FieldError>();
        checkFields(body, Set.of("name"), "", errors);
        String name = requiredText(body.get("name"), "name", errors);
        throwIfInvalid(errors);
        return name;
    }

    static List<UUID> sectionEntries(JsonNode body) {
        requireObject(body);
        var errors = new ArrayList<FieldError>();
        checkFields(body, Set.of("entryIds"), "", errors);
        var entries = body.path("entryIds");
        if (!entries.isArray() || entries.isEmpty()) {
            errors.add(new FieldError("entryIds", "INVALID_VALUE", "Выберите хотя бы один тайтл."));
        }
        throwIfInvalid(errors);
        var ids = new LinkedHashSet<UUID>();
        for (var entry : entries) {
            ids.add(uuid(entry.isString() ? entry.stringValue() : "", "entryIds"));
        }
        return List.copyOf(ids);
    }

    private static void requireObject(JsonNode body) {
        if (body == null || !body.isObject()) {
            throw ApiException.validation(List.of(new FieldError("body", "INVALID_TYPE", "Отправьте JSON-объект.")));
        }
    }

    private static void checkFields(JsonNode object, Set<String> allowed, String prefix, List<FieldError> errors) {
        object.propertyNames().stream().filter(name -> !allowed.contains(name))
                .forEach(name -> errors.add(new FieldError(prefix + name, "UNKNOWN_FIELD", "Неизвестное поле.")));
    }

    private static String requiredText(JsonNode node, String field, List<FieldError> errors) {
        if (node == null || node.isNull() || (node.isString() && node.stringValue().isBlank())) {
            errors.add(new FieldError(field, "REQUIRED", "Заполните поле."));
            return null;
        }
        if (!node.isString()) {
            errors.add(new FieldError(field, "INVALID_TYPE", "Ожидается текстовое значение."));
            return null;
        }
        return node.stringValue();
    }

    private static String identifier(JsonNode node, String field, Pattern pattern, int limit, List<FieldError> errors) {
        String value = requiredText(node, field, errors);
        if (value != null && (value.length() > limit || !pattern.matcher(value).matches())) {
            errors.add(new FieldError(field, "INVALID_FORMAT", "Неверный формат поля."));
        }
        return value;
    }

    private static ReadingStatus status(JsonNode node, List<FieldError> errors) {
        String value = requiredText(node, "status", errors);
        if (value != null) {
            try {
                return ReadingStatus.valueOf(value);
            } catch (IllegalArgumentException exception) {
                errors.add(new FieldError("status", "INVALID_VALUE", "Выберите допустимый статус чтения."));
            }
        }
        return null;
    }

    private static String note(JsonNode node, List<FieldError> errors) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isString()) {
            errors.add(new FieldError("note", "INVALID_TYPE", "Заметка должна быть текстом."));
            return null;
        }
        String note = node.stringValue();
        if (note.codePointCount(0, note.length()) > 2000) {
            errors.add(new FieldError("note", "TOO_LONG", "Заметка должна содержать не более 2000 символов."));
        }
        if (note.indexOf('\0') >= 0) {
            errors.add(new FieldError("note", "INVALID_FORMAT", "В заметке есть недопустимый символ."));
        }
        return note;
    }

    private static Progress progress(JsonNode node, List<FieldError> errors) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isObject()) {
            errors.add(new FieldError("progress", "INVALID_TYPE", "Прогресс должен быть JSON-объектом или null."));
            return null;
        }
        checkFields(node, Set.of("chapterId", "language", "pageIndex"), "progress.", errors);
        String chapterId = identifier(node.get("chapterId"), "progress.chapterId", ApiInputs.EXTERNAL_ID, 200, errors);
        String language = identifier(node.get("language"), "progress.language", ApiInputs.LANGUAGE, 32, errors);
        JsonNode page = node.get("pageIndex");
        if (page == null || page.isNull()) {
            errors.add(new FieldError("progress.pageIndex", "REQUIRED", "Укажите индекс страницы."));
            return null;
        }
        if (!page.isIntegralNumber() || !page.canConvertToInt()) {
            errors.add(new FieldError("progress.pageIndex", "INVALID_TYPE", "Ожидается целое число."));
            return null;
        }
        return new Progress(chapterId, language, page.intValue());
    }

    private static void throwIfInvalid(List<FieldError> errors) {
        if (!errors.isEmpty()) {
            throw ApiException.validation(errors);
        }
    }
}
