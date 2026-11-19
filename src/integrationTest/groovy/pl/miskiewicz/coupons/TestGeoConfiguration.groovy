package pl.miskiewicz.coupons

import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean

@TestConfiguration(proxyBeanMethods = false)
class TestGeoConfiguration {

    @Bean
    TestGeoLocationResolver testGeoLocationResolver() {
        new TestGeoLocationResolver()
    }
}
