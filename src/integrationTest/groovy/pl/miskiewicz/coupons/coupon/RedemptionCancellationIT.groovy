package pl.miskiewicz.coupons.coupon

import java.sql.SQLException
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import org.springframework.beans.factory.annotation.Autowired
import pl.miskiewicz.coupons.IntegrationSpec
import pl.miskiewicz.coupons.coupon.domain.CouponCode
import pl.miskiewicz.coupons.coupon.domain.CountryCode
import pl.miskiewicz.coupons.coupon.domain.RedeemAttempt
import pl.miskiewicz.coupons.coupon.domain.RedemptionId
import pl.miskiewicz.coupons.coupon.domain.RedemptionStore
import pl.miskiewicz.coupons.coupon.domain.UserId

class RedemptionCancellationIT extends IntegrationSpec {

    static final CountryCode PL = new CountryCode('PL')

    @Autowired
    RedemptionStore redemptionStore

    def "a redemption can be read back, cancelled, and the user can then redeem again"() {
        given:
        createCoupon('BOOK', 1)
        def first = redeem('BOOK', 'user-1')
        String id = first.body().redemptionId

        when:
        def cancelled = cancel(id)
        def afterCancel = get("/api/v1/redemptions/${id}")
        def again = redeem('BOOK', 'user-1')

        then:
        first.header('Location') == "/api/v1/redemptions/${id}"
        cancelled.status() == 204
        afterCancel.status() == 200
        afterCancel.body().cancelledAt != null
        Instant.parse(afterCancel.body().cancelledAt) >= Instant.parse(afterCancel.body().redeemedAt)
        again.status() == 201
        again.body().redemptionId != id
        currentUses('BOOK') == 1
        activeRedemptionRows('BOOK') == 1
        redemptionRows('BOOK') == 2
    }

    def "a repeated cancellation changes nothing and still answers 204"() {
        given:
        createCoupon('BOOK', 5)
        String id = redeem('BOOK', 'user-1').body().redemptionId

        when:
        def first = cancel(id)
        def second = cancel(id)

        then:
        first.status() == 204
        second.status() == 204
        currentUses('BOOK') == 0
        activeRedemptionRows('BOOK') == 0
    }

    def "answers #status #errorCode for #description"() {
        when:
        def response = cancel(redemptionId)

        then:
        response.status() == status
        response.body().errorCode == errorCode

        where:
        description           | redemptionId                 || status | errorCode
        'an unknown id'       | UUID.randomUUID().toString() || 404    | 'REDEMPTION_NOT_FOUND'
        'something not an id' | 'not-a-uuid'                 || 400    | 'INVALID_REDEMPTION_ID'
    }

    def "reads and cancels only the redemption the id names"() {
        given:
        createCoupon('BOOK', 5)
        String mine = redeem('BOOK', 'user-1').body().redemptionId
        String theirs = redeem('BOOK', 'user-2').body().redemptionId

        when:
        def read = get("/api/v1/redemptions/${theirs}")
        cancel(mine)

        then:
        read.body().redemptionId == theirs
        read.body().userId == 'user-2'
        get("/api/v1/redemptions/${theirs}").body().cancelledAt == null
        get("/api/v1/redemptions/${mine}").body().cancelledAt != null
        currentUses('BOOK') == 1
    }

    def "does not give back a use of an expired coupon"() {
        given: 'a second, unexpired coupon, so a cancel that lost its join to the coupon would find a match'
        createCoupon('OTHER', 5)
        postJson('/api/v1/coupons', [code: 'BOOK', maxUses: 5, country: 'PL', expiresAt: '2999-07-01T00:00:00Z'])
        String id = redeem('BOOK', 'user-1').body().redemptionId
        expireNow('BOOK')

        when:
        def response = cancel(id)

        then:
        response.status() == 409
        response.body().errorCode == 'COUPON_EXPIRED'
        currentUses('BOOK') == 1
        activeRedemptionRows('BOOK') == 1
    }

    def "a use returned to an exhausted coupon goes to the next customer"() {
        given:
        createCoupon('BOOK', 1)
        String id = redeem('BOOK', 'user-1').body().redemptionId
        def refused = redeem('BOOK', 'user-2')

        when:
        def cancelled = cancel(id)
        def granted = redeem('BOOK', 'user-2')

        then:
        refused.body().errorCode == 'COUPON_EXHAUSTED'
        cancelled.status() == 204
        granted.status() == 201
        currentUses('BOOK') == 1
    }

