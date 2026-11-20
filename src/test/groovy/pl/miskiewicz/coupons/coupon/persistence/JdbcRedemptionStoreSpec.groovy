package pl.miskiewicz.coupons.coupon.persistence

import java.time.Duration
import spock.lang.Specification

class JdbcRedemptionStoreSpec extends Specification {

    def "refuses a lock timeout of #timeout, which PostgreSQL would read as waiting forever"() {
        when:
        new JdbcRedemptionStore(null, null, timeout)

        then:
        thrown(IllegalArgumentException)

        where:
        timeout << [Duration.ZERO, Duration.ofNanos(999_999), Duration.ofMillis(-1)]
    }
}
