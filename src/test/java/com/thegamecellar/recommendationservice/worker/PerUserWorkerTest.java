package com.thegamecellar.recommendationservice.worker;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PerUserWorkerTest {

    @Mock private UserComputeProcessor processor;

    private ExecutorService executor;
    private PerUserWorker worker;

    @BeforeEach
    void setUp() {
        executor = Executors.newFixedThreadPool(PerUserWorker.BATCH_SIZE);
        worker = new PerUserWorker(processor, executor);
    }

    @Test
    void runBatch_submitsBatchSizeTasks() {
        when(processor.processOneAtomicallyOrSkip()).thenReturn(false);

        worker.runBatch();

        verify(processor, times(PerUserWorker.BATCH_SIZE)).processOneAtomicallyOrSkip();
    }

    @Test
    void runBatch_taskExceptionDoesNotKillBatch() {
        when(processor.processOneAtomicallyOrSkip())
                .thenThrow(new RuntimeException("task failure"))
                .thenReturn(true)
                .thenReturn(false);

        worker.runBatch();

        verify(processor, times(PerUserWorker.BATCH_SIZE)).processOneAtomicallyOrSkip();
    }
}
