package pl.miskiewicz.coupons.coupon.application;

import org.springframework.stereotype.Service;
import pl.miskiewicz.coupons.coupon.domain.LockNotAcquiredException;
import pl.miskiewicz.coupons.coupon.domain.RedemptionId;
import pl.miskiewicz.coupons.coupon.domain.RedemptionNotFoundException;
import pl.miskiewicz.coupons.coupon.domain.RedemptionRecord;
import pl.miskiewicz.coupons.coupon.domain.RedemptionStore;

@Service
public class RedemptionService {

    private final RedemptionStore redemptions;
    private final RedemptionBulkhead bulkhead;
    private final RedemptionOutcomes outcomes;

    public RedemptionService(RedemptionStore redemptions, RedemptionBulkhead bulkhead, RedemptionOutcomes outcomes) {
        this.redemptions = redemptions;
        this.bulkhead = bulkhead;
        this.outcomes = outcomes;
    }

    public RedemptionRecord get(String rawRedemptionId) {
        return redemptions.findById(RedemptionId.parse(rawRedemptionId))
                .orElseThrow(RedemptionNotFoundException::new);
    }

    // Idempotent, so the order system can retry it.
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
            return redemptions.cancel(record.redemption().id());
        } catch (LockNotAcquiredException ex) {
            throw new RedemptionBusyException();
        }
    }

    // The only legitimate reason is a concurrent cancel that won. Anything else is a bug, not a retryable 429.
    private CancellationResult whyNotCancelled(RedemptionRecord record) {
        RedemptionId id = record.redemption().id();
        RedemptionRecord current = redemptions.findById(id)
                .orElseThrow(() -> new IllegalStateException("Redemption " + id.value() + " disappeared"));
        if (current.cancelled()) {
            return CancellationResult.ALREADY_CANCELLED;
        }
        throw new IllegalStateException("Cancel of redemption " + id.value() + " changed nothing without a reason");
    }
}
