package pl.miskiewicz.coupons

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import spock.lang.Specification

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PostgresTestcontainer)
abstract class IntegrationSpec extends Specification {

    @Value('${local.server.port}')
    int port

    @Autowired
    JdbcClient jdbcClient

    final HttpClient http = HttpClient.newHttpClient()

    def setup() {
        jdbcClient.sql('TRUNCATE coupon_usage, coupon RESTART IDENTITY').update()
    }

    ApiResponse postJson(String path, Map body) {
        postRaw(path, JsonOutput.toJson(body))
    }

    ApiResponse postRaw(String path, String body) {
        send(HttpRequest.newBuilder(uri(path))
                .header('Content-Type', 'application/json')
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build())
    }

    ApiResponse get(String path) {
        send(HttpRequest.newBuilder(uri(path)).GET().build())
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
