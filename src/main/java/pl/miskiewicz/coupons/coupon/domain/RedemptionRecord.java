package pl.miskiewicz.coupons.coupon.domain;

import java.time.Instant;
import java.util.Objects;

public record RedemptionRecord(Redemption redemption, Instant cancelledAt) {

    public RedemptionRecord {
        Objects.requireNonNull(redemption, "redemption");
    }

    public boolean cancelled() {
        return cancelledAt != null;
    }
}
