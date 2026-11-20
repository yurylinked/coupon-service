package pl.miskiewicz.coupons

import java.net.http.HttpRequest
import java.net.http.HttpResponse
import org.springframework.beans.factory.annotation.Value

class ObservabilityIT extends IntegrationSpec {

    @Value('${local.management.port}')
    int managementPort

    def "exposes redemption outcomes and request latency histograms to Prometheus"() {
        given: 'the registry is shared by every integration spec, so only the change is asserted'
        createCoupon('METRICS', 1)
        def before = scrape()

        when:
        redeem('METRICS', 'user-1')
        redeem('METRICS', 'user-2')
        def after = scrape()

        then:
        after.statusCode() == 200
        counter(after.body(), 'success') - counter(before.body(), 'success') == 1
        counter(after.body(), 'exhausted') - counter(before.body(), 'exhausted') == 1
        after.body().contains('http_server_requests_seconds_bucket')
        !after.body().contains('METRICS')
    }

    def "keeps actuator endpoints off the public port"() {
        expect:
        get('/actuator/prometheus').status() == 404
    }

    private HttpResponse<String> scrape() {
        http.send(HttpRequest.newBuilder(URI.create("http://localhost:${managementPort}/actuator/prometheus")).GET().build(),
                HttpResponse.BodyHandlers.ofString())
    }

    private static double counter(String scrape, String outcome) {
        def line = scrape.readLines().find { it.startsWith('coupon_redemptions_total{') && it.contains("outcome=\"$outcome\"") }
        line == null ? 0 : line.tokenize(' ').last() as double
    }
}
