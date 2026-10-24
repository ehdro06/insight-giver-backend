package com.insightgiver.insight;

import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.random.RandomGenerator;

import com.insightgiver.account.AccountRepository;
import com.insightgiver.account.Device;
import com.insightgiver.account.DeviceRepository;
import com.insightgiver.embedding.EmbeddingClient;
import com.insightgiver.push.PushGateway;
import com.insightgiver.support.Text;
import com.insightgiver.support.Times;

import org.springframework.stereotype.Service;

@Service
public class InsightService {

    /** How many of the newest entries may cue recall. */
    private static final int SEED_POOL = 5;

    record Recollection(Memory recent, MemoryRepository.Recalled recalled) {
    }

    private final AccountRepository accounts;
    private final DeviceRepository devices;
    private final MemoryRepository memories;
    private final InsightRepository insights;
    private final EmbeddingClient embeddings;
    private final InsightWriter writer;
    private final TeaserPolicy teasers;
    private final PushGateway push;
    private final InsightProperties properties;
    private final RandomGenerator random;
    private final Clock clock;

    InsightService(AccountRepository accounts, DeviceRepository devices, MemoryRepository memories,
            InsightRepository insights, EmbeddingClient embeddings, InsightWriter writer, TeaserPolicy teasers,
            PushGateway push, InsightProperties properties, RandomGenerator insightRandom, Clock clock) {
        this.accounts = accounts;
        this.devices = devices;
        this.memories = memories;
        this.insights = insights;
        this.embeddings = embeddings;
        this.writer = writer;
        this.teasers = teasers;
        this.push = push;
        this.properties = properties;
        this.random = insightRandom;
        this.clock = clock;
    }

    /**
     * Writes, stores and pushes today's insight for one account. Empty when the diary has nothing
     * to connect yet, or another run already took today's slot.
     */
    public Optional<Insight> deliverDailyInsight(AccountRepository.InsightCandidate account, OffsetDateTime now) {
        Optional<Recollection> recollection = recall(account.id(), now);
        if (recollection.isEmpty()) {
            return Optional.empty();
        }
        // Claimed before the (paid, slow) writer runs so that parallel runs never both write.
        if (!accounts.claimInsightSlot(account.id(), account.lastInsightAt(), now)) {
            return Optional.empty();
        }
        Insight insight;
        try {
            insight = create(account, recollection.get(), now);
        } catch (RuntimeException e) {
            accounts.releaseInsightSlot(account.id(), now, account.lastInsightAt());
            throw e;
        }
        // A failed push keeps the insight: the app shows the latest one whenever it is opened.
        push(insight);
        return Optional.of(insight);
    }

    public Optional<Insight> findLatest(UUID accountId) {
        return insights.findLatest(accountId);
    }

    /**
     * Picks a recent entry as the cue, blurs it and samples a related older memory (see
     * {@link FuzzyRecall}). Seeds are tried in random order because some have nothing older to
     * connect to, e.g. the first entry of a new diary.
     */
    Optional<Recollection> recall(UUID accountId, OffsetDateTime now) {
        String model = embeddings.model();
        if (memories.countEmbedded(accountId, model) < properties.minEntries()) {
            return Optional.empty();
        }
        List<MemoryRepository.Cue> latest = memories.findLatest(accountId, model, SEED_POOL);
        OffsetDateTime freshSince = now.minus(properties.seedWindow());
        List<MemoryRepository.Cue> seeds = new ArrayList<>(
                latest.stream().filter(cue -> cue.memory().writtenAt().isAfter(freshSince)).toList());
        Collections.shuffle(seeds, random);
        // A diary that went quiet still gets insights, cued by its newest entries.
        latest.stream().filter(cue -> !seeds.contains(cue)).forEach(seeds::add);

        for (MemoryRepository.Cue seed : seeds) {
            float[] cue = FuzzyRecall.blur(seed.embedding(), properties.blur(), random);
            OffsetDateTime seedWrittenAt = seed.memory().writtenAt();
            List<MemoryRepository.Recalled> candidates = memories.findNearest(accountId, model, cue,
                    seedWrittenAt.minus(properties.memoryGap()), properties.candidates());
            if (candidates.isEmpty()) {
                // Young diaries have nothing older than the gap yet; anything before the seed will do.
                candidates = memories.findNearest(accountId, model, cue, seedWrittenAt, properties.candidates());
            }
            if (!candidates.isEmpty()) {
                MemoryRepository.Recalled recalled = FuzzyRecall.pick(candidates, MemoryRepository.Recalled::similarity,
                        properties.temperature(), random);
                return Optional.of(new Recollection(seed.memory(), recalled));
            }
        }
        return Optional.empty();
    }

    private Insight create(AccountRepository.InsightCandidate account, Recollection recollection, OffsetDateTime now) {
        LocalDate today = now.atZoneSameInstant(account.timeZone()).toLocalDate();
        String written = writer.write(new InsightWriter.Request(recollection.recent(), recollection.recalled().memory(),
                account.timeZone(), today));
        String body = Text.clip(written.strip(), properties.maxLength());
        String preview = teasers.preview(body, account.lastSeenAt(), now, random);
        Insight insight = new Insight(UUID.randomUUID(), account.id(), body, preview, now);
        insights.insert(insight, recollection.recent().id(), recollection.recalled().memory().id(),
                recollection.recalled().similarity(), writer.name());
        return insight;
    }

    private void push(Insight insight) {
        Map<String, String> payload = InsightPayload.of(insight);
        boolean delivered = false;
        for (Device device : devices.findByAccount(insight.accountId())) {
            switch (push.send(device, payload)) {
                case SENT -> delivered = true;
                case INVALID_TOKEN -> devices.delete(device.id());
                case FAILED -> {
                }
            }
        }
        if (delivered) {
            insights.markPushed(insight.id(), Times.now(clock));
        }
    }
}
