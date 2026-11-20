package pl.miskiewicz.coupons.web;

import java.sql.SQLException;
import java.util.Comparator;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import pl.miskiewicz.coupons.coupon.domain.CouponAlreadyExistsException;
import pl.miskiewicz.coupons.coupon.domain.CouponAlreadyUsedByUserException;
import pl.miskiewicz.coupons.coupon.domain.CouponCountryNotAllowedException;
import pl.miskiewicz.coupons.coupon.domain.CouponExhaustedException;
import pl.miskiewicz.coupons.coupon.domain.CouponExpiredException;
import pl.miskiewicz.coupons.coupon.domain.CouponNotFoundException;
import pl.miskiewicz.coupons.coupon.domain.CountryUnresolvedException;
import pl.miskiewicz.coupons.coupon.domain.InvalidCountryCodeException;
import pl.miskiewicz.coupons.coupon.domain.InvalidCouponCodeException;
import pl.miskiewicz.coupons.coupon.domain.InvalidExpiryException;
import pl.miskiewicz.coupons.coupon.domain.InvalidUserIdException;
import pl.miskiewicz.coupons.coupon.domain.RedemptionBusyException;

@RestControllerAdvice
class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(ApiExceptionHandler.class);
    private static final String ERROR_CODE = "errorCode";
    private static final String CONNECTION_EXCEPTION_SQL_STATE_CLASS = "08";

    private final RetryAfter retryAfter;

    ApiExceptionHandler(RetryAfter retryAfter) {
        this.retryAfter = retryAfter;
    }

    @ExceptionHandler(InvalidCouponCodeException.class)
    ProblemDetail invalidCouponCode(InvalidCouponCodeException ex) {
        return problem(HttpStatus.BAD_REQUEST, ErrorCode.INVALID_COUPON_CODE, ex.getMessage());
    }

    @ExceptionHandler(InvalidCountryCodeException.class)
    ProblemDetail invalidCountryCode(InvalidCountryCodeException ex) {
        return problem(HttpStatus.BAD_REQUEST, ErrorCode.INVALID_COUNTRY_CODE, ex.getMessage());
    }

    @ExceptionHandler(InvalidUserIdException.class)
    ProblemDetail invalidUserId(InvalidUserIdException ex) {
        return problem(HttpStatus.BAD_REQUEST, ErrorCode.INVALID_USER_ID, ex.getMessage());
    }

    @ExceptionHandler(InvalidExpiryException.class)
    ProblemDetail invalidExpiry(InvalidExpiryException ex) {
        return problem(HttpStatus.BAD_REQUEST, ErrorCode.INVALID_EXPIRY, ex.getMessage());
    }

    @ExceptionHandler(CouponAlreadyExistsException.class)
    ProblemDetail couponAlreadyExists(CouponAlreadyExistsException ex) {
        return problem(HttpStatus.CONFLICT, ErrorCode.COUPON_CODE_ALREADY_EXISTS, ex.getMessage());
    }

    @ExceptionHandler(CouponNotFoundException.class)
    ProblemDetail couponNotFound(CouponNotFoundException ex) {
        return problem(HttpStatus.NOT_FOUND, ErrorCode.COUPON_NOT_FOUND, ex.getMessage());
    }

    @ExceptionHandler(CountryUnresolvedException.class)
    ProblemDetail countryUnresolved(CountryUnresolvedException ex) {
        return problem(HttpStatus.FORBIDDEN, ErrorCode.COUNTRY_UNRESOLVED, ex.getMessage());
    }

    @ExceptionHandler(CouponCountryNotAllowedException.class)
    ProblemDetail couponCountryNotAllowed(CouponCountryNotAllowedException ex) {
        return problem(HttpStatus.FORBIDDEN, ErrorCode.COUPON_COUNTRY_NOT_ALLOWED, ex.getMessage());
    }

    @ExceptionHandler(CouponAlreadyUsedByUserException.class)
    ProblemDetail couponAlreadyUsedByUser(CouponAlreadyUsedByUserException ex) {
        return problem(HttpStatus.CONFLICT, ErrorCode.COUPON_ALREADY_USED_BY_USER, ex.getMessage());
    }

    @ExceptionHandler(CouponExpiredException.class)
    ProblemDetail couponExpired(CouponExpiredException ex) {
        return problem(HttpStatus.CONFLICT, ErrorCode.COUPON_EXPIRED, ex.getMessage());
    }

    @ExceptionHandler(CouponExhaustedException.class)
    ProblemDetail couponExhausted(CouponExhaustedException ex) {
        return problem(HttpStatus.CONFLICT, ErrorCode.COUPON_EXHAUSTED, ex.getMessage());
    }

    @ExceptionHandler(RedemptionBusyException.class)
    ResponseEntity<ProblemDetail> redemptionBusy(RedemptionBusyException ex) {
        return retryLater(problem(HttpStatus.SERVICE_UNAVAILABLE, ErrorCode.REDEMPTION_BUSY, ex.getMessage()));
    }

    @ExceptionHandler(CannotCreateTransactionException.class)
    ResponseEntity<ProblemDetail> transactionUnavailable(CannotCreateTransactionException ex) {
        LOG.warn("Could not open a database transaction", ex);
        return retryLater(databaseUnavailable());
    }

    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<ProblemDetail> dataAccessFailure(DataAccessException ex) {
        if (ex instanceof CannotGetJdbcConnectionException || isConnectionFailure(ex)) {
            LOG.warn("Database connection unavailable", ex);
            return retryLater(databaseUnavailable());
        }
        LOG.error("Unexpected database error", ex);
        return ResponseEntity.internalServerError().body(internalError());
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> unexpected(Exception ex) {
        LOG.error("Unexpected error", ex);
        return ResponseEntity.internalServerError().body(internalError());
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        ProblemDetail body = problem(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_FAILED, "Request validation failed");
        List<FieldViolation> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> new FieldViolation(error.getField(), String.valueOf(error.getDefaultMessage())))
                .sorted(Comparator.comparing(FieldViolation::field).thenComparing(FieldViolation::message))
                .toList();
        body.setProperty("errors", errors);
        return handleExceptionInternal(ex, body, headers, HttpStatus.BAD_REQUEST, request);
    }

    // Framework exceptions such as 404/405/415 build their ProblemDetail late, inside handleExceptionInternal,
    // so the errorCode is added at the last step where the final body is known.
    @Override
    protected ResponseEntity<Object> createResponseEntity(
            Object body, HttpHeaders headers, HttpStatusCode statusCode, WebRequest request) {
        if (body instanceof ProblemDetail problemDetail && !hasErrorCode(problemDetail)) {
            problemDetail.setProperty(ERROR_CODE, errorCodeFor(statusCode).name());
        }
        return super.createResponseEntity(body, headers, statusCode, request);
    }

    private static ErrorCode errorCodeFor(HttpStatusCode status) {
        if (status.is5xxServerError()) {
            return ErrorCode.INTERNAL_ERROR;
        }
        return switch (status.value()) {
            case 400 -> ErrorCode.MALFORMED_REQUEST;
            case 404 -> ErrorCode.NOT_FOUND;
            case 405 -> ErrorCode.METHOD_NOT_ALLOWED;
            case 406 -> ErrorCode.NOT_ACCEPTABLE;
            case 415 -> ErrorCode.UNSUPPORTED_MEDIA_TYPE;
            default -> ErrorCode.REQUEST_REJECTED;
        };
    }

    private static boolean isConnectionFailure(DataAccessException ex) {
        return ex.getMostSpecificCause() instanceof SQLException sqlException
                && sqlException.getSQLState() != null
                && sqlException.getSQLState().startsWith(CONNECTION_EXCEPTION_SQL_STATE_CLASS);
    }

    private static boolean hasErrorCode(ProblemDetail problemDetail) {
        return problemDetail.getProperties() != null && problemDetail.getProperties().containsKey(ERROR_CODE);
    }

    private ResponseEntity<ProblemDetail> retryLater(ProblemDetail body) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, retryAfter.seconds())
                .body(body);
    }

    private static ProblemDetail databaseUnavailable() {
        return problem(HttpStatus.SERVICE_UNAVAILABLE, ErrorCode.SERVICE_UNAVAILABLE,
                "Service temporarily unavailable, retry the request");
    }

    private static ProblemDetail internalError() {
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, ErrorCode.INTERNAL_ERROR, "Unexpected error");
    }

    private static ProblemDetail problem(HttpStatus status, ErrorCode errorCode, String detail) {
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(status, detail);
        problemDetail.setProperty(ERROR_CODE, errorCode.name());
        return problemDetail;
    }
}
