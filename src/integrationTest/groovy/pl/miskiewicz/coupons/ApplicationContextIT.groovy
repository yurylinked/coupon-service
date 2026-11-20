package pl.miskiewicz.coupons

class ApplicationContextIT extends IntegrationSpec {

    def "application runs against PostgreSQL 18 with the schema migrated"() {
        when:
        def versionNum = jdbcClient.sql("SELECT current_setting('server_version_num')::int")
                .query(Integer)
                .single()
        def tables = jdbcClient.sql("SELECT table_name FROM information_schema.tables WHERE table_schema = 'public' AND table_name <> 'flyway_schema_history' ORDER BY table_name")
                .query(String)
                .list()

        then:
        versionNum >= 180000
        tables == ['coupon', 'redemption']
    }
}
