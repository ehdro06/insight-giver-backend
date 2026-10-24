package com.insightgiver.account;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;

import com.insightgiver.support.Times;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AccountService {

    /** Coarse enough that authenticated requests rarely write; fine enough for engagement checks. */
    private static final Duration LAST_SEEN_GRANULARITY = Duration.ofMinutes(5);

    public record Registration(UUID accountId, String token) {
    }

    private final AccountRepository accounts;
    private final DeviceRepository devices;
    private final Clock clock;

    AccountService(AccountRepository accounts, DeviceRepository devices, Clock clock) {
        this.accounts = accounts;
        this.devices = devices;
        this.clock = clock;
    }

    public Registration register(ZoneId timeZone) {
        UUID id = UUID.randomUUID();
        String token = ApiTokens.generate();
        accounts.create(id, ApiTokens.hash(token), timeZone, Times.now(clock));
        return new Registration(id, token);
    }

    /** Resolves a bearer token and records the activity used to decide on widget teasers. */
    Optional<UUID> authenticate(String token) {
        Optional<UUID> id = accounts.findIdByTokenHash(ApiTokens.hash(token));
        id.ifPresent(accountId -> {
            OffsetDateTime now = Times.now(clock);
            accounts.touchLastSeen(accountId, now, now.minus(LAST_SEEN_GRANULARITY));
        });
        return id;
    }

    @Transactional
    public void updateDevice(UUID accountId, Platform platform, String pushToken, ZoneId timeZone) {
        accounts.updateTimeZone(accountId, timeZone);
        if (pushToken != null) {
            devices.replacePushToken(accountId, platform, pushToken, Times.now(clock));
        }
    }
}
