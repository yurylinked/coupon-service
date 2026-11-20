package pl.miskiewicz.coupons.coupon.application;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import pl.miskiewicz.coupons.coupon.domain.CouponCode;
import pl.miskiewicz.coupons.coupon.domain.RedemptionNotFoundException;
import pl.miskiewicz.coupons.coupon.domain.RedemptionStore;

// Gives a use back when the order that consumed it is cancelled (Empik reactivates such codes within 24 hours,
// unless they have expired). Cancelling twice is harmless, so the order system can retry safely.
@Service
public class CancelRedemptionService {

    private static final Pattern CANONICAL_UUID =
            Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    private final RedemptionStore redemptions;
    private final RedemptionBulkhead bulkhead;
    private final RedemptionOutcomes outcomes;
    private final Clock clock;

    public CancelRedemptionService(
            RedemptionStore redemptions, RedemptionBulkhead bulkhead, RedemptionOutcomes outcomes, Clock clock) {
        this.redemptions = redemptions;
        this.bulkhead = bulkhead;
        this.outcomes = outcomes;
        this.clock = clock;
    }

    public void cancel(String rawRedemptionId) {
        try {
            CouponCode code = attempt(rawRedemptionId);
            outcomes.cancelled(code);
        } catch (RuntimeException reason) {
            outcomes.cancellationRejected(reason);
            throw reason;
        }
    }

    private CouponCode attempt(String rawRedemptionId) {
        UUID redemptionId = parseId(rawRedemptionId).orElseThrow(RedemptionNotFoundException::new);
        CouponCode code = redemptions.findCouponCode(redemptionId).orElseThrow(RedemptionNotFoundException::new);
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        return bulkhead.execute(code, () -> {
            redemptions.cancel(redemptionId, now);
            return code;
        });
    }

    // UUID.fromString accepts shortened forms such as "1-2-3-4-5"; only the canonical form can be a redemption id.
    private static Optional<UUID> parseId(String raw) {
        if (raw == null || !CANONICAL_UUID.matcher(raw).matches()) {
            return Optional.empty();
        }
        return Optional.of(UUID.fromString(raw));
    }
}
