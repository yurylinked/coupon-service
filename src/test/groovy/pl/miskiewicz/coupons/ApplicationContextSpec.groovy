package pl.miskiewicz.coupons

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import spock.lang.Specification

@SpringBootTest
@Import(PostgresTestcontainer)
class ApplicationContextSpec extends Specification {

    @Autowired
    JdbcClient jdbcClient

    def "application context starts against PostgreSQL 18"() {
        when:
        def versionNum = jdbcClient.sql("SELECT current_setting('server_version_num')::int")
                .query(Integer)
                .single()

        then:
        versionNum >= 180000
    }
}
