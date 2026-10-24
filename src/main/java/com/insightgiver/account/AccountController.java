package com.insightgiver.account;

import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.UUID;
import java.util.regex.Pattern;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
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

    private static ZoneId parseTimeZone(String timeZone) {
        try {
            return ZoneId.of(timeZone);
        } catch (DateTimeException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown time zone: " + timeZone);
        }
    }
}
