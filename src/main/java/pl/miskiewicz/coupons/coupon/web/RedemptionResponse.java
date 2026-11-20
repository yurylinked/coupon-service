package pl.miskiewicz.coupons.coupon.web;

import java.time.Instant;
import java.util.UUID;
import pl.miskiewicz.coupons.coupon.domain.Redemption;
import pl.miskiewicz.coupons.coupon.domain.RedemptionRecord;

record RedemptionResponse(UUID redemptionId, String code, String userId, Instant redeemedAt, Instant cancelledAt) {

    static RedemptionResponse from(Redemption redemption) {
        return from(new RedemptionRecord(redemption, null));
    }

    static RedemptionResponse from(RedemptionRecord record) {
        Redemption redemption = record.redemption();
        return new RedemptionResponse(
                redemption.id(),
                redemption.code().value(),
                redemption.userId().value(),
                redemption.redeemedAt(),
                record.cancelledAt());
    }
}
