package pl.miskiewicz.coupons

import pl.miskiewicz.coupons.coupon.domain.CountryCode
import pl.miskiewicz.coupons.coupon.domain.GeoLocationResolver

class TestGeoLocationResolver implements GeoLocationResolver {

    volatile Optional<CountryCode> country = Optional.empty()

    @Override
    Optional<CountryCode> resolveCountry(InetAddress address) {
        country
    }
}
