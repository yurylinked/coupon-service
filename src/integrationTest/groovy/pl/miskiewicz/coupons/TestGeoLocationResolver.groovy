package pl.miskiewicz.coupons

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import pl.miskiewicz.coupons.coupon.domain.CountryCode
import pl.miskiewicz.coupons.coupon.domain.GeoLocationResolver

class TestGeoLocationResolver implements GeoLocationResolver {

    volatile Optional<CountryCode> country = Optional.empty()
    volatile InetAddress lastLookup
    volatile Pause pause

    @Override
    Optional<CountryCode> resolveCountry(InetAddress address) {
        lastLookup = address
        def current = pause
        if (current != null && current.address == address) {
            current.hold()
        }
        country
    }

    // Lets a test stop one request inside the geo lookup, which runs before the redemption transaction, and run other
    // requests meanwhile: an interleaving that is otherwise only reachable through an unlucky pause.
    Pause pauseLookupsFrom(String address) {
        pause = new Pause(InetAddress.ofLiteral(address))
    }

    static class Pause {

        final InetAddress address
        private final CountDownLatch entered = new CountDownLatch(1)
        private final CountDownLatch released = new CountDownLatch(1)

        Pause(InetAddress address) {
            this.address = address
        }

        void awaitEntered() {
            assert entered.await(10, TimeUnit.SECONDS)
        }

        void release() {
            released.countDown()
        }

        private void hold() {
            entered.countDown()
            assert released.await(10, TimeUnit.SECONDS)
        }
    }
}
