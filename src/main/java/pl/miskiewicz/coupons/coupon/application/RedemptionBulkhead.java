package pl.miskiewicz.coupons.coupon.application;

import java.time.Duration;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import pl.miskiewicz.coupons.coupon.domain.CouponCode;
import pl.miskiewicz.coupons.coupon.domain.RedemptionBusyException;

// Caps how many redemptions of one coupon may wait on its row lock, so a hot coupon cannot hold the whole pool.
// Coupons are hashed onto a fixed array of fair semaphores: memory stays bounded, at the cost of two coupons
// occasionally sharing a stripe (about 0.1% of pairs with 1024 stripes).
@Component
public class RedemptionBulkhead {

    private final Semaphore[] stripes;
    private final long maxWaitNanos;

    public RedemptionBulkhead(
            @Value("${coupons.redemption.bulkhead.permits-per-stripe:4}") int permitsPerStripe,
            @Value("${coupons.redemption.bulkhead.stripes:1024}") int stripeCount,
            @Value("${coupons.redemption.bulkhead.max-wait:200ms}") Duration maxWait) {
        if (permitsPerStripe < 1 || stripeCount < 1 || maxWait.isNegative()) {
            throw new IllegalArgumentException("Bulkhead needs at least one permit, one stripe and a non-negative wait");
        }
        this.stripes = new Semaphore[stripeCount];
        for (int i = 0; i < stripeCount; i++) {
            stripes[i] = new Semaphore(permitsPerStripe, true);
        }
        this.maxWaitNanos = maxWait.toNanos();
    }

    public <T> T execute(CouponCode code, Supplier<T> action) {
        Semaphore stripe = stripes[stripeIndex(code)];
        acquire(stripe);
        try {
            return action.get();
        } finally {
            stripe.release();
        }
    }

    int stripeIndex(CouponCode code) {
        return Math.floorMod(code.value().hashCode(), stripes.length);
    }

    private void acquire(Semaphore stripe) {
        try {
            if (!stripe.tryAcquire(maxWaitNanos, TimeUnit.NANOSECONDS)) {
                throw new RedemptionBusyException();
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new RedemptionBusyException();
        }
    }
}
