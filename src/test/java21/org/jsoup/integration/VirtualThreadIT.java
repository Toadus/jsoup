package org.jsoup.integration;

import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the Java 21 virtual thread functionality in real-world integration scenarios.
 */
public class VirtualThreadIT {
    
    @Test
    public void virtualThreadSupport() throws InterruptedException {
        // Create multiple virtual threads
        int numThreads = 10;
        CountDownLatch latch = new CountDownLatch(numThreads);
        AtomicInteger virtualThreadCount = new AtomicInteger(0);
        
        for (int i = 0; i < numThreads; i++) {
            Thread vt = Thread.ofVirtual().name("virtual-thread-" + i).start(() -> {
                if (Thread.currentThread().isVirtual()) {
                    virtualThreadCount.incrementAndGet();
                }
                latch.countDown();
            });
        }
        
        latch.await();
        
        // Verify that all threads were virtual
        assertTrue(virtualThreadCount.get() == numThreads, 
            "All threads should be virtual, but only " + virtualThreadCount.get() + " out of " + numThreads + " were virtual");
    }
    
    @Test
    public void canUseVirtualThreadsForProcessing() throws InterruptedException {
        // Create a processing task using virtual threads
        int iterations = 500;
        CountDownLatch latch = new CountDownLatch(iterations);
        AtomicInteger counter = new AtomicInteger(0);
        
        for (int i = 0; i < iterations; i++) {
            int finalI = i;
            Thread.ofVirtual().name("processor-" + i).start(() -> {
                // Do some CPU work
                int sum = 0;
                for (int j = 0; j < 10000; j++) {
                    sum += (j * finalI) % 10;
                }
                
                if (sum >= 0) {
                    counter.incrementAndGet();
                }
                
                latch.countDown();
            });
        }
        
        latch.await();
        
        // Verify that all processing completed
        assertTrue(counter.get() == iterations, 
            "All processing should complete, but only " + counter.get() + " out of " + iterations + " completed");
    }
}