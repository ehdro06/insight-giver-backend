package com.insightgiver.account;

import java.util.UUID;

/** @param apnsEnvironment set for iOS devices only */
public record Device(UUID id, UUID accountId, Platform platform, String pushToken, ApnsEnvironment apnsEnvironment) {
}
