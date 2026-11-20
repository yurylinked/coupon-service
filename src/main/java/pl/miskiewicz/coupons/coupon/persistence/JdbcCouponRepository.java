package pl.miskiewicz.coupons.coupon.persistence;

import static pl.miskiewicz.coupons.coupon.persistence.Timestamps.instant;
import static pl.miskiewicz.coupons.coupon.persistence.Timestamps.utc;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import pl.miskiewicz.coupons.coupon.domain.Coupon;
import pl.miskiewicz.coupons.coupon.domain.CouponCode;
import pl.miskiewicz.coupons.coupon.domain.CouponRepository;
import pl.miskiewicz.coupons.coupon.domain.CouponSnapshot;
import pl.miskiewicz.coupons.coupon.domain.CountryCode;

@Repository
class JdbcCouponRepository implements CouponRepository {

    private static final String COLUMNS = "code, country_code, max_uses, current_uses, created_at, valid_from, expires_at, active";

    private final JdbcClient jdbc;

    JdbcCouponRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Instant currentTime() {
        return jdbc.sql("SELECT statement_timestamp()").query((rs, rowNum) -> instant(rs, 1)).single();
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
                .param("createdAt", utc(coupon.createdAt()))
                .param("validFrom", utc(coupon.validFrom()))
                .param("expiresAt", utc(coupon.expiresAt()))
                .param("active", coupon.active())
                .update() == 1;
    }

    @Override
    public Optional<CouponSnapshot> findByCode(CouponCode code) {
        return jdbc.sql("SELECT " + COLUMNS + ", statement_timestamp() AS observed_at FROM coupon WHERE code = :code")
                .param("code", code.value())
                .query((rs, rowNum) -> new CouponSnapshot(mapCoupon(rs), instant(rs, "observed_at")))
                .optional();
    }

    @Override
    public Optional<Coupon> setActive(CouponCode code, boolean active) {
        return jdbc.sql("UPDATE coupon SET active = :active WHERE code = :code RETURNING " + COLUMNS)
                .param("active", active)
                .param("code", code.value())
                .query((rs, rowNum) -> mapCoupon(rs))
                .optional();
    }

    private static Coupon mapCoupon(ResultSet rs) throws SQLException {
        return new Coupon(
                new CouponCode(rs.getString("code")),
                new CountryCode(rs.getString("country_code")),
                rs.getInt("max_uses"),
                rs.getInt("current_uses"),
                instant(rs, "created_at"),
                instant(rs, "valid_from"),
                instant(rs, "expires_at"),
                rs.getBoolean("active"));
    }
}
