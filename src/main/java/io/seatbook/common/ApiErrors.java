package io.seatbook.common;

import java.util.Map;
import java.util.TreeMap;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;

@RestControllerAdvice
class ApiErrors {
    @ExceptionHandler(ApiException.class)
    ProblemDetail businessError(ApiException ex) {
        return problem(ex.status(), ex.code(), ex.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail invalidBody(MethodArgumentNotValidException ex) {
        Map<String, String> fields = new TreeMap<>();
        ex.getBindingResult().getFieldErrors()
            .forEach(error -> fields.putIfAbsent(error.getField(), error.getDefaultMessage()));
        var detail = problem(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "请检查请求字段");
        detail.setProperty("fields", fields);
        return detail;
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    ProblemDetail invalidParameter(HandlerMethodValidationException ex) {
        return problem(HttpStatus.BAD_REQUEST, "INVALID_PARAMETER", "请检查路径或查询参数");
    }

    private ProblemDetail problem(HttpStatus status, String code, String message) {
        var detail = ProblemDetail.forStatusAndDetail(status, message);
        detail.setProperty("code", code);
        return detail;
    }
}
