package pl.miskiewicz.coupons.web

import java.util.random.RandomGenerator
import spock.lang.Specification

class RetryAfterSpec extends Specification {

    def "draws the delay from the whole window, both ends included"() {
        given:
        def random = Mock(RandomGenerator)
        def retryAfter = new RetryAfter({ random })

        when:
        def seconds = retryAfter.seconds()

        then:
        1 * random.nextInt(1, 4) >> drawn
        seconds == expected

        where:
        drawn || expected
        1     || '1'
        3     || '3'
    }

    def "spreads real retries across every second of the window"() {
        given:
        def retryAfter = new RetryAfter()

        when:
        def drawn = (1..1_000).collect { retryAfter.seconds() } as Set

        then:
        drawn == ['1', '2', '3'] as Set
    }
}
