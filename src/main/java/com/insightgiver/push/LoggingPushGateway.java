package com.insightgiver.push;

import java.util.Map;

import com.insightgiver.account.Device;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class LoggingPushGateway implements PushGateway {

    private static final Logger log = LoggerFactory.getLogger(LoggingPushGateway.class);

    @Override
    public Result send(Device device, Map<String, String> data) {
        log.info("Would push to {} device {}: {}", device.platform(), device.id(), data);
        return Result.SENT;
    }
}
