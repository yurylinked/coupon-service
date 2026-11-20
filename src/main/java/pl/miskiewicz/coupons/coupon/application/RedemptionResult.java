package pl.miskiewicz.coupons.coupon.application;

import java.util.Objects;
import pl.miskiewicz.coupons.coupon.domain.Redemption;

public record RedemptionResult(Redemption redemption, boolean replayed) {

    public RedemptionResult {
        Objects.requireNonNull(redemption, "redemption");
    }
}
