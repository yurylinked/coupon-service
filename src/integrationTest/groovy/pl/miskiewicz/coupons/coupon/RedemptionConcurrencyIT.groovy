package pl.miskiewicz.coupons.coupon

import java.time.Instant
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.support.TransactionTemplate
import pl.miskiewicz.coupons.IntegrationSpec
import pl.miskiewicz.coupons.coupon.domain.CouponCode
import pl.miskiewicz.coupons.coupon.domain.CountryCode
import pl.miskiewicz.coupons.coupon.domain.RedemptionStore
import pl.miskiewicz.coupons.coupon.domain.UserId
import pl.miskiewicz.coupons.coupon.persistence.NaiveRedemptionStore

class RedemptionConcurrencyIT extends IntegrationSpec {

    static final CountryCode PL = new CountryCode('PL')
    static final CouponCode HOT = new CouponCode('HOT')

    @Autowired
    RedemptionStore redemptionStore

    @Autowired
    TransactionTemplate transactionTemplate

    def "conditional update grants exactly the limit when 200 users race for 10 uses"() {
        given:
        createCoupon('HOT', 10)

        when:
        def outcomes = race(200) { i -> redemptionStore.redeem(HOT, new UserId("user-$i"), PL, Instant.now()) }

        then:
        outcomes.countBy { it } == [OK: 10, CouponExhaustedException: 190]
        usageRows('HOT') == 10
        currentUses('HOT') == 10
    }

    def "naive check-then-act oversells the same race"() {
        given:
        createCoupon('HOT', 10)
        def naiveStore = new NaiveRedemptionStore(jdbcClient, transactionTemplate)

        when:
        race(200) { i -> naiveStore.redeem(HOT, new UserId("user-$i"), PL, Instant.now()) }

        then:
        usageRows('HOT') > 10
    }

    def "one user firing 50 parallel requests redeems the coupon once"() {
        given:
        createCoupon('HOT', 100)

        when:
        def outcomes = race(50) { i -> redemptionStore.redeem(HOT, new UserId('same-user'), PL, Instant.now()) }

        then:
        outcomes.countBy { it } == [OK: 1, CouponAlreadyUsedByUserException: 49]
        usageRows('HOT') == 1
        currentUses('HOT') == 1
    }

    def "one user double-clicking a single-use coupon is told they already used it"() {
        given:
        createCoupon('HOT', 1)

        when:
        def outcomes = race(50) { i -> redemptionStore.redeem(HOT, new UserId('same-user'), PL, Instant.now()) }

        then:
        outcomes.countBy { it } == [OK: 1, CouponAlreadyUsedByUserException: 49]
        usageRows('HOT') == 1
        currentUses('HOT') == 1
    }
}
