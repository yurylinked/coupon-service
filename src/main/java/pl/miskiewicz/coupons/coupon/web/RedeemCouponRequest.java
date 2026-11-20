package pl.miskiewicz.coupons.coupon.web;

import pl.miskiewicz.coupons.coupon.application.RedeemCouponCommand;

record RedeemCouponRequest(String code, String userId) {

    RedeemCouponCommand toCommand(String clientAddress, String idempotencyKey) {
        return new RedeemCouponCommand(code, userId, clientAddress, idempotencyKey);
    }
}
