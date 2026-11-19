package pl.miskiewicz.coupons.coupon.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import pl.miskiewicz.coupons.coupon.application.RedeemCouponCommand;

record RedeemCouponRequest(@NotBlank @Size(max = 64) String code, @NotBlank @Size(max = 64) String userId) {

    RedeemCouponCommand toCommand(String clientAddress) {
        return new RedeemCouponCommand(code, userId, clientAddress);
    }
}
