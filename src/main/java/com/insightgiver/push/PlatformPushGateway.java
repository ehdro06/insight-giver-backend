package com.insightgiver.push;

import java.util.Map;

import com.insightgiver.account.Device;

/** Routes each push to its device's platform: FCM for Android, APNs for iOS. */
final class PlatformPushGateway implements PushGateway, AutoCloseable {

    private final PushGateway android;
    private final PushGateway ios;

    PlatformPushGateway(PushGateway android, PushGateway ios) {
        this.android = android;
        this.ios = ios;
    }

    @Override
    public Result send(Device device, Map<String, String> data) {
        return switch (device.platform()) {
            case ANDROID -> android.send(device, data);
            case IOS -> ios.send(device, data);
        };
    }

    @Override
    public void close() throws Exception {
        if (android instanceof AutoCloseable closeable) {
            closeable.close();
        }
        if (ios instanceof AutoCloseable closeable) {
            closeable.close();
        }
    }
}
