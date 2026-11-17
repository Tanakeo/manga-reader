package io.github.mangareader.error;

import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;

public final class ApiException extends RuntimeException {
    public record FieldError(String field, String code, String message) {}

    private final HttpStatus status;
    private final String code;
    private final List<FieldError> errors;
    private final Map<String, ?> extensions;

    private ApiException(HttpStatus status, String code, String detail, List<FieldError> errors,
                         Map<String, ?> extensions, Throwable cause) {
        super(detail, cause);
        this.status = status;
        this.code = code;
        this.errors = List.copyOf(errors);
        this.extensions = Map.copyOf(extensions);
    }

    public static ApiException problem(HttpStatus status, String code, String detail) {
        return new ApiException(status, code, detail, List.of(), Map.of(), null);
    }

    public static ApiException invalidTags(List<String> ids) {
        return new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "UNKNOWN_TAG", "Некоторые теги не найдены.",
                List.of(new FieldError("tagId", "UNKNOWN_TAG", "Удалите неизвестные теги.")),
                Map.of("invalidTagIds", ids), null);
    }

    public static ApiException catalogSourceRequired() {
        return new ApiException(HttpStatus.BAD_REQUEST, "SOURCE_REQUIRED_FOR_CATALOG_OPTIONS",
                "Для фильтрации и сортировки выберите источник.",
                List.of(new FieldError("sourceId", "REQUIRED", "Выберите источник.")), Map.of(), null);
    }

    public static ApiException validation(List<FieldError> errors) {
        return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Исправьте указанные поля.",
                errors, Map.of(), null);
    }

    public static ApiException unavailableLanguage(List<String> languages) {
        String detail = "Для этой главы нет выбранного перевода.";
        return new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "LANGUAGE_UNAVAILABLE", detail,
                List.of(new FieldError("language", "NOT_AVAILABLE", detail)),
                Map.of("availableLanguages", languages), null);
    }

    public static ApiException invalidProgress(String field, String detail) {
        return new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "INVALID_PROGRESS", detail,
                List.of(new FieldError("progress." + field, "INVALID_VALUE", detail)), Map.of(), null);
    }

    public static ApiException sourceBadResponse(Throwable cause) {
        return new ApiException(HttpStatus.BAD_GATEWAY, "SOURCE_BAD_RESPONSE",
                "Источник вернул неожиданный ответ.", List.of(), Map.of(), cause);
    }

    public static ApiException sourceUnavailable(Throwable cause) {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "SOURCE_UNAVAILABLE",
                "Источник временно недоступен. Попробуйте позже.", List.of(), Map.of(), cause);
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    public List<FieldError> errors() {
        return errors;
    }

    public Map<String, ?> extensions() {
        return extensions;
    }
}
