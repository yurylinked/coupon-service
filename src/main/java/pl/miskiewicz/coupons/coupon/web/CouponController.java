package pl.miskiewicz.coupons.coupon.web;

import jakarta.validation.Valid;
import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import pl.miskiewicz.coupons.coupon.application.CouponService;
import pl.miskiewicz.coupons.coupon.domain.Coupon;

@RestController
@RequestMapping("/api/v1/coupons")
class CouponController {

    private final CouponService couponService;

    CouponController(CouponService couponService) {
        this.couponService = couponService;
    }

    @PostMapping
    ResponseEntity<CouponResponse> create(@RequestBody CreateCouponRequest request) {
        Coupon coupon = couponService.create(request.toCommand());
        return ResponseEntity
                .created(URI.create("/api/v1/coupons/" + coupon.code().value()))
                .body(CouponResponse.from(coupon));
    }

    @GetMapping("/{code}")
    CouponResponse get(@PathVariable String code) {
        return CouponResponse.from(couponService.get(code));
    }

    @PatchMapping("/{code}")
    CouponResponse setActive(@PathVariable String code, @Valid @RequestBody CouponStatusRequest request) {
        return CouponResponse.from(couponService.setActive(code, request.active()));
    }
}
