package pl.miskiewicz.coupons.coupon.application;

public record CreateCouponCommand(String code, int maxUses, String country) {
}
