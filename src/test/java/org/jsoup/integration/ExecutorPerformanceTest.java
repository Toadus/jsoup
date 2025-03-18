package org.jsoup.integration;

import org.jsoup.Jsoup;
import org.jsoup.integration.servlets.FileServlet;
import org.jsoup.internal.SharedConstants;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Performance tests comparing the three executor implementations in jsoup:
 * 1. UrlConnectionExecutor (pre-Java 11)
 * 2. HttpClientExecutor (Java 11+)
 * 3. VirtualThreadExecutor (Java 21+)
 */
public class ExecutorPerformanceTest {
    
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
    }
    
    @AfterAll
    static void cleanup() {
        // Reset system properties to defaults
        System.setProperty(SharedConstants.UseHttpClient, "false");
        System.setProperty(SharedConstants.UseVirtualThreads, "false");
    }
    
    @Test
    public void compareExecutorPerformance() throws Exception {
        final int ITERATIONS = 30;          // Number of tests per executor
        final int CONCURRENT_REQUESTS = 20; // Number of concurrent requests per test
        
        List<PerformanceResult> results = new ArrayList<>();
        
        // Test UrlConnectionExecutor (pre-Java 11)
        System.setProperty(SharedConstants.UseHttpClient, "false");
        System.setProperty(SharedConstants.UseVirtualThreads, "false");
        results.add(runPerformanceTest("UrlConnectionExecutor", ITERATIONS, CONCURRENT_REQUESTS));
        
        // Test HttpClientExecutor if available (Java 11+)
        if (hasHttpClient) {
            System.setProperty(SharedConstants.UseHttpClient, "true");
            System.setProperty(SharedConstants.UseVirtualThreads, "false");
            results.add(runPerformanceTest("HttpClientExecutor", ITERATIONS, CONCURRENT_REQUESTS));
        }
        
        // Test VirtualThreadExecutor if available (Java 21+)
        if (hasVirtualThreads) {
            System.setProperty(SharedConstants.UseVirtualThreads, "true");
            // Note: Setting UseHttpClient doesn't matter here as VirtualThreads takes precedence
            results.add(runPerformanceTest("VirtualThreadExecutor", ITERATIONS, CONCURRENT_REQUESTS));
        }
        
        // Print summary of results
        System.out.println("\n=== PERFORMANCE COMPARISON SUMMARY ===");
        for (PerformanceResult result : results) {
            System.out.printf("%s: %.2f ms per request (avg), %.2f ms total for %d requests%n", 
                result.executorName, 
                result.averageResponseTime, 
                (double)result.totalTime,
                result.totalRequests);
        }
        
        printSummary(results, "PERFORMANCE");
    }
    
    private PerformanceResult runPerformanceTest(String executorName, int iterations, int concurrentRequests) throws Exception {
        System.out.println("\n=== Testing " + executorName + " ===");
        
        long startTime = System.currentTimeMillis();
        long totalRequests = iterations * concurrentRequests;
        AtomicInteger successCount = new AtomicInteger(0);
        List<Long> responseTimes = Collections.synchronizedList(new ArrayList<>(concurrentRequests * iterations));
        
        for (int i = 0; i < iterations; i++) {
            CountDownLatch latch = new CountDownLatch(concurrentRequests);
            
            // Use standard thread pool for running requests to ensure fair comparison
            // (Not using virtual threads for the test harness itself)
            ExecutorService executor = Executors.newFixedThreadPool(Math.min(concurrentRequests, 16));
            
            for (int j = 0; j < concurrentRequests; j++) {
                executor.submit(() -> {
                    long requestStart = System.nanoTime();
                    try {
                        Document doc = Jsoup.connect(FileServlet.urlTo("/htmltests/large.html"))
                            .timeout(30000)
                            .get();
                        
                        if (doc.title().contains("Large")) {
                            successCount.incrementAndGet();
                        }
                        
                        // Record response time in milliseconds
                        long requestEnd = System.nanoTime();
                        long responseTimeMs = (requestEnd - requestStart) / 1_000_000;
                        responseTimes.add(responseTimeMs);
                    } catch (IOException e) {
                        System.err.println("Request failed: " + e.getMessage());
                    } finally {
                        latch.countDown();
                    }
                });
            }
            
            // Wait for all requests to complete
            boolean completed = latch.await(60, TimeUnit.SECONDS);
            if (!completed) {
                System.err.println("Warning: Not all requests completed within timeout");
            }
            
            executor.shutdown();
            executor.awaitTermination(10, TimeUnit.SECONDS);
            
            // Progress indicator
            if ((i + 1) % 5 == 0 || i == iterations - 1) {
                System.out.printf("Completed %d/%d iterations%n", i + 1, iterations);
            }
        }
        
        long endTime = System.currentTimeMillis();
        long totalTime = endTime - startTime;
        
        // Calculate percentiles
        double p95 = 0;
        double medianResponseTime = 0;
        double avgResponseTime = 0;
        
        if (!responseTimes.isEmpty()) {
            // Sort response times for percentile calculation
            Collections.sort(responseTimes);
            
            // Calculate average
            long sum = 0;
            for (long time : responseTimes) {
                sum += time;
            }
            avgResponseTime = (double) sum / responseTimes.size();
            
            // Median (50th percentile)
            int medianIndex = responseTimes.size() / 2;
            medianResponseTime = responseTimes.get(medianIndex);
            
            // 95th percentile
            int p95Index = (int) Math.ceil(responseTimes.size() * 0.95) - 1;
            p95 = p95Index >= 0 ? responseTimes.get(p95Index) : 0;
        } else {
            // Just use totalTime / successCount as avg if no response times
            avgResponseTime = (double) totalTime / successCount.get();
        }
        
        double requestsPerSecond = successCount.get() * 1000.0 / totalTime;
        
        PerformanceResult result = new PerformanceResult(
            executorName, 
            totalTime, 
            successCount.get(), 
            totalRequests,
            avgResponseTime,
            medianResponseTime,
            p95,
            requestsPerSecond
        );
        
        System.out.printf("Results for %s:%n", executorName);
        System.out.printf("Total time: %.2f seconds%n", totalTime / 1000.0);
        System.out.printf("Successful requests: %d/%d (%.2f%%)%n", 
            successCount.get(), totalRequests, 
            (successCount.get() * 100.0 / totalRequests));
        System.out.printf("Average response time: %.2f ms%n", avgResponseTime);
        System.out.printf("Median response time: %.2f ms%n", medianResponseTime);
        System.out.printf("95th percentile: %.2f ms%n", p95);
        System.out.printf("Throughput: %.2f requests/second%n", requestsPerSecond);
        
        return result;
    }
    
    private static class PerformanceResult {
        final String executorName;
        final long totalTime;
        final int successfulRequests;
        final long totalRequests;
        final double averageResponseTime;
        final double medianResponseTime;
        final double p95ResponseTime;
        final double requestsPerSecond;
        
        PerformanceResult(String executorName, long totalTime, int successfulRequests, 
                         long totalRequests, double averageResponseTime, double medianResponseTime,
                         double p95ResponseTime, double requestsPerSecond) {
            this.executorName = executorName;
            this.totalTime = totalTime;
            this.successfulRequests = successfulRequests;
            this.totalRequests = totalRequests;
            this.averageResponseTime = averageResponseTime;
            this.medianResponseTime = medianResponseTime;
            this.p95ResponseTime = p95ResponseTime;
            this.requestsPerSecond = requestsPerSecond;
        }
    }
    
    private void printSummary(List<PerformanceResult> results, String testType) {
        System.out.println("\n=== " + testType + " COMPARISON SUMMARY ===");
        
        // Print header
        System.out.printf("%-23s | %8s | %8s | %8s | %8s | %8s%n", 
            "Executor", "Avg(ms)", "Median(ms)", "P95(ms)", "Req/sec", "Success");
        System.out.println("--------------------------------------------------------------------------------");
        
        // Print data rows
        for (PerformanceResult result : results) {
            System.out.printf("%-23s | %8.2f | %8.2f | %8.2f | %8.2f | %d/%d%n", 
                result.executorName, 
                result.averageResponseTime, 
                result.medianResponseTime,
                result.p95ResponseTime,
                result.requestsPerSecond,
                result.successfulRequests,
                result.totalRequests);
        }
        
        // Find the fastest executor by throughput
        PerformanceResult fastest = results.stream()
            .max((r1, r2) -> Double.compare(r1.requestsPerSecond, r2.requestsPerSecond))
            .orElse(null);
            
        if (fastest != null) {
            System.out.printf("%nHighest throughput: %s (%.2f req/sec)%n", 
                fastest.executorName, fastest.requestsPerSecond);
                
            // Find best latency (lowest p95)
            PerformanceResult lowestLatency = results.stream()
                .min((r1, r2) -> Double.compare(r1.p95ResponseTime, r2.p95ResponseTime))
                .orElse(null);
                
            if (lowestLatency != null) {
                System.out.printf("Lowest 95th percentile latency: %s (%.2f ms)%n", 
                    lowestLatency.executorName, lowestLatency.p95ResponseTime);
            }
                
            // Calculate and print comparison percentages for throughput
            for (PerformanceResult result : results) {
                if (!result.equals(fastest)) {
                    double percentDifference = ((fastest.requestsPerSecond / result.requestsPerSecond) - 1) * 100;
                    System.out.printf("%s throughput is %.2f%% lower than %s%n", 
                        result.executorName, percentDifference, fastest.executorName);
                }
            }
        }
    }
}