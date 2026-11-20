package pl.miskiewicz.coupons.coupon.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

final class Timestamps {

    private Timestamps() {
    }

    static OffsetDateTime utc(Instant instant) {
        return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    static Instant instant(ResultSet rs, String column) throws SQLException {
        return toInstant(rs.getObject(column, OffsetDateTime.class));
    }

    static Instant instant(ResultSet rs, int column) throws SQLException {
        return toInstant(rs.getObject(column, OffsetDateTime.class));
    }

    private static Instant toInstant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }
}
