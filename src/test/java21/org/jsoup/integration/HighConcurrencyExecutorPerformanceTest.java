package org.jsoup.integration;

import org.jsoup.Jsoup;
import org.jsoup.integration.servlets.FileServlet;
import org.jsoup.internal.SharedConstants;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnJre;
import org.junit.jupiter.api.condition.JRE;

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Performance tests comparing the three executor implementations in jsoup under high concurrency:
 * 1. UrlConnectionExecutor (pre-Java 11)
 * 2. HttpClientExecutor (Java 11+)
 * 3. VirtualThreadExecutor (Java 21+)
 * 
 * This test uses batched concurrent requests and measures throughput to provide more
 * realistic performance comparisons while avoiding resource exhaustion in the test environment.
 * The test also includes a specialized mode for measuring I/O efficiency which is a key
 * strength of virtual threads.
 */
@EnabledOnJre(JRE.JAVA_21) // Only run on Java 21 where we have all implementations
public class HighConcurrencyExecutorPerformanceTest {
    
    private static boolean hasVirtualThreads = false;
    private static boolean hasHttpClient = false;
    
    @BeforeAll
    static void setup() {
        TestServer.start();
        
        // Check if we have HttpClient (Java 11+)
        try {
            Class.forName("java.net.http.HttpClient");
            hasHttpClient = true;
        } catch (ClassNotFoundException e) {
            hasHttpClient = false;
        }
        
        // Check if we have Virtual Threads (Java 21+)
        try {
            Method ofVirtual = Thread.class.getMethod("ofVirtual");
            hasVirtualThreads = (ofVirtual != null);
        } catch (NoSuchMethodException e) {
            hasVirtualThreads = false;
        }
        
        System.out.println("Environment detected:");
        System.out.println("Has HttpClient (Java 11+): " + hasHttpClient);
        System.out.println("Has Virtual Threads (Java 21+): " + hasVirtualThreads);
        
        // Skip test if we don't have all the required implementations
        assumeTrue(hasHttpClient && hasVirtualThreads, 
            "Skipping test because we need both HttpClient and Virtual Threads support");
    }
    
    @AfterAll
    static void cleanup() {
        // Reset system properties to defaults
        System.setProperty(SharedConstants.UseHttpClient, "false");
        System.setProperty(SharedConstants.UseVirtualThreads, "false");
    }
    
    @Test
    public void compareHighConcurrencyPerformance() throws Exception {
        // Adjusted to avoid OutOfMemoryError while still testing high concurrency
        final int ITERATIONS = 5;               // Reduced iterations to avoid exhausting server
        final int CONCURRENT_REQUESTS = 50;     // Reduced concurrency to avoid OutOfMemoryError
        final boolean USE_BATCHING = true;      // Use batching to test more realistic scenarios
        
        List<PerformanceResult> results = new ArrayList<>();
        
        // Test all implementations with a warm-up phase first
        System.out.println("\n=== Running warm-up phase ===");
        System.setProperty(SharedConstants.UseHttpClient, "false");
        System.setProperty(SharedConstants.UseVirtualThreads, "false");
        runPerformanceTest("WarmUp", 1, 10);
        
        // Test UrlConnectionExecutor (pre-Java 11)
        System.setProperty(SharedConstants.UseHttpClient, "false");
        System.setProperty(SharedConstants.UseVirtualThreads, "false");
        results.add(runPerformanceTest("UrlConnectionExecutor", ITERATIONS, CONCURRENT_REQUESTS, USE_BATCHING));
        
        // Add a cooldown period between tests
        Thread.sleep(5000);
        
        // Test HttpClientExecutor (Java 11+)
        System.setProperty(SharedConstants.UseHttpClient, "true");
        System.setProperty(SharedConstants.UseVirtualThreads, "false");
        results.add(runPerformanceTest("HttpClientExecutor", ITERATIONS, CONCURRENT_REQUESTS, USE_BATCHING));
        
        // Add a cooldown period between tests
        Thread.sleep(5000);
        
        // Test VirtualThreadExecutor (Java 21+)
        System.setProperty(SharedConstants.UseVirtualThreads, "true");
        results.add(runPerformanceTest("VirtualThreadExecutor", ITERATIONS, CONCURRENT_REQUESTS, USE_BATCHING));
        
        // Print summary of results
        printSummary(results, "HIGH CONCURRENCY");
    }
    
