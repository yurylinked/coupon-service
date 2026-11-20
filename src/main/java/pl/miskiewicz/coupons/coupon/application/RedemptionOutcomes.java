package pl.miskiewicz.coupons.coupon.application;

import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import pl.miskiewicz.coupons.coupon.domain.CountryUnresolvedException;
import pl.miskiewicz.coupons.coupon.domain.CouponAlreadyUsedByUserException;
import pl.miskiewicz.coupons.coupon.domain.CouponCountryNotAllowedException;
import pl.miskiewicz.coupons.coupon.domain.CouponExhaustedException;
import pl.miskiewicz.coupons.coupon.domain.CouponExpiredException;
import pl.miskiewicz.coupons.coupon.domain.CouponNotFoundException;
import pl.miskiewicz.coupons.coupon.domain.InvalidUserIdException;
import pl.miskiewicz.coupons.coupon.domain.Redemption;
import pl.miskiewicz.coupons.coupon.domain.RedemptionBusyException;

// One counter per outcome (never tagged by coupon code, to keep cardinality bounded) and one structured log line
// per redemption. User ids and client IPs are deliberately not logged.
@Component
public class RedemptionOutcomes {

    static final String METRIC = "coupon.redemptions";
    private static final Logger LOG = LoggerFactory.getLogger(RedemptionOutcomes.class);

    private final MeterRegistry registry;

    public RedemptionOutcomes(MeterRegistry registry) {
        this.registry = registry;
    }

    void succeeded(Redemption redemption) {
        count("success");
        LOG.atInfo()
                .addKeyValue("event", "coupon.redemption")
                .addKeyValue("outcome", "success")
                .addKeyValue("couponCode", redemption.code().value())
                .addKeyValue("redemptionId", redemption.id())
                .addKeyValue("country", redemption.country().value())
                .log("Coupon redeemed");
    }

    void rejected(RuntimeException reason) {
        String outcome = outcomeOf(reason);
        count(outcome);
        LOG.atInfo()
                .addKeyValue("event", "coupon.redemption")
                .addKeyValue("outcome", outcome)
                .log("Coupon redemption rejected");
    }

    private void count(String outcome) {
        registry.counter(METRIC, "outcome", outcome).increment();
    }

    static String outcomeOf(RuntimeException reason) {
        return switch (reason) {
            case InvalidUserIdException ignored -> "invalid_user";
            case CouponNotFoundException ignored -> "not_found";
            case CountryUnresolvedException ignored -> "country_unresolved";
            case CouponCountryNotAllowedException ignored -> "country_not_allowed";
            case CouponAlreadyUsedByUserException ignored -> "already_used";
            case CouponExpiredException ignored -> "expired";
            case CouponExhaustedException ignored -> "exhausted";
            case RedemptionBusyException ignored -> "busy";
            default -> "error";
        };
    }
}
