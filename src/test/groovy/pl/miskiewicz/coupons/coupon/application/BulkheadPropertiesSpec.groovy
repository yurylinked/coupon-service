package pl.miskiewicz.coupons.coupon.application

import java.time.Duration
import spock.lang.Specification

class BulkheadPropertiesSpec extends Specification {

    def "rejects permits=#permits, stripes=#stripes, wait=#wait at startup"() {
        when:
        new BulkheadProperties(permits, stripes, wait)

        then:
        thrown(exception)

        where:
        permits | stripes | wait                   || exception
        0       | 64      | Duration.ofMillis(200) || IllegalArgumentException
        4       | 0       | Duration.ofMillis(200) || IllegalArgumentException
        4       | 64      | Duration.ofMillis(-1)  || IllegalArgumentException
        4       | 64      | null                   || NullPointerException
    }

    def "accepts a wait of zero, which refuses at once"() {
        expect:
        new BulkheadProperties(1, 1, Duration.ZERO).maxWait() == Duration.ZERO
    }
}
