package com.example.homedocsregistrar.telegram;

import com.example.homedocsregistrar.telegram.MediaGroupCollector.BufferedPage;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

class MediaGroupCollectorTest {

    @Test
    void collectsAlbumPagesOrderedByMessageIdAfterDebounce() throws InterruptedException {
        MediaGroupCollector collector = new MediaGroupCollector(200);
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<List<BufferedPage>> received = new AtomicReference<>();
        Consumer<List<BufferedPage>> onComplete = pages -> {
            received.set(pages);
            done.countDown();
        };

        // Pages arrive out of order; only the first should report "first of group".
        boolean first = collector.add("group-1", new BufferedPage(42, "F3", "3.jpg", 30), onComplete);
        boolean second = collector.add("group-1", new BufferedPage(42, "F1", "1.jpg", 10), onComplete);
        collector.add("group-1", new BufferedPage(42, "F2", "2.jpg", 20), onComplete);

        assertThat(first).isTrue();
        assertThat(second).isFalse();
        assertThat(done.await(3, TimeUnit.SECONDS)).isTrue();
        assertThat(received.get())
                .extracting(BufferedPage::fileId)
                .containsExactly("F1", "F2", "F3");

        collector.shutdown();
    }
}
