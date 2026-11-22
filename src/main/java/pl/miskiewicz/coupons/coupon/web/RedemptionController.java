package pl.miskiewicz.coupons.coupon.web;

import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import pl.miskiewicz.coupons.coupon.application.RedeemCouponService;
import pl.miskiewicz.coupons.coupon.application.RedemptionResult;
import pl.miskiewicz.coupons.coupon.application.RedemptionService;

@RestController
// produces makes Spring refuse an unacceptable Accept header before the handler runs, not after it has written.
@RequestMapping(
        path = "/api/v1/redemptions",
        produces = {MediaType.APPLICATION_JSON_VALUE, MediaType.APPLICATION_PROBLEM_JSON_VALUE})
class RedemptionController {

    private static final String IDEMPOTENCY_KEY = "Idempotency-Key";
    private static final String IDEMPOTENT_REPLAYED = "Idempotent-Replayed";

    private final RedeemCouponService redeemCouponService;
    private final RedemptionService redemptionService;

    RedemptionController(RedeemCouponService redeemCouponService, RedemptionService redemptionService) {
        this.redeemCouponService = redeemCouponService;
        this.redemptionService = redemptionService;
    }

    @PostMapping
    ResponseEntity<RedemptionResponse> redeem(
            @RequestBody RedeemCouponRequest request,
            @RequestHeader(name = IDEMPOTENCY_KEY, required = false) String idempotencyKey,
            HttpServletRequest httpRequest) {
        RedemptionResult result =
                redeemCouponService.redeem(request.toCommand(httpRequest.getRemoteAddr(), idempotencyKey));
        ResponseEntity.BodyBuilder response = ResponseEntity
                .created(URI.create("/api/v1/redemptions/" + result.redemption().id().value()));
        if (result.replayed()) {
            response.header(IDEMPOTENT_REPLAYED, "true");
        }
        return response.body(RedemptionResponse.from(result.redemption()));
    }

    @GetMapping("/{redemptionId}")
    RedemptionResponse get(@PathVariable String redemptionId) {
        return RedemptionResponse.from(redemptionService.get(redemptionId));
    }

    @DeleteMapping("/{redemptionId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void cancel(@PathVariable String redemptionId) {
        redemptionService.cancel(redemptionId);
    }
}
