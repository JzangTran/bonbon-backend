package com.bonbon.backend.common.exception;

import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Every error leaves the API as RFC 9457 {@code application/problem+json} with a stable {@code code}.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    ProblemDetail handleBusiness(BusinessException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(ex.getStatus(), ex.getMessage());
        problem.setProperty("code", ex.getCode());
        ex.getProperties().forEach(problem::setProperty);
        return problem;
    }

    @ExceptionHandler(AccessDeniedException.class)
    ProblemDetail handleAccessDenied(AccessDeniedException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, "Bạn không có quyền thực hiện thao tác này.");
        problem.setProperty("code", "FORBIDDEN");
        return problem;
    }

    /** A constraint on a request header or parameter (for example the length of an Idempotency-Key) is the caller's mistake. */
    @ExceptionHandler(jakarta.validation.ConstraintViolationException.class)
    ProblemDetail handleConstraintViolation(jakarta.validation.ConstraintViolationException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Một số thông tin chưa hợp lệ.");
        problem.setProperty("code", "VALIDATION_FAILED");
        problem.setProperty("errors", ex.getConstraintViolations().stream()
                .map(v -> java.util.Map.of("field", v.getPropertyPath().toString().substring(v.getPropertyPath().toString().lastIndexOf('.') + 1), "message", v.getMessage())).toList());
        return problem;
    }

    @ExceptionHandler(Exception.class)
    ProblemDetail handleUnexpected(Exception ex) {
        ProblemDetail rejected = rejectedInput(ex);
        if (rejected != null) {
            return rejected;
        }
        log.error("Unhandled exception", ex);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, "Đã có lỗi xảy ra. Vui lòng thử lại sau.");
        problem.setProperty("code", "INTERNAL_ERROR");
        return problem;
    }

    /**
     * Input the caller chose that the stack cannot take is the caller's mistake, not ours: a badly percent-encoded query
     * string, a NUL character (PostgreSQL text cannot hold one) and a page number so large its offset overflows.
     */
    private static ProblemDetail rejectedInput(Throwable ex) {
        for (Throwable t = ex; t != null; t = t.getCause()) {
            String message = t.getMessage() == null ? "" : t.getMessage();
            if (t instanceof org.apache.tomcat.util.http.InvalidParameterException) {
                return badInput("INVALID_PARAMETER", "Đường dẫn có tham số chứa ký tự không hợp lệ.");
            }
            if (message.contains("invalid byte sequence for encoding")) {
                return badInput("INVALID_CHARACTER", "Văn bản có ký tự không hợp lệ.");
            }
            if (message.contains("Page offset exceeds")) {
                return badInput("INVALID_PAGE", "Trang hoặc kích thước trang không hợp lệ.");
            }
        }
        return null;
    }

    private static ProblemDetail badInput(String code, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail);
        problem.setProperty("code", code);
        return problem;
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Một số thông tin chưa hợp lệ.");
        problem.setProperty("code", "VALIDATION_FAILED");
        List<Map<String, String>> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> Map.of("field", fe.getField(),
                        "message", fe.getDefaultMessage() == null ? "Không hợp lệ" : fe.getDefaultMessage()))
                .toList();
        problem.setProperty("errors", errors);
        return ResponseEntity.badRequest().body(problem);
    }
}
