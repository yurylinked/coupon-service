package pl.miskiewicz.coupons.coupon

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
        def outcomes = race(200) { i -> redemptionStore.redeem(HOT, new UserId("user-$i"), PL, null) }

        then:
        outcomes.countBy { it } == [Redeemed: 10, NoUseLeft: 190]
        usageRows('HOT') == 10
        currentUses('HOT') == 10
    }

    def "naive check-then-act oversells the same race"() {
        given:
        createCoupon('HOT', 10)
        def naiveStore = new NaiveRedemptionStore(jdbcClient, transactionTemplate)

        when:
        race(200) { i -> naiveStore.redeem(HOT, new UserId("user-$i"), PL, null) }

        then:
        usageRows('HOT') > 10
    }

    def "one user firing 50 parallel requests at a coupon with #maxUses uses redeems it once"() {
        given:
        createCoupon('HOT', maxUses)

        when:
        def outcomes = race(50) { i -> redemptionStore.redeem(HOT, new UserId('same-user'), PL, null) }

        then:
        outcomes.countBy { it } == [Redeemed: 1, AlreadyRedeemed: 49]
        usageRows('HOT') == 1
        currentUses('HOT') == 1

        where:
        maxUses << [100, 1]
    }

    def "over HTTP, 50 customers racing for 10 uses get exactly 10 grants, shed requests included after their retry"() {
        given:
        createCoupon('HOT', 10)

        when:
        def first = race(50) { i -> "${i}:${redeem('HOT', "user-$i").status()}".toString() }
        def shed = first.findAll { it.endsWith(':429') }.collect { it.tokenize(':')[0] }
        def retried = shed.collect { i -> redeem('HOT', "user-$i").status() as String }
        def statuses = first.collect { it.tokenize(':')[1] }.findAll { it != '429' } + retried

        then:
        first.every { it.tokenize(':')[1] in ['201', '409', '429'] }
        statuses.size() == 50
        statuses.count { it == '201' } == 10
        statuses.count { it == '409' } == 40
        currentUses('HOT') == 10
        usageRows('HOT') == 10
    }
}
