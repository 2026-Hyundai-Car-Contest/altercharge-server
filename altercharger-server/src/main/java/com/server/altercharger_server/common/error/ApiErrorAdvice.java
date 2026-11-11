package com.server.altercharger_server.common.error;

import io.swagger.v3.oas.annotations.media.Schema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class ApiErrorAdvice {
    private static final Logger log = LoggerFactory.getLogger(ApiErrorAdvice.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiError> apiError(ApiException exception) {
        return ResponseEntity.status(exception.status())
                .body(new ApiError(exception.error(), exception.getMessage()));
    }

    @ExceptionHandler({MissingServletRequestParameterException.class, MethodArgumentTypeMismatchException.class,
            HandlerMethodValidationException.class, jakarta.validation.ConstraintViolationException.class})
    public ResponseEntity<ApiError> invalidRequest(Exception exception) {
        return ResponseEntity.badRequest().body(new ApiError("INVALID_REQUEST", "요청 파라미터의 형식과 필수 값을 확인해 주세요."));
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class,
            HttpMediaTypeNotSupportedException.class})
    public ResponseEntity<ApiError> invalidBody(Exception exception) {
        return ResponseEntity.badRequest().body(new ApiError("INVALID_REQUEST_BODY", "요청 본문의 필수 값, 자료형, 범위를 확인해 주세요."));
    }

    @ExceptionHandler({DataAccessResourceFailureException.class, CannotCreateTransactionException.class})
    public ResponseEntity<ApiError> unavailable(Exception exception) {
        log.warn("Database unavailable: {}", exception.getClass().getSimpleName());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(new ApiError("SERVICE_UNAVAILABLE", "데이터 저장소에 연결할 수 없습니다. 잠시 후 다시 시도해 주세요."));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiError> integrityError(DataIntegrityViolationException exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof org.hibernate.exception.ConstraintViolationException violation) {
                if ("pk_charging_events".equals(violation.getConstraintName())) {
                    return ResponseEntity.status(HttpStatus.CONFLICT)
                            .body(new ApiError("EVENT_ID_CONFLICT", "같은 이벤트 ID에 다른 내용이 저장되어 있습니다."));
                }
                if ("uk_vehicle_snapshot_instant".equals(violation.getConstraintName())) {
                    return ResponseEntity.status(HttpStatus.CONFLICT)
                            .body(new ApiError("SNAPSHOT_CONFLICT", "같은 차량과 시각에 다른 기록이 저장되어 있습니다."));
                }
            }
        }
        return unexpectedError(exception);
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
    public record ApiError(
            @Schema(description = "기계 판독용 오류 코드. 각 API의 HTTP 응답 예시를 참고", example = "STATION_NOT_FOUND", requiredMode = Schema.RequiredMode.REQUIRED) String error,
            @Schema(description = "민감정보·SQL·스택트레이스를 포함하지 않는 한국어 설명", example = "해당 충전소를 찾을 수 없습니다.", requiredMode = Schema.RequiredMode.REQUIRED) String message) {}
}
