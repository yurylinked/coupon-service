package pl.miskiewicz.coupons.coupon.application;

import org.springframework.stereotype.Service;
import pl.miskiewicz.coupons.coupon.domain.CouponExpiredException;
import pl.miskiewicz.coupons.coupon.domain.CouponRepository;
import pl.miskiewicz.coupons.coupon.domain.CouponSnapshot;
import pl.miskiewicz.coupons.coupon.domain.LockNotAcquiredException;
import pl.miskiewicz.coupons.coupon.domain.RedemptionId;
import pl.miskiewicz.coupons.coupon.domain.RedemptionNotFoundException;
import pl.miskiewicz.coupons.coupon.domain.RedemptionRecord;
import pl.miskiewicz.coupons.coupon.domain.RedemptionStore;

@Service
public class RedemptionService {

    private final RedemptionStore redemptions;
    private final CouponRepository coupons;
    private final RedemptionBulkhead bulkhead;
    private final RedemptionOutcomes outcomes;

    public RedemptionService(
            RedemptionStore redemptions, CouponRepository coupons, RedemptionBulkhead bulkhead, RedemptionOutcomes outcomes) {
        this.redemptions = redemptions;
        this.coupons = coupons;
        this.bulkhead = bulkhead;
        this.outcomes = outcomes;
    }

    public RedemptionRecord get(String rawRedemptionId) {
        return redemptions.findById(RedemptionId.parse(rawRedemptionId))
                .orElseThrow(RedemptionNotFoundException::new);
    }

    // For a cancelled order. Repeating it changes nothing, so the order system can retry safely.
    public void cancel(String rawRedemptionId) {
        try {
            CancellationResult result = attemptCancel(rawRedemptionId);
            outcomes.cancelled(result);
        } catch (RuntimeException reason) {
            outcomes.cancellationRejected(reason);
            throw reason;
        }
    }

    private CancellationResult attemptCancel(String rawRedemptionId) {
        RedemptionRecord record = get(rawRedemptionId);
        if (record.cancelled()) {
            return CancellationResult.ALREADY_CANCELLED;
        }
        boolean cancelled = bulkhead.execute(record.redemption().code(), () -> cancelAtomically(record));
        return cancelled ? CancellationResult.CANCELLED : whyNotCancelled(record);
    }

    private boolean cancelAtomically(RedemptionRecord record) {
        try {
            return redemptions.cancel(new RedemptionId(record.redemption().id()));
        } catch (LockNotAcquiredException ex) {
            throw new RedemptionBusyException();
        }
    }

    // The atomic cancel changed nothing: either another cancel won or the coupon has expired. Anything else is a bug,
    // reported as one instead of as a retry the order system would repeat forever.
    private CancellationResult whyNotCancelled(RedemptionRecord record) {
        RedemptionId id = new RedemptionId(record.redemption().id());
        RedemptionRecord current = redemptions.findById(id)
                .orElseThrow(() -> new IllegalStateException("Redemption " + id.value() + " disappeared"));
        if (current.cancelled()) {
            return CancellationResult.ALREADY_CANCELLED;
        }
        CouponSnapshot coupon = coupons.findByCode(record.redemption().code())
                .orElseThrow(() -> new IllegalStateException("Coupon of redemption " + id.value() + " disappeared"));
        if (coupon.isExpired()) {
            throw new CouponExpiredException();
        }
        throw new IllegalStateException("Cancel of redemption " + id.value() + " changed nothing without a reason");
    }
}
