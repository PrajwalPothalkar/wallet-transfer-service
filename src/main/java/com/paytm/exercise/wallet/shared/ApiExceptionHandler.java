package com.paytm.exercise.wallet.shared;

import com.paytm.exercise.wallet.api.ApiError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class ApiExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ApiError> handleApiException(ApiException exception) {
        log.info("request_rejected code={}", exception.code());
        return ResponseEntity.status(exception.status()).body(new ApiError(exception.code(), exception.getMessage()));
    }

    /**
     * Unparseable or absent body, and decimal values in integer-paise fields. These are caller
     * errors: they must not surface as 500s, which would also poison the error-rate metric.
     */
    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    ResponseEntity<ApiError> handleUnreadableRequest(Exception exception) {
        log.info("request_rejected code=malformed_request reason={}", exception.getClass().getSimpleName());
        return ResponseEntity.badRequest().body(new ApiError("malformed_request",
                "request body or path variable could not be parsed; amounts must be integer paise"));
    }

    /** Spring MVC's own 404/405/415 signals already carry the right status; preserve it. */
    @ExceptionHandler(ErrorResponseException.class)
    ResponseEntity<ApiError> handleSpringMvcError(ErrorResponseException exception) {
        log.info("request_rejected code=request_not_supported status={}", exception.getStatusCode().value());
        return ResponseEntity.status(exception.getStatusCode())
                .body(new ApiError("request_not_supported", "request could not be routed or accepted"));
    }

    /** A path that matches no controller and no static resource is a plain 404, not a server error. */
    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ApiError> handleUnknownPath(NoResourceFoundException exception) {
        log.info("request_rejected code=request_not_supported status={}", HttpStatus.NOT_FOUND.value());
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ApiError("request_not_supported", "request could not be routed or accepted"));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> handleUnexpected(Exception exception) {
        log.error("unhandled_request_error", exception);
        return ResponseEntity.internalServerError().body(new ApiError("internal_error", "unexpected server error"));
    }
}