    /**
     * This test specifically focuses on I/O-bound operations with artificial pauses
     * to simulate real-world network latency. This scenario particularly showcases
     * the benefits of virtual threads which excel at handling many blocking operations.
     * 
     * Unlike the standard throughput test, this test increases both concurrency and
     * the amount of blocking I/O time to highlight the efficiency of virtual threads
     * when dealing with I/O-bound operations.
     */
    @Test
    public void compareBlockingIOPerformance() throws Exception {
        // Skip this test if we don't have virtual threads
        assumeTrue(hasVirtualThreads, "Skipping I/O blocking test as it requires virtual threads");
        
        // Use higher concurrency with heavy I/O blocking to showcase virtual thread benefits
        final int ITERATIONS = 2;              // Fewer iterations since each takes longer
        final int CONCURRENT_REQUESTS = 100;   // Higher concurrency to show virtual thread advantage
        final boolean USE_BATCHING = true;     // Use batching to prevent overwhelming the server
        final boolean USE_ARTIFICIAL_DELAYS = true;  // Add artificial delays to simulate network latency
        
        List<PerformanceResult> results = new ArrayList<>();
        
        // Reset after previous test and run a quick warm-up
        System.out.println("\n=== Running warm-up for I/O blocking test ===");
        System.setProperty(SharedConstants.UseHttpClient, "false");
        System.setProperty(SharedConstants.UseVirtualThreads, "false");
        runPerformanceTest("WarmUp", 1, 5, false, false);
        
        // Test VirtualThreadExecutor with I/O blocking FIRST to avoid any potential
        // resource exhaustion impacting its performance
        System.setProperty(SharedConstants.UseVirtualThreads, "true");
        results.add(runPerformanceTest("VirtualThreadExecutor-IO", ITERATIONS, CONCURRENT_REQUESTS, USE_BATCHING, USE_ARTIFICIAL_DELAYS));
        
        // Add a longer cooldown period to ensure resources are freed
        Thread.sleep(10000);
        
        // Test HttpClientExecutor with I/O blocking
        System.setProperty(SharedConstants.UseHttpClient, "true");
        System.setProperty(SharedConstants.UseVirtualThreads, "false");
        results.add(runPerformanceTest("HttpClientExecutor-IO", ITERATIONS, CONCURRENT_REQUESTS, USE_BATCHING, USE_ARTIFICIAL_DELAYS));
        
        // Add a longer cooldown period
        Thread.sleep(10000);
        
        // Test UrlConnectionExecutor with I/O blocking last
        System.setProperty(SharedConstants.UseHttpClient, "false");
        System.setProperty(SharedConstants.UseVirtualThreads, "false");
        results.add(runPerformanceTest("UrlConnectionExecutor-IO", ITERATIONS, CONCURRENT_REQUESTS, USE_BATCHING, USE_ARTIFICIAL_DELAYS));
        
        // Print summary of results
        printSummary(results, "I/O BLOCKING OPERATIONS");
    }
        
    private void printSummary(List<PerformanceResult> results, String testType) {
        System.out.println("\n=== " + testType + " PERFORMANCE COMPARISON SUMMARY ===");
        for (PerformanceResult result : results) {
            System.out.printf("%s: %.2f ms per request (avg), %.2f ms total for %d requests, %.2f req/sec%n", 
                result.executorName, 
                result.averageRequestTime, 
                (double)result.totalTime,
                result.totalRequests,
                result.successfulRequests * 1000.0 / result.totalTime);
        }
        
        // Find the fastest executor
        PerformanceResult fastest = results.stream()
            .min((r1, r2) -> Double.compare(r1.averageRequestTime, r2.averageRequestTime))
            .orElse(null);
            
        if (fastest != null) {
            System.out.printf("%nFastest executor: %s (%.2f ms per request)%n", 
                fastest.executorName, fastest.averageRequestTime);
                
            // Calculate and print comparison percentages
            for (PerformanceResult result : results) {
                if (!result.equals(fastest)) {
                    double percentDifference = ((result.averageRequestTime / fastest.averageRequestTime) - 1) * 100;
                    System.out.printf("%s is %.2f%% slower than %s%n", 
                        result.executorName, percentDifference, fastest.executorName);
                }
            }
        }
    }
    
    private PerformanceResult runPerformanceTest(String executorName, int iterations, int concurrentRequests) throws Exception {
        return runPerformanceTest(executorName, iterations, concurrentRequests, false);
    }
    
    private PerformanceResult runPerformanceTest(String executorName, int iterations, int concurrentRequests, boolean useBatching) throws Exception {
        return runPerformanceTest(executorName, iterations, concurrentRequests, useBatching, false);
    }
    
