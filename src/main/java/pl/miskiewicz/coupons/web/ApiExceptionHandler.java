package pl.miskiewicz.coupons.web;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import pl.miskiewicz.coupons.coupon.domain.CouponAlreadyExistsException;
import pl.miskiewicz.coupons.coupon.domain.CouponNotFoundException;
import pl.miskiewicz.coupons.coupon.domain.InvalidCountryCodeException;
import pl.miskiewicz.coupons.coupon.domain.InvalidCouponCodeException;

@RestControllerAdvice
class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final String ERROR_CODE = "errorCode";

    @ExceptionHandler(InvalidCouponCodeException.class)
    ProblemDetail invalidCouponCode(InvalidCouponCodeException ex) {
        return problem(HttpStatus.BAD_REQUEST, ErrorCode.INVALID_COUPON_CODE, ex.getMessage());
    }

    @ExceptionHandler(InvalidCountryCodeException.class)
    ProblemDetail invalidCountryCode(InvalidCountryCodeException ex) {
        return problem(HttpStatus.BAD_REQUEST, ErrorCode.INVALID_COUNTRY_CODE, ex.getMessage());
    }

    @ExceptionHandler(CouponAlreadyExistsException.class)
    ProblemDetail couponAlreadyExists(CouponAlreadyExistsException ex) {
        return problem(HttpStatus.CONFLICT, ErrorCode.COUPON_CODE_ALREADY_EXISTS, ex.getMessage());
    }

    @ExceptionHandler(CouponNotFoundException.class)
    ProblemDetail couponNotFound(CouponNotFoundException ex) {
        return problem(HttpStatus.NOT_FOUND, ErrorCode.COUPON_NOT_FOUND, ex.getMessage());
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        ProblemDetail body = problem(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_FAILED, "Request validation failed");
        List<Map<String, String>> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> Map.of(
                        "field", error.getField(),
                        "message", String.valueOf(error.getDefaultMessage())))
                .sorted(Comparator.comparing((Map<String, String> error) -> error.get("field"))
                        .thenComparing(error -> error.get("message")))
                .toList();
        body.setProperty("errors", errors);
        return handleExceptionInternal(ex, body, headers, HttpStatus.BAD_REQUEST, request);
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception ex, Object body, HttpHeaders headers, HttpStatusCode statusCode, WebRequest request) {
        if (body instanceof ProblemDetail problemDetail && !hasErrorCode(problemDetail)) {
            ErrorCode errorCode = statusCode.value() == HttpStatus.BAD_REQUEST.value()
                    ? ErrorCode.MALFORMED_REQUEST
                    : ErrorCode.REQUEST_REJECTED;
            problemDetail.setProperty(ERROR_CODE, errorCode.name());
        }
        return super.handleExceptionInternal(ex, body, headers, statusCode, request);
    }

    private static boolean hasErrorCode(ProblemDetail problemDetail) {
        return problemDetail.getProperties() != null && problemDetail.getProperties().containsKey(ERROR_CODE);
    }

    private static ProblemDetail problem(HttpStatus status, ErrorCode errorCode, String detail) {
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(status, detail);
        problemDetail.setProperty(ERROR_CODE, errorCode.name());
        return problemDetail;
    }
}