    def "a use of a suspended coupon can be given back while the coupon stays suspended"() {
        given:
        createCoupon('BOOK', 5)
        String id = redeem('BOOK', 'user-1').body().redemptionId
        setActive('BOOK', false)

        when:
        def cancelled = cancel(id)
        def refused = redeem('BOOK', 'user-1')

        then:
        cancelled.status() == 204
        currentUses('BOOK') == 0
        refused.body().errorCode == 'COUPON_SUSPENDED'
    }

    def "twenty parallel cancellations of one redemption give the use back exactly once"() {
        given:
        createCoupon('BOOK', 5)
        def redeemed = redemptionStore.redeem(new CouponCode('BOOK'), new UserId('user-1'), PL, null) as RedeemAttempt.Redeemed

        when:
        def outcomes = race(20) { i -> redemptionStore.cancel(redeemed.redemption().id()) ? 'cancelled' : 'unchanged' }

        then:
        outcomes.countBy { it } == [cancelled: 1, unchanged: 19]
        currentUses('BOOK') == 0
        activeRedemptionRows('BOOK') == 0
    }

    def "cancelling a use while #description redeems the coupon keeps the counter exact"() {
        given:
        def codes = (1..20).collect { "RACE-$it" as String }
        def redemptionIds = codes.collect { code ->
            createCoupon(code, maxUses)
            (redemptionStore.redeem(new CouponCode(code), new UserId('user-1'), PL, null) as RedeemAttempt.Redeemed).redemption().id()
        }

        when:
        def outcomes = codes.indices.collectMany { int index ->
            race(2) { i ->
                if (i == 1) {
                    return redemptionStore.cancel(redemptionIds[index]) ? 'cancelled' : 'unchanged'
                }
                redemptionStore.redeem(new CouponCode(codes[index]), new UserId(rival), PL, null)
            }
        }

        then:
        outcomes.every { it in allowed }
        outcomes.count { it == 'cancelled' } == codes.size()
        codes.every { currentUses(it) == activeRedemptionRows(it) }

        where:
        description     | rival    | maxUses || allowed
        'another user'  | 'user-2' | 1       || ['cancelled', 'Redeemed', 'NoUseLeft']
        'the same user' | 'user-1' | 2       || ['cancelled', 'Redeemed', 'AlreadyRedeemed']
    }

    // The same user's re-redeem inserts against the very row a cancel updates, so a cancel that locked that row
    // before the coupon would deadlock with it. Both orders block on the coupon here; only the right one leaves
    // the redemption row free while it waits.
    def "a cancel takes the coupon lock before it touches the redemption row"() {
        given:
        createCoupon('BOOK', 5)
        def redeemed = redemptionStore.redeem(new CouponCode('BOOK'), new UserId('user-1'), PL, null) as RedeemAttempt.Redeemed
        def holder = sideConnection()
        holder.autoCommit = false
        holder.createStatement().execute("SELECT id FROM coupon WHERE code = 'BOOK' FOR UPDATE")

        when:
        def cancelling = CompletableFuture.supplyAsync { redemptionStore.cancel(redeemed.redemption().id()) }
        awaitTransactionsWaitingOnLock(1)
        def redemptionRowFree = redemptionRowLockable(redeemed.redemption().id())
        holder.rollback()

        then:
        redemptionRowFree
        cancelling.get(10, TimeUnit.SECONDS)
        currentUses('BOOK') == 0

        cleanup:
        holder.rollback()
        holder.close()
    }

    private boolean redemptionRowLockable(RedemptionId redemptionId) {
        def probe = sideConnection()
        try {
            probe.autoCommit = false
            def statement = probe.prepareStatement('SELECT id FROM redemption WHERE public_id = ? FOR UPDATE NOWAIT')
            statement.setObject(1, redemptionId.value())
            statement.executeQuery()
            true
        } catch (SQLException ex) {
            if (ex.SQLState != '55P03') {
                throw ex
            }
            false
        } finally {
            probe.rollback()
            probe.close()
        }
    }
}
