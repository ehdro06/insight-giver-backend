package com.insightgiver.insight;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.insightgiver.account.AuthenticatedAccount;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/insights")
class InsightController {

    record InsightResponse(UUID id, String body, OffsetDateTime createdAt) {
    }

    private final InsightService insights;

    InsightController(InsightService insights) {
        this.insights = insights;
    }

    /** What the app shows when opened, including after a push that never arrived. 204 before the first insight. */
    @GetMapping("/latest")
    ResponseEntity<InsightResponse> latest(@AuthenticationPrincipal AuthenticatedAccount account) {
        return insights.findLatest(account.id())
                .map(insight -> ResponseEntity.ok(new InsightResponse(insight.id(), insight.body(), insight.createdAt())))
                .orElseGet(() -> ResponseEntity.noContent().build());
    }
}
