package pl.miskiewicz.coupons

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import org.springframework.jdbc.core.simple.JdbcClient
import pl.miskiewicz.coupons.coupon.domain.CountryCode
import spock.lang.Specification

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import([PostgresTestcontainer, TestGeoConfiguration])
@ActiveProfiles('test')
abstract class IntegrationSpec extends Specification {

    @Value('${local.server.port}')
    int port

    @Autowired
    JdbcClient jdbcClient

    @Autowired
    TestGeoLocationResolver geo

    final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()

    def setup() {
        jdbcClient.sql('TRUNCATE coupon_usage, coupon RESTART IDENTITY').update()
        requestsComeFrom('PL')
    }

    void requestsComeFrom(String country) {
        geo.country = Optional.ofNullable(country).map { new CountryCode(it) }
    }

    void createCoupon(String code, int maxUses) {
        createCoupon(code, maxUses, 'PL')
    }

    void createCoupon(String code, int maxUses, String country) {
        def response = postJson('/api/v1/coupons', [code: code, maxUses: maxUses, country: country])
        assert response.status() == 201
    }

    int usageRows(String code) {
        jdbcClient.sql('SELECT count(*) FROM coupon_usage u JOIN coupon c ON c.id = u.coupon_id WHERE c.code = :code')
                .param('code', code)
                .query(Integer)
                .single()
    }

    int currentUses(String code) {
        jdbcClient.sql('SELECT current_uses FROM coupon WHERE code = :code')
                .param('code', code)
                .query(Integer)
                .single()
    }

    ApiResponse postJson(String path, Map body) {
        postRaw(path, JsonOutput.toJson(body))
    }

    ApiResponse postRaw(String path, String body) {
        postRaw(path, body, [:])
    }

    ApiResponse postJson(String path, Map body, Map<String, String> headers) {
        postRaw(path, JsonOutput.toJson(body), headers)
    }

    ApiResponse postRaw(String path, String body, Map<String, String> headers) {
        def request = HttpRequest.newBuilder(uri(path))
                .header('Content-Type', 'application/json')
                .POST(HttpRequest.BodyPublishers.ofString(body))
        headers.each { name, value -> request.header(name, value) }
        send(request.build())
    }

    ApiResponse request(String method, String path, String body, String contentType) {
        def builder = HttpRequest.newBuilder(uri(path))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body))
        if (contentType != null) {
            builder.header('Content-Type', contentType)
        }
        send(builder.build())
    }

    ApiResponse get(String path) {
        send(HttpRequest.newBuilder(uri(path)).GET().build())
    }

    // Starts every action at the same moment and reports each outcome as OK or the simple name of what it threw.
    static List<String> race(int requests, Closure<?> redemption) {
        def start = new CountDownLatch(1)
        def executor = Executors.newVirtualThreadPerTaskExecutor()
        try {
            def futures = (1..requests).collect { int i ->
                executor.submit({
                    start.await()
                    redemption.call(i)
                    'OK'
                } as Callable<String>)
            }
            start.countDown()
            futures.collect { future ->
                try {
                    future.get()
                } catch (ExecutionException ex) {
                    ex.cause.class.simpleName
                }
            }
        } finally {
            executor.close()
        }
    }

    private URI uri(String path) {
        URI.create("http://localhost:${port}${path}")
    }

    private ApiResponse send(HttpRequest request) {
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString())
        def body = response.body() ? new JsonSlurper().parseText(response.body()) : null
        new ApiResponse(response.statusCode(), response.headers().map(), body)
    }
}
