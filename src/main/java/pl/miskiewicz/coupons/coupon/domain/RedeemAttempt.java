package pl.miskiewicz.coupons.coupon.domain;

import java.util.Objects;

// What the atomic redemption did. Only Redeemed changed anything; the other outcomes were rolled back.
public sealed interface RedeemAttempt {

    record Redeemed(Redemption redemption) implements RedeemAttempt {

        public Redeemed {
            Objects.requireNonNull(redemption, "redemption");
        }
    }

    record AlreadyRedeemed() implements RedeemAttempt {
    }

    record NoUseLeft() implements RedeemAttempt {
    }
}
