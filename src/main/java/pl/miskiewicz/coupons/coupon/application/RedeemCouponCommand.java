package pl.miskiewicz.coupons.coupon.application;

import java.net.InetAddress;

public record RedeemCouponCommand(String code, String userId, InetAddress clientAddress) {
}