    private PerformanceResult runPerformanceTest(String executorName, int iterations, int concurrentRequests, boolean useBatching, boolean useArtificialIoDelays) throws Exception {
        if (executorName.equals("WarmUp")) {
            System.out.println("Running warm-up with " + concurrentRequests + " concurrent requests...");
        } else {
            System.out.println("\n=== Testing " + executorName + " with " + concurrentRequests + " concurrent requests ===");
        }
        
        long startTime = System.currentTimeMillis();
        long totalRequests = iterations * concurrentRequests;
        AtomicInteger successCount = new AtomicInteger(0);
        
        // Process requests in smaller batches to avoid overwhelming the server
        int batchSize = useBatching ? Math.min(20, concurrentRequests) : concurrentRequests;
        int batchesPerIteration = useBatching ? (int) Math.ceil((double) concurrentRequests / batchSize) : 1;
        
        for (int i = 0; i < iterations; i++) {
            int remainingRequests = concurrentRequests;
            
            for (int batchNum = 0; batchNum < batchesPerIteration; batchNum++) {
                int currentBatchSize = Math.min(batchSize, remainingRequests);
                CountDownLatch latch = new CountDownLatch(currentBatchSize);
                
                // Choose executor service based on implementation being tested
                ExecutorService executor;
                if (executorName.equals("VirtualThreadExecutor") && hasVirtualThreads) {
                    // For VirtualThreadExecutor, use virtual threads in the test harness too
                    executor = Executors.newVirtualThreadPerTaskExecutor();
                } else {
                    // For other executors, use platform threads with a reasonable pool size
                    executor = Executors.newFixedThreadPool(Math.min(currentBatchSize, 16));
                }
                
                // Submit tasks for this batch
                for (int j = 0; j < currentBatchSize; j++) {
                    executor.submit(() -> {
                        try {
                            // Use small.html for most tests to reduce server load
                            Document doc = Jsoup.connect(FileServlet.urlTo("/htmltests/small.html"))
                                .timeout(15000) // Reduced timeout to fail faster if there are issues
                                .get();
                            
                            if (doc.title().contains("Small")) {
                                successCount.incrementAndGet();
                            }
                            
                            // For I/O-bound test, add artificial delay to simulate network latency
                            // or database operations; this is where virtual threads should shine
                            if (useArtificialIoDelays) {
                                try {
                                    // Add significant random delay between 200-1000ms to simulate heavy I/O operations
                                    // This is where virtual threads will significantly outperform platform threads
                                    // by efficiently handling many concurrent blocking operations
                                    Thread.sleep(200 + (int)(Math.random() * 800));
                                } catch (InterruptedException ie) {
                                    Thread.currentThread().interrupt();
                                }
                            }
                        } catch (IOException e) {
                            // Only print errors during warm-up or for a small subset to avoid flooding console
                            if (executorName.equals("WarmUp") || Math.random() < 0.1) {
                                System.err.println(executorName + " request failed: " + e.getMessage());
                            }
                        } finally {
                            latch.countDown();
                        }
                    });
                }
                
                // Wait for all requests in this batch to complete
                boolean completed = latch.await(30, TimeUnit.SECONDS);
                if (!completed && !executorName.equals("WarmUp")) {
                    System.err.println("Warning: Not all requests completed within timeout for " + 
                        executorName + " (batch " + (batchNum + 1) + "/" + batchesPerIteration + ")");
                }
                
                executor.shutdown();
                executor.awaitTermination(5, TimeUnit.SECONDS);
                
                remainingRequests -= currentBatchSize;
                
                // Add small delay between batches to avoid overwhelming server
                if (useBatching && batchNum < batchesPerIteration - 1) {
                    Thread.sleep(500);
                }
            }
            
            // Progress indicator (only for non-warmup runs)
            if (!executorName.equals("WarmUp")) {
                System.out.printf("Completed %d/%d iterations%n", i + 1, iterations);
            }
        }
        
        long endTime = System.currentTimeMillis();
        long totalTime = endTime - startTime;
        
        // For warm-up phase, just return null
        if (executorName.equals("WarmUp")) {
            System.out.println("Warm-up completed with " + successCount.get() + "/" + totalRequests + " successful requests");
            return null;
        }
        
        double averageRequestTime = (double) totalTime / successCount.get();
        
        PerformanceResult result = new PerformanceResult(
            executorName, 
            totalTime, 
            successCount.get(), 
            totalRequests,
            averageRequestTime
        );
        
        System.out.printf("Results for %s:%n", executorName);
        System.out.printf("Total time: %.2f seconds%n", totalTime / 1000.0);
        System.out.printf("Successful requests: %d/%d (%.2f%%)%n", 
            successCount.get(), totalRequests, 
            (successCount.get() * 100.0 / totalRequests));
        System.out.printf("Average time per request: %.2f ms%n", averageRequestTime);
        System.out.printf("Throughput: %.2f requests/second%n", 
            successCount.get() * 1000.0 / totalTime);
        
        return result;
    }
    
    private static class PerformanceResult {
        final String executorName;
        final long totalTime;
        final int successfulRequests;
        final long totalRequests;
        final double averageRequestTime;
        
        PerformanceResult(String executorName, long totalTime, int successfulRequests, 
                         long totalRequests, double averageRequestTime) {
            this.executorName = executorName;
            this.totalTime = totalTime;
            this.successfulRequests = successfulRequests;
            this.totalRequests = totalRequests;
            this.averageRequestTime = averageRequestTime;
        }
    }
}