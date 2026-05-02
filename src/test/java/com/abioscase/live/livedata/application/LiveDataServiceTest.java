package com.abioscase.live.livedata.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.abioscase.live.livedata.cache.InMemoryLiveSnapshotCache;
import com.abioscase.live.livedata.cache.LiveSnapshot;
import com.abioscase.live.config.LiveDataProperties;
import com.abioscase.live.integration.abios.AbiosSnapshotReader;
import com.abioscase.live.integration.abios.model.AbiosEnrichedDocument;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class LiveDataServiceTest {

    @Mock
    private AbiosSnapshotReader snapshotReader;

    @Mock
    private LiveSnapshotAggregator aggregator;

    private InMemoryLiveSnapshotCache cache;
    private LiveDataProperties props;
    private LiveDataService service;

    @BeforeEach
    void setUp() {
        cache = new InMemoryLiveSnapshotCache();
        props = new LiveDataProperties();
        props.setCacheTtlSeconds(10);
        props.setMaxStaleTtlSeconds(300);
        props.setBackgroundRefreshEnabled(true);
        service = new LiveDataService(snapshotReader, aggregator, cache, props, new SimpleMeterRegistry(), Optional.empty(), Runnable::run);
        // Async stale-refresh calls aggregate(loadRawSeries()); mocks default to null — stub a safe empty snapshot.
        lenient().when(aggregator.aggregate(any())).thenAnswer(inv -> snapshotAt(Instant.now(), false, false));
    }

    @Test
    void returnsFreshSnapshotFromCacheWithoutUpstreamCall() {
        LiveSnapshot fresh = snapshotAt(Instant.now().minusSeconds(1), false, false);
        cache.put(fresh);

        LiveSnapshot result = service.getSnapshot();

        assertThat(result.stale()).isFalse();
        assertThat(result.fetchedAt()).isEqualTo(fresh.fetchedAt());
        verify(snapshotReader, never()).loadRawSeries();
    }

    @Test
    void cacheMissLoadsAndStoresSnapshot() {
        AbiosEnrichedDocument raw = new AbiosEnrichedDocument();
        LiveSnapshot loaded = snapshotAt(Instant.now(), false, false);
        when(snapshotReader.loadRawSeries()).thenReturn(raw);
        when(aggregator.aggregate(raw)).thenReturn(loaded);

        LiveSnapshot result = service.getSnapshot();

        assertThat(result).isEqualTo(loaded);
        assertThat(cache.get()).contains(loaded);
        verify(snapshotReader).loadRawSeries();
        verify(aggregator).aggregate(raw);
    }

    @Test
    void expiredWithinMaxStaleReturnsStaleImmediately() {
        LiveSnapshot expiredButUsable = snapshotAt(Instant.now().minusSeconds(20), false, false);
        cache.put(expiredButUsable);

        LiveSnapshot result = service.getSnapshot();

        assertThat(result.stale()).isTrue();
        assertThat(result.fetchedAt()).isEqualTo(expiredButUsable.fetchedAt());
    }

    @Test
    void throwsWhenNoDataAndUpstreamFails() {
        when(snapshotReader.loadRawSeries()).thenThrow(new RuntimeException("upstream down"));

        assertThatThrownBy(() -> service.getSnapshot())
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("upstream down");
    }

    private static LiveSnapshot snapshotAt(Instant at, boolean stale, boolean degraded) {
        return new LiveSnapshot(at, stale, degraded, null, List.of(), List.of(), List.of(), Map.of());
    }
}
