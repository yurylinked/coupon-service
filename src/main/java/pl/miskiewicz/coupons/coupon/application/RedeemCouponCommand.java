package pl.miskiewicz.coupons.coupon.application;

public record RedeemCouponCommand(String code, String userId, String clientAddress) {
}
