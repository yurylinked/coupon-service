package pl.miskiewicz.coupons.geo

import org.springframework.boot.test.context.runner.ApplicationContextRunner
import pl.miskiewicz.coupons.coupon.domain.GeoLocationResolver
import spock.lang.Specification

class GeoConfigurationSpec extends Specification {

    ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(GeoConfiguration)

    def "uses the DB-IP database by default"() {
        expect:
        contextRunner
                .withPropertyValues('coupons.geo.dbip.database=src/test/resources/geo/test-country.mmdb')
                .run { context ->
                    assert context.getBean(GeoLocationResolver) instanceof DbIpGeoLocationResolver
                }
    }

    def "uses a fixed country when the static provider is selected"() {
        expect:
        contextRunner
                .withPropertyValues('coupons.geo.provider=static', 'coupons.geo.static.country=pl')
                .run { context ->
                    assert context.getBean(GeoLocationResolver) instanceof StaticGeoLocationResolver
                }
    }

    def "refuses to start without the DB-IP database"() {
        expect:
        contextRunner
                .withPropertyValues('coupons.geo.dbip.database=does-not-exist.mmdb')
                .run { context ->
                    assert context.startupFailure != null
                }
    }
}
