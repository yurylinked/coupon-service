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
import pl.miskiewicz.coupons.coupon.application.RedemptionBusyException;
import pl.miskiewicz.coupons.coupon.domain.CountryUnresolvedException;
import pl.miskiewicz.coupons.coupon.domain.CouponAlreadyExistsException;
import pl.miskiewicz.coupons.coupon.domain.CouponAlreadyUsedByUserException;
import pl.miskiewicz.coupons.coupon.domain.CouponCountryNotAllowedException;
import pl.miskiewicz.coupons.coupon.domain.CouponExhaustedException;
import pl.miskiewicz.coupons.coupon.domain.CouponExpiredException;
import pl.miskiewicz.coupons.coupon.domain.CouponNotFoundException;
import pl.miskiewicz.coupons.coupon.domain.CouponNotYetValidException;
import pl.miskiewicz.coupons.coupon.domain.CouponSuspendedException;
import pl.miskiewicz.coupons.coupon.domain.DomainException;
import pl.miskiewicz.coupons.coupon.domain.InvalidCountryCodeException;
import pl.miskiewicz.coupons.coupon.domain.InvalidCouponCodeException;
import pl.miskiewicz.coupons.coupon.domain.InvalidExpiryException;
import pl.miskiewicz.coupons.coupon.domain.InvalidIdempotencyKeyException;
import pl.miskiewicz.coupons.coupon.domain.InvalidMaxUsesException;
import pl.miskiewicz.coupons.coupon.domain.InvalidRedemptionIdException;
import pl.miskiewicz.coupons.coupon.domain.InvalidUserIdException;
import pl.miskiewicz.coupons.coupon.domain.InvalidValidityPeriodException;
import pl.miskiewicz.coupons.coupon.domain.RedemptionCancelledException;
import pl.miskiewicz.coupons.coupon.domain.RedemptionNotFoundException;

@RestControllerAdvice
class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(ApiExceptionHandler.class);
    private static final String ERROR_CODE = "errorCode";
    private static final String CONNECTION_EXCEPTION_SQL_STATE_CLASS = "08";

    private final RetryAfter retryAfter;

    ApiExceptionHandler(RetryAfter retryAfter) {
        this.retryAfter = retryAfter;
    }

    @ExceptionHandler(DomainException.class)
    ResponseEntity<ProblemDetail> domainOutcome(DomainException ex) {
        Mapping mapping = mappingOf(ex);
        return ResponseEntity.status(mapping.status()).body(problem(mapping.status(), mapping.errorCode(), ex.getMessage()));
    }

    // Contention on one hot coupon is not an outage: 429 keeps it out of 5xx alerting, 503 stays for a lost database.
    @ExceptionHandler(RedemptionBusyException.class)
    ResponseEntity<ProblemDetail> redemptionBusy(RedemptionBusyException ex) {
        return retryLater(HttpStatus.TOO_MANY_REQUESTS,
                problem(HttpStatus.TOO_MANY_REQUESTS, ErrorCode.REDEMPTION_BUSY, ex.getMessage()));
    }

    // Exhaustive over the sealed hierarchy: a new domain outcome does not compile until it has an HTTP mapping.
    static Mapping mappingOf(DomainException ex) {
        return switch (ex) {
            case InvalidCouponCodeException ignored -> new Mapping(HttpStatus.BAD_REQUEST, ErrorCode.INVALID_COUPON_CODE);
            case InvalidCountryCodeException ignored -> new Mapping(HttpStatus.BAD_REQUEST, ErrorCode.INVALID_COUNTRY_CODE);
            case InvalidUserIdException ignored -> new Mapping(HttpStatus.BAD_REQUEST, ErrorCode.INVALID_USER_ID);
            case InvalidIdempotencyKeyException ignored -> new Mapping(HttpStatus.BAD_REQUEST, ErrorCode.INVALID_IDEMPOTENCY_KEY);
            case InvalidMaxUsesException ignored -> new Mapping(HttpStatus.BAD_REQUEST, ErrorCode.INVALID_MAX_USES);
            case InvalidRedemptionIdException ignored -> new Mapping(HttpStatus.BAD_REQUEST, ErrorCode.INVALID_REDEMPTION_ID);
            case InvalidExpiryException ignored -> new Mapping(HttpStatus.BAD_REQUEST, ErrorCode.INVALID_EXPIRY);
            case InvalidValidityPeriodException ignored -> new Mapping(HttpStatus.BAD_REQUEST, ErrorCode.INVALID_VALIDITY_PERIOD);
            case CouponNotFoundException ignored -> new Mapping(HttpStatus.NOT_FOUND, ErrorCode.COUPON_NOT_FOUND);
            case RedemptionNotFoundException ignored -> new Mapping(HttpStatus.NOT_FOUND, ErrorCode.REDEMPTION_NOT_FOUND);
            case CountryUnresolvedException ignored -> new Mapping(HttpStatus.FORBIDDEN, ErrorCode.COUNTRY_UNRESOLVED);
            case CouponCountryNotAllowedException ignored -> new Mapping(HttpStatus.FORBIDDEN, ErrorCode.COUPON_COUNTRY_NOT_ALLOWED);
            case CouponAlreadyExistsException ignored -> new Mapping(HttpStatus.CONFLICT, ErrorCode.COUPON_CODE_ALREADY_EXISTS);
            case CouponAlreadyUsedByUserException ignored -> new Mapping(HttpStatus.CONFLICT, ErrorCode.COUPON_ALREADY_USED_BY_USER);
            case RedemptionCancelledException ignored -> new Mapping(HttpStatus.CONFLICT, ErrorCode.REDEMPTION_CANCELLED);
            case CouponExpiredException ignored -> new Mapping(HttpStatus.CONFLICT, ErrorCode.COUPON_EXPIRED);
            case CouponSuspendedException ignored -> new Mapping(HttpStatus.CONFLICT, ErrorCode.COUPON_SUSPENDED);
            case CouponNotYetValidException ignored -> new Mapping(HttpStatus.CONFLICT, ErrorCode.COUPON_NOT_YET_VALID);
            case CouponExhaustedException ignored -> new Mapping(HttpStatus.CONFLICT, ErrorCode.COUPON_EXHAUSTED);
        };
    }

    record Mapping(HttpStatus status, ErrorCode errorCode) {
    }

    @ExceptionHandler(CannotCreateTransactionException.class)
    ResponseEntity<ProblemDetail> transactionUnavailable(CannotCreateTransactionException ex) {
        LOG.warn("Could not open a database transaction", ex);
        return retryLater(HttpStatus.SERVICE_UNAVAILABLE, databaseUnavailable());
    }

    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<ProblemDetail> dataAccessFailure(DataAccessException ex) {
        if (ex instanceof CannotGetJdbcConnectionException || isConnectionFailure(ex)) {
            LOG.warn("Database connection unavailable", ex);
            return retryLater(HttpStatus.SERVICE_UNAVAILABLE, databaseUnavailable());
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

    // Framework errors (404, 405, 415) build their body late, so their errorCode is added here.
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

    private ResponseEntity<ProblemDetail> retryLater(HttpStatus status, ProblemDetail body) {
        return ResponseEntity.status(status)
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
