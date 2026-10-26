package com.insightgiver.account;

import java.time.DateTimeException;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.UUID;
import java.util.regex.Pattern;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1")
class AccountController {

    record CreateAccountRequest(@NotBlank @Size(max = 64) String timeZone) {
    }

    record CreateAccountResponse(UUID accountId, String token) {
    }

    /**
     * Sent on every app launch. {@code pushToken} is the FCM token on Android and the APNs device
     * token (hex) on iOS, or null while the device has none yet.
     */
    record DeviceRequest(@NotNull Platform platform, @Size(min = 1, max = 4096) String pushToken,
            ApnsEnvironment apnsEnvironment, @NotBlank @Size(max = 64) String timeZone) {
    }

    /** @param time local {@code HH:mm} on a quarter hour; when omitted, the time picked before is kept */
    record DeliveryRequest(@NotNull DeliveryMode mode, @Size(max = 5) String time) {
    }

    record DeliveryResponse(DeliveryMode mode, String time) {

        static DeliveryResponse of(DeliveryPreferences preferences) {
            LocalTime time = preferences.time();
            return new DeliveryResponse(preferences.mode(), time == null ? null : time.format(HH_MM));
        }
    }

    private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");

    /** APNs device tokens are hex, and they end up in the APNs request path. */
    private static final Pattern APNS_TOKEN = Pattern.compile("[0-9a-fA-F]{64,200}");

    private final AccountService accounts;

    AccountController(AccountService accounts) {
        this.accounts = accounts;
    }

    @PostMapping("/accounts")
    @ResponseStatus(HttpStatus.CREATED)
    CreateAccountResponse create(@Valid @RequestBody CreateAccountRequest request) {
        AccountService.Registration registration = accounts.register(parseTimeZone(request.timeZone()));
        return new CreateAccountResponse(registration.accountId(), registration.token());
    }

    @PutMapping("/me/device")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void updateDevice(@AuthenticationPrincipal AuthenticatedAccount account, @Valid @RequestBody DeviceRequest request) {
        if (request.platform() == Platform.IOS && request.pushToken() != null
                && !APNS_TOKEN.matcher(request.pushToken()).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Not an APNs device token");
        }
        accounts.updateDevice(account.id(), request.platform(), request.pushToken(), request.apnsEnvironment(),
                parseTimeZone(request.timeZone()));
    }

    @GetMapping("/me/delivery")
    DeliveryResponse delivery(@AuthenticationPrincipal AuthenticatedAccount account) {
        return DeliveryResponse.of(accounts.deliveryPreferences(account.id()));
    }

    @PutMapping("/me/delivery")
    DeliveryResponse updateDelivery(@AuthenticationPrincipal AuthenticatedAccount account,
            @Valid @RequestBody DeliveryRequest request) {
        LocalTime time;
        if (request.time() != null) {
            time = parseTime(request.time());
            if (!DeliveryPreferences.onGrid(time)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Delivery times fall on multiples of %d minutes".formatted(DeliveryPreferences.MINUTE_STEP));
            }
        } else {
            // Switching modes keeps the time picked before, so going back to FIXED needs none.
            time = accounts.deliveryPreferences(account.id()).time();
        }
        if (request.mode() == DeliveryMode.FIXED && time == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A fixed delivery needs a time");
        }
        DeliveryPreferences preferences = new DeliveryPreferences(request.mode(), time);
        accounts.updateDeliveryPreferences(account.id(), preferences);
        return DeliveryResponse.of(preferences);
    }

    private static LocalTime parseTime(String time) {
        try {
            return LocalTime.parse(time, HH_MM);
        } catch (DateTimeParseException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Not an HH:mm time: " + time);
        }
    }

    private static ZoneId parseTimeZone(String timeZone) {
        try {
            return ZoneId.of(timeZone);
        } catch (DateTimeException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown time zone: " + timeZone);
        }
    }
}
