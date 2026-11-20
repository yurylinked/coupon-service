package pl.miskiewicz.coupons.coupon.application;

import java.net.InetAddress;
import java.util.Optional;
import org.springframework.stereotype.Service;
import pl.miskiewicz.coupons.coupon.domain.CountryCode;
import pl.miskiewicz.coupons.coupon.domain.CountryUnresolvedException;
import pl.miskiewicz.coupons.coupon.domain.CouponAlreadyUsedByUserException;
import pl.miskiewicz.coupons.coupon.domain.CouponCode;
import pl.miskiewicz.coupons.coupon.domain.CouponCountryNotAllowedException;
import pl.miskiewicz.coupons.coupon.domain.CouponNotFoundException;
import pl.miskiewicz.coupons.coupon.domain.CouponRejectedException;
import pl.miskiewicz.coupons.coupon.domain.CouponRepository;
import pl.miskiewicz.coupons.coupon.domain.CouponSnapshot;
import pl.miskiewicz.coupons.coupon.domain.GeoLocationResolver;
import pl.miskiewicz.coupons.coupon.domain.IdempotencyKey;
import pl.miskiewicz.coupons.coupon.domain.LockNotAcquiredException;
import pl.miskiewicz.coupons.coupon.domain.RedeemAttempt;
import pl.miskiewicz.coupons.coupon.domain.RedemptionCancelledException;
import pl.miskiewicz.coupons.coupon.domain.RedemptionStore;
import pl.miskiewicz.coupons.coupon.domain.UserId;

@Service
public class RedeemCouponService {

    private final CouponRepository coupons;
    private final RedemptionStore redemptions;
    private final GeoLocationResolver geoLocation;
    private final RedemptionBulkhead bulkhead;
    private final RedemptionOutcomes outcomes;

    public RedeemCouponService(
            CouponRepository coupons,
            RedemptionStore redemptions,
            GeoLocationResolver geoLocation,
            RedemptionBulkhead bulkhead,
            RedemptionOutcomes outcomes) {
        this.coupons = coupons;
        this.redemptions = redemptions;
        this.geoLocation = geoLocation;
        this.bulkhead = bulkhead;
        this.outcomes = outcomes;
    }

    public RedemptionResult redeem(RedeemCouponCommand command) {
        try {
            RedemptionResult result = attempt(command);
            outcomes.redeemed(result);
            return result;
        } catch (RuntimeException reason) {
            outcomes.redemptionRejected(reason);
            throw reason;
        }
    }

    private RedemptionResult attempt(RedeemCouponCommand command) {
        UserId userId = UserId.parse(command.userId());
        Optional<IdempotencyKey> key = IdempotencyKey.ofNullable(command.idempotencyKey());
        CouponSnapshot snapshot = coupons.findByCode(CouponCode.parse(command.code()))
                .orElseThrow(CouponNotFoundException::new);
        CouponCode code = snapshot.coupon().code();
        // A replay reports a past fact, so rules that may have changed since must not turn it into an error.
        Optional<RedemptionResult> replay = key.flatMap(k -> replay(code, userId, k));
        if (replay.isPresent()) {
            return replay.get();
        }
        CountryCode country = resolveCountry(command.clientAddress());
        if (!snapshot.coupon().country().equals(country)) {
            throw new CouponCountryNotAllowedException();
        }
        if (redemptions.hasRedeemed(code, userId)) {
            return alreadyRedeemed(code, userId, key);
        }
        snapshot.ensureRedeemable();
        RedeemAttempt attempt = bulkhead.execute(code, () -> redeemAtomically(code, userId, country, key));
        return switch (attempt) {
            case RedeemAttempt.Redeemed redeemed -> new RedemptionResult(redeemed.redemption(), false);
            case RedeemAttempt.AlreadyRedeemed _ -> alreadyRedeemed(code, userId, key);
            case RedeemAttempt.NoUseLeft _ -> throw whyNoUseLeft(code);
        };
    }

    private RedeemAttempt redeemAtomically(CouponCode code, UserId userId, CountryCode country, Optional<IdempotencyKey> key) {
        try {
            return redemptions.redeem(code, userId, country, key.orElse(null));
        } catch (LockNotAcquiredException ex) {
            throw new RedemptionBusyException();
        }
    }

    // A twin request with the same key may have committed after the first lookup.
    private RedemptionResult alreadyRedeemed(CouponCode code, UserId userId, Optional<IdempotencyKey> key) {
        return key.flatMap(k -> replay(code, userId, k)).orElseThrow(CouponAlreadyUsedByUserException::new);
    }

    private Optional<RedemptionResult> replay(CouponCode code, UserId userId, IdempotencyKey key) {
        return redemptions.findByIdempotencyKey(code, userId, key).map(stored -> {
            if (stored.cancelled()) {
                throw new RedemptionCancelledException();
            }
            return new RedemptionResult(stored.redemption(), true);
        });
    }

    // If no rule rejects the re-read coupon, a use came back in the meantime and a retry can succeed.
    private RuntimeException whyNoUseLeft(CouponCode code) {
        CouponSnapshot snapshot = coupons.findByCode(code)
                .orElseThrow(() -> new IllegalStateException("Coupon " + code.value() + " disappeared"));
        Optional<CouponRejectedException> rejection = snapshot.rejection();
        return rejection.isPresent() ? rejection.get() : new RedemptionBusyException();
    }

    private CountryCode resolveCountry(String clientAddress) {
        return parseAddress(clientAddress)
                .flatMap(geoLocation::resolveCountry)
                .orElseThrow(CountryUnresolvedException::new);
    }

    private static Optional<InetAddress> parseAddress(String clientAddress) {
        if (clientAddress == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(InetAddress.ofLiteral(clientAddress));
        } catch (IllegalArgumentException ex) {
            return Optional.empty();
        }
    }
}
