package pl.miskiewicz.coupons.geo

import java.nio.file.Path
import pl.miskiewicz.coupons.coupon.domain.CountryCode
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification

class DbIpGeoLocationResolverSpec extends Specification {

    static final Path TEST_DATABASE = Path.of('src/test/resources/geo/test-country.mmdb')

    @Shared
    @AutoCleanup
    DbIpGeoLocationResolver resolver = new DbIpGeoLocationResolver(TEST_DATABASE)

    def "resolves #address to #expected"() {
        expect:
        resolver.resolveCountry(InetAddress.ofLiteral(address)) == Optional.of(new CountryCode(expected))

        where:
        address         || expected
        '203.0.113.7'   || 'PL'
        '198.51.100.1'  || 'DE'
        '2001:db8::1'   || 'PL'
    }

    def "leaves the country unresolved for #description"() {
        expect:
        resolver.resolveCountry(InetAddress.ofLiteral(address)).isEmpty()

        where:
        description                          | address
        'an address missing from the data'   | '8.8.8.8'
        'a private address'                  | '10.0.0.1'
        'loopback'                           | '127.0.0.1'
        'IPv6 loopback'                      | '::1'
        'a record without a country'         | '192.0.2.1'
        'a code outside ISO 3166-1'          | '233.252.0.9'
    }

    def "fails fast when the database file is missing"() {
        when:
        new DbIpGeoLocationResolver(Path.of('does-not-exist.mmdb'))

        then:
        thrown(IOException)
    }
}
