package pl.miskiewicz.coupons.coupon.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
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
                        INSERT INTO coupon (code, country_code, max_uses, current_uses, created_at, valid_from, expires_at, active)
                        VALUES (:code, :country, :maxUses, :currentUses, :createdAt, :validFrom, :expiresAt, :active)
                        ON CONFLICT (code) DO NOTHING
                        """)
                .param("code", coupon.code().value())
                .param("country", coupon.country().value())
                .param("maxUses", coupon.maxUses())
                .param("currentUses", coupon.currentUses())
                .param("createdAt", toTimestamp(coupon.createdAt()))
                .param("validFrom", coupon.validityStart().map(JdbcCouponRepository::toTimestamp).orElse(null))
                .param("expiresAt", coupon.expiry().map(JdbcCouponRepository::toTimestamp).orElse(null))
                .param("active", coupon.active())
                .update() == 1;
    }

    @Override
    public Optional<Coupon> findByCode(CouponCode code) {
        return jdbc.sql("""
                        SELECT code, country_code, max_uses, current_uses, created_at, valid_from, expires_at, active
                        FROM coupon
                        WHERE code = :code
                        """)
                .param("code", code.value())
                .query(JdbcCouponRepository::mapCoupon)
                .optional();
    }

    @Override
    public Optional<Coupon> updateActive(CouponCode code, boolean active) {
        return jdbc.sql("""
                        UPDATE coupon
                        SET active = :active
                        WHERE code = :code
                        RETURNING code, country_code, max_uses, current_uses, created_at, valid_from, expires_at, active
                        """)
                .param("active", active)
                .param("code", code.value())
                .query(JdbcCouponRepository::mapCoupon)
                .optional();
    }

    private static OffsetDateTime toTimestamp(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static Instant toInstant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static Coupon mapCoupon(ResultSet rs, int rowNum) throws SQLException {
        return new Coupon(
                new CouponCode(rs.getString("code")),
                new CountryCode(rs.getString("country_code")),
                rs.getInt("max_uses"),
                rs.getInt("current_uses"),
                toInstant(rs, "created_at"),
                toInstant(rs, "valid_from"),
                toInstant(rs, "expires_at"),
                rs.getBoolean("active"));
    }
}
