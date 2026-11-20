package pl.miskiewicz.coupons.coupon.application;

import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
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
import pl.miskiewicz.coupons.coupon.domain.InvalidRedemptionIdException;
import pl.miskiewicz.coupons.coupon.domain.InvalidUserIdException;
import pl.miskiewicz.coupons.coupon.domain.InvalidValidityPeriodException;
import pl.miskiewicz.coupons.coupon.domain.RedemptionCancelledException;
import pl.miskiewicz.coupons.coupon.domain.RedemptionNotFoundException;

// Metrics are tagged by outcome only, never by coupon code, to keep cardinality bounded. Logs carry neither user id
// nor client IP.
@Component
public class RedemptionOutcomes {

    static final String REDEMPTIONS = "coupon.redemptions";
    static final String CANCELLATIONS = "coupon.cancellations";
    private static final Logger LOG = LoggerFactory.getLogger(RedemptionOutcomes.class);

    private final MeterRegistry registry;

    public RedemptionOutcomes(MeterRegistry registry) {
        this.registry = registry;
    }

    void redeemed(RedemptionResult result) {
        String outcome = result.replayed() ? "replayed" : "success";
        count(REDEMPTIONS, outcome);
        LOG.atInfo()
                .addKeyValue("event", "coupon.redemption")
                .addKeyValue("outcome", outcome)
                .addKeyValue("couponCode", result.redemption().code().value())
                .addKeyValue("redemptionId", result.redemption().id())
                .addKeyValue("country", result.redemption().country().value())
                .log("Coupon redeemed");
    }

    void redemptionRejected(RuntimeException reason) {
        rejected(REDEMPTIONS, "coupon.redemption", "Coupon redemption rejected", reason);
    }

    void cancelled(CancellationResult result) {
        String outcome = result == CancellationResult.CANCELLED ? "success" : "already_cancelled";
        count(CANCELLATIONS, outcome);
        LOG.atInfo()
                .addKeyValue("event", "coupon.cancellation")
                .addKeyValue("outcome", outcome)
                .log("Coupon redemption cancelled");
    }

    void cancellationRejected(RuntimeException reason) {
        rejected(CANCELLATIONS, "coupon.cancellation", "Coupon cancellation rejected", reason);
    }

    static String outcomeOf(RuntimeException reason) {
        return switch (reason) {
            case DomainException domain -> outcomeOf(domain);
            case RedemptionBusyException ignored -> "busy";
            default -> "error";
        };
    }

    private static String outcomeOf(DomainException reason) {
        return switch (reason) {
            case InvalidUserIdException ignored -> "invalid_user";
            case InvalidIdempotencyKeyException ignored -> "invalid_idempotency_key";
            case InvalidCouponCodeException ignored -> "invalid_code";
            case InvalidRedemptionIdException ignored -> "invalid_redemption_id";
            case InvalidCountryCodeException ignored -> "invalid_country";
            case InvalidExpiryException ignored -> "invalid_expiry";
            case InvalidValidityPeriodException ignored -> "invalid_validity_period";
            case CouponNotFoundException ignored -> "not_found";
            case RedemptionNotFoundException ignored -> "redemption_not_found";
            case CouponAlreadyExistsException ignored -> "already_exists";
            case CountryUnresolvedException ignored -> "country_unresolved";
            case CouponCountryNotAllowedException ignored -> "country_not_allowed";
            case CouponAlreadyUsedByUserException ignored -> "already_used";
            case RedemptionCancelledException ignored -> "redemption_cancelled";
            case CouponExpiredException ignored -> "expired";
            case CouponSuspendedException ignored -> "suspended";
            case CouponNotYetValidException ignored -> "not_yet_valid";
            case CouponExhaustedException ignored -> "exhausted";
        };
    }

    private void rejected(String metric, String event, String message, RuntimeException reason) {
        String outcome = outcomeOf(reason);
        count(metric, outcome);
        LOG.atInfo()
                .addKeyValue("event", event)
                .addKeyValue("outcome", outcome)
                .log(message);
    }

    private void count(String metric, String outcome) {
        registry.counter(metric, "outcome", outcome).increment();
    }
}
