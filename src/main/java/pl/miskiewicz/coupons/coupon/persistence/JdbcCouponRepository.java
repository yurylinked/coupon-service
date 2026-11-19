package pl.miskiewicz.coupons.coupon.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import pl.miskiewicz.coupons.coupon.domain.Coupon;
import pl.miskiewicz.coupons.coupon.domain.CouponCode;
import pl.miskiewicz.coupons.coupon.domain.CouponRepository;
import pl.miskiewicz.coupons.coupon.domain.CountryCode;

@Repository
class JdbcCouponRepository implements CouponRepository {

    private final JdbcClient jdbc;

    JdbcCouponRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean insertIfAbsent(Coupon coupon) {
        return jdbc.sql("""
                        INSERT INTO coupon (code, country_code, max_uses, current_uses, created_at)
                        VALUES (:code, :country, :maxUses, :currentUses, :createdAt)
                        ON CONFLICT (code) DO NOTHING
                        """)
                .param("code", coupon.code().value())
                .param("country", coupon.country().value())
                .param("maxUses", coupon.maxUses())
                .param("currentUses", coupon.currentUses())
                .param("createdAt", OffsetDateTime.ofInstant(coupon.createdAt(), ZoneOffset.UTC))
                .update() == 1;
    }

    @Override
    public Optional<Coupon> findByCode(CouponCode code) {
        return jdbc.sql("""
                        SELECT code, country_code, max_uses, current_uses, created_at
                        FROM coupon
                        WHERE code = :code
                        """)
                .param("code", code.value())
                .query(JdbcCouponRepository::mapCoupon)
                .optional();
    }

    private static Coupon mapCoupon(ResultSet rs, int rowNum) throws SQLException {
        return new Coupon(
                new CouponCode(rs.getString("code")),
                new CountryCode(rs.getString("country_code")),
                rs.getInt("max_uses"),
                rs.getInt("current_uses"),
                rs.getObject("created_at", OffsetDateTime.class).toInstant());
    }
}
