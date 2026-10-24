package com.insightgiver.account;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class DeviceRepository {

    private final JdbcClient jdbc;

    DeviceRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Accounts are bound to one app install, so its current push token replaces any older one.
     * A token that moved here from another account (reinstall) is taken over.
     */
    void replacePushToken(UUID accountId, Platform platform, String pushToken, OffsetDateTime now) {
        jdbc.sql("DELETE FROM devices WHERE account_id = :accountId AND push_token <> :pushToken")
                .param("accountId", accountId)
                .param("pushToken", pushToken)
                .update();
        jdbc.sql("""
                INSERT INTO devices (id, account_id, platform, push_token, updated_at)
                VALUES (:id, :accountId, :platform, :pushToken, :now)
                ON CONFLICT (push_token) DO UPDATE
                SET account_id = EXCLUDED.account_id, platform = EXCLUDED.platform, updated_at = EXCLUDED.updated_at
                """)
                .param("id", UUID.randomUUID())
                .param("accountId", accountId)
                .param("platform", platform.name())
                .param("pushToken", pushToken)
                .param("now", now)
                .update();
    }

    public List<Device> findByAccount(UUID accountId) {
        return jdbc.sql("SELECT id, account_id, platform, push_token FROM devices WHERE account_id = :accountId")
                .param("accountId", accountId)
                .query((rs, row) -> new Device(
                        rs.getObject("id", UUID.class),
                        rs.getObject("account_id", UUID.class),
                        Platform.valueOf(rs.getString("platform")),
                        rs.getString("push_token")))
                .list();
    }

    public void delete(UUID deviceId) {
        jdbc.sql("DELETE FROM devices WHERE id = :id").param("id", deviceId).update();
    }
}
