package pl.miskiewicz.coupons

import java.net.http.HttpRequest
import java.net.http.HttpResponse
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics

@AutoConfigureMetrics
class ObservabilityIT extends IntegrationSpec {

    @Value('${local.management.port}')
    int managementPort

    def "exposes redemption outcomes and request latency histograms to Prometheus"() {
        given:
        createCoupon('METRICS', 1)
        redeem('METRICS', 'user-1')
        redeem('METRICS', 'user-2')

        when:
        def scrape = http.send(
                HttpRequest.newBuilder(URI.create("http://localhost:${managementPort}/actuator/prometheus")).GET().build(),
                HttpResponse.BodyHandlers.ofString())

        then:
        scrape.statusCode() == 200
        counterLine(scrape.body(), 'success').endsWith(' 1.0')
        counterLine(scrape.body(), 'exhausted').endsWith(' 1.0')
        scrape.body().contains('http_server_requests_seconds_bucket')
        !scrape.body().contains('METRICS')
    }

    def "keeps actuator endpoints off the public port"() {
        expect:
        get('/actuator/prometheus').status() == 404
    }

    private static String counterLine(String scrape, String outcome) {
        scrape.readLines().find { it.startsWith('coupon_redemptions_total{') && it.contains("outcome=\"$outcome\"") }
    }
}
