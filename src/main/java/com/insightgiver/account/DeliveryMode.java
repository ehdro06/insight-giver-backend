package com.insightgiver.account;

/** When an account's daily insight arrives, in the account's own time zone. */
public enum DeliveryMode {
    /** The server's morning time (09:00 by default). */
    MORNING,
    /** A time the person picked. */
    FIXED,
    /** A different time inside the server's daytime window every day. */
    SURPRISE
}
