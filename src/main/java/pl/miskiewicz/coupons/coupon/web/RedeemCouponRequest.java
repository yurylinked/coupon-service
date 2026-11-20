package pl.miskiewicz.coupons.coupon.web;

import pl.miskiewicz.coupons.coupon.application.RedeemCouponCommand;

// Both fields are validated by their value objects, so each has exactly one error code.
record RedeemCouponRequest(String code, String userId) {

    RedeemCouponCommand toCommand(String clientAddress, String idempotencyKey) {
        return new RedeemCouponCommand(code, userId, clientAddress, idempotencyKey);
    }
}
