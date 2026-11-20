package pl.miskiewicz.coupons.web;

import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;
import java.util.random.RandomGenerator;
import org.springframework.stereotype.Component;

// Clients rejected by the same hot-row stall must not come back as one synchronized wave,
// so the advertised delay is drawn from a window instead of being a constant.
@Component
class RetryAfter {

    private static final int MIN_SECONDS = 1;
    private static final int MAX_SECONDS = 3;

    private final Supplier<RandomGenerator> random;

    RetryAfter() {
        this(ThreadLocalRandom::current);
    }

    RetryAfter(Supplier<RandomGenerator> random) {
        this.random = random;
    }

    String seconds() {
        return Integer.toString(random.get().nextInt(MIN_SECONDS, MAX_SECONDS + 1));
    }
}
