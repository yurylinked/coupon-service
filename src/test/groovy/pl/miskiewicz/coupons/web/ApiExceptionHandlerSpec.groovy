package pl.miskiewicz.coupons.web

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get

import java.sql.SQLException
import java.util.random.RandomGenerator
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.CannotGetJdbcConnectionException
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.transaction.CannotCreateTransactionException
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController
import spock.lang.Specification

class ApiExceptionHandlerSpec extends Specification {

    RandomGenerator random = Stub {
        nextInt(_, _) >> 2
    }

    MockMvc mvc = MockMvcBuilders.standaloneSetup(new FailingController())
            .setControllerAdvice(new ApiExceptionHandler(new RetryAfter({ random })))
            .build()

    def "maps #failure to #status #errorCode"() {
        when:
        def response = mvc.perform(get("/fail/$failure")).andReturn().response

        then:
        response.status == status
        response.contentType == 'application/problem+json'
        response.contentAsString.contains("\"errorCode\":\"$errorCode\"")
        response.getHeader('Retry-After') == retryAfter
        !response.contentAsString.contains('secret')

        where:
        failure            || status | errorCode             | retryAfter
        'pool-exhausted'   || 503    | 'SERVICE_UNAVAILABLE' | '2'
        'no-transaction'   || 503    | 'SERVICE_UNAVAILABLE' | '2'
        'connection-lost'  || 503    | 'SERVICE_UNAVAILABLE' | '2'
        'constraint'       || 500    | 'INTERNAL_ERROR'      | null
        'bug'              || 500    | 'INTERNAL_ERROR'      | null
    }

    @RestController
    static class FailingController {

        @GetMapping('/fail/{failure}')
        String fail(@PathVariable('failure') String failure) {
            if (failure == 'pool-exhausted') {
                throw new CannotGetJdbcConnectionException('secret pool detail')
            }
            if (failure == 'no-transaction') {
                throw new CannotCreateTransactionException('secret tx detail')
            }
            if (failure == 'connection-lost') {
                throw new DataAccessResourceFailureException('secret', new SQLException('secret connection detail', '08006'))
            }
            if (failure == 'constraint') {
                throw new DataIntegrityViolationException('secret constraint detail')
            }
            throw new IllegalStateException('secret bug detail')
        }
    }
}
