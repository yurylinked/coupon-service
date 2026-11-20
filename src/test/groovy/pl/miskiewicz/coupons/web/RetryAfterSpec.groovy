package pl.miskiewicz.coupons.web

import java.time.Duration
import spock.lang.Specification

class RetryAfterSpec extends Specification {

    def "spreads delays over every whole second of the window and nothing outside it"() {
        given:
        def retryAfter = new RetryAfter(Duration.ofSeconds(1), Duration.ofSeconds(3))

        when:
        def drawn = (1..1_000).collect { retryAfter.seconds() } as Set

        then:
        drawn == ['1', '2', '3'] as Set
    }

    def "a window of one second always gives that second"() {
        expect:
        new RetryAfter(Duration.ofSeconds(2), Duration.ofSeconds(2)).seconds() == '2'
    }

    def "rejects a window from #min to #max"() {
        when:
        new RetryAfter(min, max)

        then:
        thrown(IllegalArgumentException)

        where:
        min                    | max
        Duration.ZERO          | Duration.ofSeconds(3)
        Duration.ofSeconds(3)  | Duration.ofSeconds(1)
    }
}
