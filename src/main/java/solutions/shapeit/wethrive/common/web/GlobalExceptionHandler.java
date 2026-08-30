package solutions.shapeit.wethrive.common.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    public record FieldViolation(String field, String message) {}
    public record Problem(Instant timestamp, int status, String code, String message,
                          String path, String correlationId, List<FieldViolation> violations) {}

    @ExceptionHandler(ApiException.class)
    ResponseEntity<Problem> api(ApiException ex, HttpServletRequest request) {
        return response(ex.status(), ex.code(), ex.getMessage(), request, List.of());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<Problem> validation(MethodArgumentNotValidException ex, HttpServletRequest request) {
        List<FieldViolation> violations = ex.getBindingResult().getFieldErrors().stream()
                .map(this::violation).toList();
        return response(HttpStatus.BAD_REQUEST, "validation_failed", "The request contains invalid fields", request, violations);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    ResponseEntity<Problem> constraint(ConstraintViolationException ex, HttpServletRequest request) {
        return response(HttpStatus.BAD_REQUEST, "validation_failed", "The request is invalid", request, List.of());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<Problem> unreadable(HttpMessageNotReadableException ex, HttpServletRequest request) {
        if (causedByBodyLimit(ex)) {
            return response(HttpStatus.CONTENT_TOO_LARGE, "payload_too_large", "The request body is too large", request, List.of());
        }
        return response(HttpStatus.BAD_REQUEST, "malformed_request", "The request body is malformed", request, List.of());
    }

    @ExceptionHandler({MissingServletRequestParameterException.class, MethodArgumentTypeMismatchException.class})
    ResponseEntity<Problem> requestParameter(Exception ex, HttpServletRequest request) {
        return response(HttpStatus.BAD_REQUEST, "invalid_parameter", "A request parameter is missing or invalid", request, List.of());
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ResponseEntity<Problem> mediaType(HttpMediaTypeNotSupportedException ex, HttpServletRequest request) {
        return response(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "unsupported_media_type", "The request content type is not supported", request, List.of());
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<Problem> missingRoute(NoResourceFoundException ex, HttpServletRequest request) {
        return response(HttpStatus.NOT_FOUND, "not_found", "The requested resource was not found", request, List.of());
    }

    @ExceptionHandler({AccessDeniedException.class})
    ResponseEntity<Problem> denied(RuntimeException ex, HttpServletRequest request) {
        return response(HttpStatus.FORBIDDEN, "forbidden", "You do not have permission to perform this action", request, List.of());
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<Problem> integrity(DataIntegrityViolationException ex, HttpServletRequest request) {
        return response(HttpStatus.CONFLICT, "data_conflict", "The request conflicts with existing data", request, List.of());
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    ResponseEntity<Problem> optimisticConflict(OptimisticLockingFailureException ex, HttpServletRequest request) {
        return response(HttpStatus.CONFLICT, "optimistic_lock_conflict",
                "The record was changed by another request; reload it and try again", request, List.of());
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Problem> unexpected(Exception ex, HttpServletRequest request) {
        log.error("request_failed code=internal_error path={} exceptionType={}",
                safePath(request.getRequestURI()), ex.getClass().getSimpleName());
        return response(HttpStatus.INTERNAL_SERVER_ERROR, "internal_error", "An unexpected error occurred", request, List.of());
    }

    private boolean causedByBodyLimit(Throwable error) {
        Throwable current = error;
        for (int depth = 0; current != null && depth < 12; depth++, current = current.getCause()) {
            if (current instanceof ApiRequestSizeFilter.RequestBodyTooLargeException) return true;
        }
        return false;
    }

    private FieldViolation violation(FieldError error) {
        return new FieldViolation(error.getField(), error.getDefaultMessage());
    }

    private ResponseEntity<Problem> response(HttpStatus status, String code, String message,
                                             HttpServletRequest request, List<FieldViolation> violations) {
        return ResponseEntity.status(status).body(new Problem(Instant.now(), status.value(), code, message,
                safePath(request.getRequestURI()), MDC.get("correlationId"), violations));
    }

    private String safePath(String path) {
        if (path == null) return null;
        return path.replaceAll("(/api/v1/invitations/)[^/]+(/(?:accept|decline))", "$1[token]$2");
    }
}
