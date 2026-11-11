package com.server.altercharger_server.common.error;

import io.swagger.v3.oas.annotations.media.Schema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class ApiErrorAdvice {
    private static final Logger log = LoggerFactory.getLogger(ApiErrorAdvice.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiError> apiError(ApiException exception) {
        return ResponseEntity.status(exception.status())
                .body(new ApiError(exception.error(), exception.getMessage()));
    }

    @ExceptionHandler({MissingServletRequestParameterException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ApiError> invalidRequest(Exception exception) {
        return ResponseEntity.badRequest().body(new ApiError("INVALID_REQUEST", "요청 파라미터의 형식과 필수 값을 확인해 주세요."));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiError> notFound(NoResourceFoundException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ApiError("NOT_FOUND", "요청한 경로를 찾을 수 없습니다."));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiError> methodNotAllowed(HttpRequestMethodNotSupportedException exception) {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .body(new ApiError("METHOD_NOT_ALLOWED", "지원하지 않는 요청 방식입니다."));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> unexpectedError(Exception exception) {
        // Exception messages can contain upstream URLs or credentials; log only the exception class.
        log.error("API request failed: {}", exception.getClass().getSimpleName());
        return ResponseEntity.internalServerError()
                .body(new ApiError("INTERNAL_SERVER_ERROR", "서버에서 요청을 처리하지 못했습니다."));
    }

    @Schema(name = "ApiError", description = "공통 오류 응답")
    public record ApiError(String error, String message) {}
}
