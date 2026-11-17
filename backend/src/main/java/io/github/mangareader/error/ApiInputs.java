package io.github.mangareader.error;

import io.github.mangareader.error.ApiException.FieldError;
import java.util.List;
import java.util.regex.Pattern;

public final class ApiInputs {
    public static final Pattern SOURCE_ID = Pattern.compile("[a-z0-9][a-z0-9-]{0,63}");
    public static final Pattern EXTERNAL_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._~-]{0,199}");
    public static final Pattern LANGUAGE = Pattern.compile("[a-zA-Z0-9]+(?:-[a-zA-Z0-9]+)*");

    private ApiInputs() {}

    public static String requireSourceId(String value) {
        requireMatch("sourceId", value, SOURCE_ID, 64);
        return value;
    }

    public static void requireMatch(String field, String value, Pattern pattern, int limit) {
        if (value == null || value.isBlank()) {
            throw ApiException.validation(List.of(new FieldError(field, "REQUIRED", "Заполните поле.")));
        }
        if (value.length() > limit || !pattern.matcher(value).matches()) {
            throw ApiException.validation(List.of(new FieldError(field, "INVALID_FORMAT", "Неверный формат поля.")));
        }
    }
}
