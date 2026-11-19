package pl.miskiewicz.coupons.coupon.domain;

import java.net.InetAddress;
import java.util.Optional;

public interface GeoLocationResolver {

    Optional<CountryCode> resolveCountry(InetAddress address);
}
