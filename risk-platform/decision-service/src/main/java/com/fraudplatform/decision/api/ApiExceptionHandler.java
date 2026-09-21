package com.fraudplatform.decision.api;

import com.fraudplatform.commons.correlation.Correlation;
import com.fraudplatform.commons.error.ErrorCode;
import com.fraudplatform.commons.error.PlatformException;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.net.URI;
import java.util.List;
import java.util.Map;

/**
 * Maps every failure to an RFC 9457 problem document with a stable {@code code} and the correlation ID,
 * so a customer engineer can quote one identifier when raising a ticket.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    public static ProblemDetail problem(ErrorCode code, String detail, Map<String, Object> extra) {
        ProblemDetail p = ProblemDetail.forStatusAndDetail(HttpStatus.valueOf(code.httpStatus()),
                detail == null ? code.defaultMessage() : detail);
        p.setType(URI.create("https://docs.fraud-platform.example/errors/" + code.name().toLowerCase().replace('_', '-')));
        p.setTitle(code.defaultMessage());
        p.setProperty("code", code.name());
        p.setProperty("correlationId", MDC.get(Correlation.MDC_CORRELATION_ID));
        if (extra != null && !extra.isEmpty()) p.setProperty("details", extra);
        return p;
    }

    private static ResponseEntity<ProblemDetail> respond(ErrorCode code, String detail, Map<String, Object> extra) {
        return ResponseEntity.status(code.httpStatus()).body(problem(code, detail, extra));
    }

    @ExceptionHandler(PlatformException.class)
    ResponseEntity<ProblemDetail> platform(PlatformException e) {
        if (e.code().httpStatus() >= 500) log.error("platform error code={}", e.code(), e);
        else log.info("request rejected code={} message={}", e.code(), e.getMessage());
        return respond(e.code(), e.getMessage(), e.details());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ProblemDetail> invalid(MethodArgumentNotValidException e) {
        List<Map<String, String>> errors = e.getBindingResult().getAllErrors().stream().map(err -> Map.of(
                "field", err instanceof org.springframework.validation.FieldError fe ? fe.getField() : err.getObjectName(),
                "message", String.valueOf(err.getDefaultMessage()))).toList();
        return respond(ErrorCode.VALIDATION_FAILED, null, Map.of("errors", errors));
    }

    /** Spring reports {@code @Valid} body errors this way when the controller also validates headers/params. */
    @ExceptionHandler(HandlerMethodValidationException.class)
    ResponseEntity<ProblemDetail> methodValidation(HandlerMethodValidationException e) {
        List<Map<String, String>> errors = new java.util.ArrayList<>();
        for (var result : e.getParameterValidationResults()) {
            String param = result.getMethodParameter().getParameterName();
            if (result instanceof org.springframework.validation.method.ParameterErrors pe) {
                pe.getFieldErrors().forEach(fe -> errors.add(Map.of("field", fe.getField(),
                        "message", String.valueOf(fe.getDefaultMessage()))));
                pe.getGlobalErrors().forEach(ge -> errors.add(Map.of("field", param,
                        "message", String.valueOf(ge.getDefaultMessage()))));
            } else {
                result.getResolvableErrors().forEach(err -> errors.add(Map.of("field", String.valueOf(param),
                        "message", String.valueOf(err.getDefaultMessage()))));
            }
        }
        return respond(ErrorCode.VALIDATION_FAILED, null, Map.of("errors", errors));
    }

    @ExceptionHandler({ConstraintViolationException.class, MethodArgumentTypeMismatchException.class})
    ResponseEntity<ProblemDetail> invalidParams(Exception e) {
        return respond(ErrorCode.VALIDATION_FAILED, "invalid request parameter or header", Map.of("reason", e.getMessage()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ProblemDetail> unreadable(HttpMessageNotReadableException e) {
        // Do not echo the payload back: it may contain customer data.
        return respond(ErrorCode.VALIDATION_FAILED, "malformed JSON or invalid field value", Map.of());
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ResponseEntity<ProblemDetail> mediaType(HttpMediaTypeNotSupportedException e) {
        return ResponseEntity.status(415).body(problem(ErrorCode.VALIDATION_FAILED, "Content-Type must be application/json", Map.of()));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ProblemDetail> method(HttpRequestMethodNotSupportedException e) {
        return ResponseEntity.status(405).body(problem(ErrorCode.VALIDATION_FAILED, e.getMessage(), Map.of()));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ProblemDetail> notFound(NoResourceFoundException e) {
        return respond(ErrorCode.NOT_FOUND, null, Map.of());
    }

    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<ProblemDetail> database(DataAccessException e) {
        log.error("database error", e);
        return respond(ErrorCode.DEPENDENCY_UNAVAILABLE, "database unavailable, retry with the same Idempotency-Key", Map.of());
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> unexpected(Exception e) {
        log.error("unexpected error", e);
        return respond(ErrorCode.INTERNAL_ERROR, null, Map.of());
    }
}
