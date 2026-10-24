package com.insightgiver.account;

import java.util.UUID;

/** Security principal for requests carrying a valid device token. */
public record AuthenticatedAccount(UUID id) {
}
