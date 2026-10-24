package com.insightgiver.account;

import java.util.UUID;

public record Device(UUID id, UUID accountId, Platform platform, String pushToken) {
}
