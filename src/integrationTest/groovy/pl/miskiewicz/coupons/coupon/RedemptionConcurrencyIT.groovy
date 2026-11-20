package pl.miskiewicz.coupons.coupon

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.support.TransactionTemplate
import pl.miskiewicz.coupons.ApiResponse
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
        redemptionRows('HOT') == 10
        currentUses('HOT') == 10
    }

    def "naive check-then-act oversells the same race"() {
        given:
        createCoupon('HOT', 10)
        def naiveStore = new NaiveRedemptionStore(jdbcClient, transactionTemplate)

        when:
        race(200) { i -> naiveStore.redeem(HOT, new UserId("user-$i"), PL, null) }

        then:
        redemptionRows('HOT') > 10
    }

    def "one user firing 50 parallel requests at a coupon with #maxUses uses redeems it once"() {
        given:
        createCoupon('HOT', maxUses)

        when:
        def outcomes = race(50) { i -> redemptionStore.redeem(HOT, new UserId('same-user'), PL, null) }

        then:
        outcomes.countBy { it } == [Redeemed: 1, AlreadyRedeemed: 49]
        redemptionRows('HOT') == 1
        currentUses('HOT') == 1

        where:
        maxUses << [100, 1]
    }

    def "over HTTP, 50 customers racing for 10 uses get exactly 10 grants, shed requests included after their retry"() {
        given:
        createCoupon('HOT', 10)

        when:
        def first = race(50) { i -> answer(redeem('HOT', "user-$i".toString()), i) }
        def retried = first.findAll { it.endsWith(' 429') }
                .collect { it.tokenize(' ').first() }
                .collect { i -> answer(redeem('HOT', "user-$i".toString()), i) }
        def finalAnswers = first.findAll { !it.endsWith(' 429') } + retried

        then:
        first.every { it.tokenize(' ').last() in ['201', '409', '429'] }
        finalAnswers.count { it.endsWith(' 201') } == 10
        finalAnswers.findAll { !it.endsWith(' 201') }.every { it.endsWith('COUPON_EXHAUSTED 409') }
        finalAnswers.size() == 50
        currentUses('HOT') == 10
        redemptionRows('HOT') == 10
    }

    private static String answer(ApiResponse response, Object user) {
        "$user ${response.body()?.errorCode ?: ''} ${response.status()}".toString().replaceAll(/ +/, ' ')
    }
}
