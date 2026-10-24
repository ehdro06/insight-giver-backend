package com.insightgiver.push;

import java.util.Map;

import com.insightgiver.account.Device;

/** Sends silent (data-only) pushes; the app handles them natively, without showing a notification. */
public interface PushGateway {

    enum Result {
        SENT,
        /** The token no longer reaches an install of the app and should be forgotten. */
        INVALID_TOKEN,
        FAILED
    }

    Result send(Device device, Map<String, String> data);
}
