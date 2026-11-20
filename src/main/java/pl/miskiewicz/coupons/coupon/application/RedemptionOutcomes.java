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
import pl.miskiewicz.coupons.coupon.domain.InvalidActiveFlagException;
import pl.miskiewicz.coupons.coupon.domain.InvalidIdempotencyKeyException;
import pl.miskiewicz.coupons.coupon.domain.InvalidMaxUsesException;
import pl.miskiewicz.coupons.coupon.domain.InvalidRedemptionIdException;
import pl.miskiewicz.coupons.coupon.domain.InvalidUserIdException;
import pl.miskiewicz.coupons.coupon.domain.InvalidValidityPeriodException;
import pl.miskiewicz.coupons.coupon.domain.RedemptionCancelledException;
import pl.miskiewicz.coupons.coupon.domain.RedemptionNotFoundException;

// Metrics are tagged by outcome only, never by coupon code, to keep cardinality bounded. Logs carry neither user id
// nor client IP.
@Component
public class RedemptionOutcomes {

    private static final String REDEMPTIONS = "coupon.redemptions";
    private static final String CANCELLATIONS = "coupon.cancellations";
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
                .addKeyValue("redemptionId", result.redemption().id().value())
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
            case RedemptionBusyException _ -> "busy";
            default -> "error";
        };
    }

    private static String outcomeOf(DomainException reason) {
        return switch (reason) {
            case InvalidUserIdException _ -> "invalid_user";
            case InvalidIdempotencyKeyException _ -> "invalid_idempotency_key";
            case InvalidActiveFlagException _ -> "invalid_active_flag";
            case InvalidMaxUsesException _ -> "invalid_max_uses";
            case InvalidCouponCodeException _ -> "invalid_code";
            case InvalidRedemptionIdException _ -> "invalid_redemption_id";
            case InvalidCountryCodeException _ -> "invalid_country";
            case InvalidExpiryException _ -> "invalid_expiry";
            case InvalidValidityPeriodException _ -> "invalid_validity_period";
            case CouponNotFoundException _ -> "not_found";
            case RedemptionNotFoundException _ -> "redemption_not_found";
            case CouponAlreadyExistsException _ -> "already_exists";
            case CountryUnresolvedException _ -> "country_unresolved";
            case CouponCountryNotAllowedException _ -> "country_not_allowed";
            case CouponAlreadyUsedByUserException _ -> "already_used";
            case RedemptionCancelledException _ -> "redemption_cancelled";
            case CouponExpiredException _ -> "expired";
            case CouponSuspendedException _ -> "suspended";
            case CouponNotYetValidException _ -> "not_yet_valid";
            case CouponExhaustedException _ -> "exhausted";
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
