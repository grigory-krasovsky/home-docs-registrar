package com.example.homedocsregistrar.telegram;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Buffers the pages of a Telegram album (media group) — they arrive as separate updates with a shared
 * {@code media_group_id} and no "end of album" signal. Each new page (re)arms a short debounce timer;
 * once it fires (no more pages for a moment) the collected pages, ordered by message id, are handed to
 * the callback as one document. Single photos (no media group) don't go through here.
 */
@Component
public class MediaGroupCollector {

    private static final Logger log = LoggerFactory.getLogger(MediaGroupCollector.class);

    private final long debounceMs;
    private final Map<String, Group> groups = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "media-group-flush");
        thread.setDaemon(true);
        return thread;
    });

    public MediaGroupCollector(@Value("${telegram.media-group-debounce-ms:1500}") long debounceMs) {
        this.debounceMs = debounceMs;
    }

    /**
     * Add a page to its media group and (re)arm the flush timer. Returns true if this was the first page
     * of the group, so the caller can post a single "receiving pages…" acknowledgement.
     */
    public boolean add(String mediaGroupId, BufferedPage page, Consumer<List<BufferedPage>> onComplete) {
        Group group = groups.computeIfAbsent(mediaGroupId, key -> new Group());
        synchronized (group) {
            boolean first = group.pages.isEmpty();
            group.pages.add(page);
            group.onComplete = onComplete;
            if (group.flushTask != null) {
                group.flushTask.cancel(false);
            }
            group.flushTask = scheduler.schedule(() -> flush(mediaGroupId), debounceMs, TimeUnit.MILLISECONDS);
            return first;
        }
    }

    private void flush(String mediaGroupId) {
        Group group = groups.remove(mediaGroupId);
        if (group == null) {
            return;
        }
        List<BufferedPage> pages;
        Consumer<List<BufferedPage>> onComplete;
        synchronized (group) {
            pages = new ArrayList<>(group.pages);
            onComplete = group.onComplete;
        }
        pages.sort(Comparator.comparingInt(BufferedPage::messageId));
        try {
            onComplete.accept(pages);
        } catch (RuntimeException e) {
            log.error("Failed to process media group {}", mediaGroupId, e);
        }
    }

    @PreDestroy
    void shutdown() {
        scheduler.shutdownNow();
    }

    private static final class Group {
        private final List<BufferedPage> pages = new ArrayList<>();
        private Consumer<List<BufferedPage>> onComplete;
        private ScheduledFuture<?> flushTask;
    }

    /** One buffered album page: the chat it came from, its Telegram file, and message id (for ordering). */
    public record BufferedPage(long chatId, String fileId, String fileName, int messageId) {
    }
}
