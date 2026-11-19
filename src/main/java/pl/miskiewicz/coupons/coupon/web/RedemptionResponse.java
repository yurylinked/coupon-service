package pl.miskiewicz.coupons.coupon.web;

import java.time.Instant;
import java.util.UUID;
import pl.miskiewicz.coupons.coupon.domain.Redemption;

record RedemptionResponse(UUID redemptionId, String code, String userId, Instant redeemedAt) {

    static RedemptionResponse from(Redemption redemption) {
        return new RedemptionResponse(
                redemption.id(),
                redemption.code().value(),
                redemption.userId().value(),
                redemption.redeemedAt());
    }
}
