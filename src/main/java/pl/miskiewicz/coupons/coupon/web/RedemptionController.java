package pl.miskiewicz.coupons.coupon.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import pl.miskiewicz.coupons.coupon.application.RedeemCouponService;

@RestController
@RequestMapping("/api/v1/redemptions")
class RedemptionController {

    private final RedeemCouponService redeemCouponService;

    RedemptionController(RedeemCouponService redeemCouponService) {
        this.redeemCouponService = redeemCouponService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    RedemptionResponse redeem(@Valid @RequestBody RedeemCouponRequest request, HttpServletRequest httpRequest) {
        return RedemptionResponse.from(redeemCouponService.redeem(request.toCommand(httpRequest.getRemoteAddr())));
    }
}
