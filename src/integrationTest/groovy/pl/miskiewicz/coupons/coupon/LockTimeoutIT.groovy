package pl.miskiewicz.coupons.coupon

import javax.sql.DataSource
import org.springframework.beans.factory.annotation.Autowired
import pl.miskiewicz.coupons.IntegrationSpec

class LockTimeoutIT extends IntegrationSpec {

    @Autowired
    DataSource dataSource

    def "answers 503 with Retry-After while the coupon row is locked and succeeds once it is released"() {
        given:
        createCoupon('LOCKED', 5)
        def lockHolder = dataSource.connection
        lockHolder.autoCommit = false
        lockHolder.createStatement().execute("SELECT id FROM coupon WHERE code = 'LOCKED' FOR UPDATE")

        when:
        long started = System.nanoTime()
        def busy = postJson('/api/v1/redemptions', [code: 'LOCKED', userId: 'user-1'])
        long waitedMillis = (System.nanoTime() - started).intdiv(1_000_000)
        lockHolder.rollback()
        def retried = postJson('/api/v1/redemptions', [code: 'LOCKED', userId: 'user-1'])

        then:
        busy.status() == 503
        busy.header('Retry-After') in ['1', '2', '3']
        busy.body().errorCode == 'REDEMPTION_BUSY'
        waitedMillis >= 1_900 && waitedMillis < 10_000
        retried.status() == 201
        currentUses('LOCKED') == 1

        cleanup:
        lockHolder?.close()
    }
}
