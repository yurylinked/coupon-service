package pl.miskiewicz.coupons.coupon.web;

import jakarta.validation.constraints.NotNull;

record CouponStatusRequest(@NotNull Boolean active) {
}
