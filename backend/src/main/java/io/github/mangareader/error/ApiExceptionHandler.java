package io.github.mangareader.error;

import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {
    private static final Logger LOG = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ProblemDetail> handleApiException(ApiException exception, HttpServletRequest request) {
        if (exception.getCause() != null) {
            LOG.warn("{}: {}", exception.code(), exception.getCause().toString());
        }
        return ResponseEntity.status(exception.status()).contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problem(exception, request.getRequestURI()));
    }

    @ExceptionHandler({DataAccessResourceFailureException.class, TransientDataAccessResourceException.class,
            CannotCreateTransactionException.class})
    ResponseEntity<ProblemDetail> handleDatabaseUnavailable(Exception exception, HttpServletRequest request) {
        LOG.warn("Database access failed", exception);
        return handleApiException(ApiException.problem(HttpStatus.SERVICE_UNAVAILABLE,
                "DATABASE_UNAVAILABLE", "База данных временно недоступна. Попробуйте позже."), request);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> handleUnexpected(Exception exception, HttpServletRequest request) {
        LOG.error("Unexpected API error", exception);
        return handleApiException(ApiException.problem(HttpStatus.INTERNAL_SERVER_ERROR,
                "INTERNAL_ERROR", "Внутренняя ошибка сервера."), request);
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception exception, Object body, HttpHeaders headers,
                                                             HttpStatusCode status, WebRequest request) {
        String path = ((ServletWebRequest) request).getRequest().getRequestURI();
        ApiException mapped = switch (status.value()) {
            case 400 -> ApiException.problem(HttpStatus.BAD_REQUEST, "INVALID_REQUEST",
                    "Проверьте параметры запроса и формат JSON.");
            case 404 -> ApiException.problem(HttpStatus.NOT_FOUND, "ROUTE_NOT_FOUND", "Адрес API не найден.");
            case 405 -> ApiException.problem(HttpStatus.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED",
                    "Этот HTTP-метод здесь недоступен.");
            case 406 -> ApiException.problem(HttpStatus.NOT_ACCEPTABLE, "NOT_ACCEPTABLE",
                    "Запрошенный формат ответа недоступен.");
            case 415 -> ApiException.problem(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_MEDIA_TYPE",
                    "Отправьте тело запроса в формате application/json.");
            default -> null;
        };
        ProblemDetail response;
        if (mapped == null) {
            response = ProblemDetail.forStatusAndDetail(status, "Запрос не удалось обработать.");
            response.setType(URI.create("about:blank"));
            response.setInstance(URI.create(path));
            response.setProperty("code", "HTTP_ERROR");
            response.setProperty("errors", List.of());
        } else {
            response = problem(mapped, path);
        }
        HttpHeaders responseHeaders = new HttpHeaders();
        responseHeaders.putAll(headers);
        responseHeaders.setContentType(MediaType.APPLICATION_PROBLEM_JSON);
        return super.handleExceptionInternal(exception, response, responseHeaders, status, request);
    }

    private ProblemDetail problem(ApiException exception, String path) {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(exception.status(), exception.getMessage());
        detail.setType(URI.create("about:blank"));
        detail.setTitle(exception.code().equals("VALIDATION_ERROR")
                ? "Ошибка проверки запроса"
                : exception.status().getReasonPhrase());
        detail.setInstance(URI.create(path));
        detail.setProperty("code", exception.code());
        detail.setProperty("errors", exception.errors());
        exception.extensions().forEach(detail::setProperty);
        return detail;
    }
}
