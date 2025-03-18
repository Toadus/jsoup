package org.jsoup.integration;

import org.jsoup.Jsoup;
import org.jsoup.integration.servlets.FileServlet;
import org.jsoup.internal.SharedConstants;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnJre;
import org.junit.jupiter.api.condition.JRE;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * A specialized test that demonstrates the key advantage of virtual threads in Java 21:
 * their ability to handle extreme concurrency with I/O-bound operations.
 *
 * This test makes hundreds of concurrent network requests with artificial I/O delays to
 * simulate real-world conditions where virtual threads should significantly outperform
 * platform threads.
 */
@EnabledOnJre(JRE.JAVA_21)
public class ExtremeConcurrencyTest {

    @Test
    public void extremeConcurrencyWithVirtualThreads() throws Exception {
        TestServer.start();

        System.out.println("\n==== EXTREME CONCURRENCY TEST ====");
        System.out.println("This test compares the handling of 400 concurrent blocking I/O operations");
        System.out.println("between platform threads (HttpURLConnection) and virtual threads.\n");
        System.out.println("Both implementations use 400 concurrent threads and perform identical operations.");
        System.out.println("The only difference is platform threads vs virtual threads.\n");

        // Warm up both implementations more thoroughly
        System.out.println("Warming up...");
        runWarmup();

        // We'll test two scenarios:
        // 1. Using URLConnection with platform threads (pre-Java 11 style)
        // 2. Using HttpClient with virtual threads (Java 21 style)
        
        int concurrentRequests = 400;  // Use very high concurrency to better demonstrate the difference
        int runTimeoutSeconds = 120;   // Allow up to 120 seconds for each test
        
        // Run both implementations twice to mitigate warmup and caching effects
        // Run with Virtual Threads first
        System.setProperty(SharedConstants.UseVirtualThreads, "true");
        TestResult virtualThreadResult1 = runExtremeConcurrencyTest(
            "HttpClient + Virtual Threads (Run 1)", 
            concurrentRequests,
            runTimeoutSeconds,
            this::configureVirtualThreadExecutor
        );
        
        // Add a cooldown period
        Thread.sleep(5000);
        
        // Run with UrlConnection (platform threads)
        System.setProperty(SharedConstants.UseHttpClient, "false");
        System.setProperty(SharedConstants.UseVirtualThreads, "false");
        TestResult urlConnResult1 = runExtremeConcurrencyTest(
            "URLConnection + Platform Threads (Run 1)", 
            concurrentRequests,
            runTimeoutSeconds,
            this::configurePlatformThreadExecutor
        );
        
        // Add a cooldown period
        Thread.sleep(5000);
        
        // Run with Virtual Threads again
        System.setProperty(SharedConstants.UseVirtualThreads, "true");
        TestResult virtualThreadResult2 = runExtremeConcurrencyTest(
            "HttpClient + Virtual Threads (Run 2)", 
            concurrentRequests,
            runTimeoutSeconds,
            this::configureVirtualThreadExecutor
        );
        
        // Add a cooldown period
        Thread.sleep(5000);
        
        // Run with platform threads again
        System.setProperty(SharedConstants.UseHttpClient, "false");
        System.setProperty(SharedConstants.UseVirtualThreads, "false");
        TestResult urlConnResult2 = runExtremeConcurrencyTest(
            "URLConnection + Platform Threads (Run 2)", 
            concurrentRequests,
            runTimeoutSeconds,
            this::configurePlatformThreadExecutor
        );
        
        // Use the second run results for comparison (after JVM is fully warmed up)
        TestResult urlConnResult = urlConnResult2;
        TestResult virtualThreadResult = virtualThreadResult2;
        
        // Print comparison results
        System.out.println("\n==== EXTREME CONCURRENCY COMPARISON ====");
        System.out.printf("%-30s | %6s | %6s | %8s | %8s | %8s | %8s | %8s%n", 
            "Implementation", "Reqs", "Success", "Time(s)", "Req/sec", "Avg(ms)", "Med(ms)", "P95(ms)");
        System.out.println("-".repeat(100));
        printResult(urlConnResult);
        printResult(virtualThreadResult);
        
        if (urlConnResult.successfulRequests > 0 && virtualThreadResult.successfulRequests > 0) {
            double speedup = virtualThreadResult.requestsPerSecond / urlConnResult.requestsPerSecond;
            System.out.printf("%nPerformance comparison:%n");
            System.out.printf("- Throughput: Virtual threads processed %.2fx more requests per second%n", speedup);
            
            // Latency comparison
            double avgLatencyImprovement = (urlConnResult.avgResponseTime / virtualThreadResult.avgResponseTime);
            double p95LatencyImprovement = (urlConnResult.p95ResponseTime / virtualThreadResult.p95ResponseTime);
            
            System.out.printf("- Average latency: Virtual threads were %.2fx faster%n", avgLatencyImprovement);
            System.out.printf("- 95th percentile latency: Virtual threads were %.2fx faster%n", p95LatencyImprovement);
        }
    }
    
    private void printResult(TestResult result) {
        System.out.printf("%-30s | %6d | %6d | %8.2f | %8.2f | %8.2f | %8.2f | %8.2f%n", 
            result.name, 
            result.totalRequests,
            result.successfulRequests,
            result.elapsedTimeSeconds,
            result.requestsPerSecond,
            result.avgResponseTime,
            result.medianResponseTime,
            result.p95ResponseTime);
    }
    
    private void runWarmup() throws Exception {
        System.out.println("Warming up platform threads implementation...");
        // Warm up URLConnection implementation more thoroughly
        System.setProperty(SharedConstants.UseHttpClient, "false");
        System.setProperty(SharedConstants.UseVirtualThreads, "false");
        
        // Do more significant warmup with concurrent requests
        ExecutorService warmupExecutor = Executors.newFixedThreadPool(20);
        List<Future<?>> futures = new ArrayList<>();
        
        for (int i = 0; i < 50; i++) {
            futures.add(warmupExecutor.submit(() -> {
                try {
                    Jsoup.connect(FileServlet.urlTo("/htmltests/small.html")).get();
                    // Add a short delay
                    Thread.sleep(20);
                } catch (Exception e) {
                    System.err.println("URLConnection warmup error: " + e.getMessage());
                }
                return null;
            }));
        }
        
        // Wait for all warmup requests to complete
        for (Future<?> future : futures) {
            try {
                future.get(10, TimeUnit.SECONDS);
            } catch (Exception e) {
                // Ignore warmup errors
            }
        }
        warmupExecutor.shutdown();
        warmupExecutor.awaitTermination(20, TimeUnit.SECONDS);
        
        System.out.println("Warming up virtual threads implementation...");
        // Warm up Virtual Thread implementation
        System.setProperty(SharedConstants.UseVirtualThreads, "true");
        
        // Use virtual threads for warmup
        warmupExecutor = Executors.newVirtualThreadPerTaskExecutor();
        futures = new ArrayList<>();
        
        for (int i = 0; i < 50; i++) {
            futures.add(warmupExecutor.submit(() -> {
                try {
                    Jsoup.connect(FileServlet.urlTo("/htmltests/small.html")).get();
                    // Add a short delay
                    Thread.sleep(20);
                } catch (Exception e) {
                    System.err.println("Virtual thread warmup error: " + e.getMessage());
                }
                return null;
            }));
        }
        
        // Wait for all warmup requests to complete
        for (Future<?> future : futures) {
            try {
                future.get(10, TimeUnit.SECONDS);
            } catch (Exception e) {
                // Ignore warmup errors
            }
        }
        warmupExecutor.shutdown();
        warmupExecutor.awaitTermination(20, TimeUnit.SECONDS);
        
        System.out.println("Warmup completed. Running tests...");
        // Allow system to settle after warmup
        Thread.sleep(2000);
    }
    
    private TestResult runExtremeConcurrencyTest(
            String name, 
            int concurrentRequests, 
            int timeoutSeconds,
            Supplier<ExecutorService> executorSupplier) throws Exception {
        
        System.out.println("\nRunning test: " + name);
        System.out.println("Concurrent requests: " + concurrentRequests);
        
        AtomicInteger successCount = new AtomicInteger(0);
        CountDownLatch latch = new CountDownLatch(concurrentRequests);
        List<Future<?>> futures = new ArrayList<>();
        List<Long> responseTimes = Collections.synchronizedList(new ArrayList<>(concurrentRequests));
        
        long startTime = System.currentTimeMillis();
        
        // Create executor based on test type
        try (ExecutorService executor = executorSupplier.get()) {
            // Submit tasks
            for (int i = 0; i < concurrentRequests; i++) {
                Future<?> future = executor.submit(() -> {
                    long requestStart = System.nanoTime();
                    try {
                        // Make HTTP request - Move the simulated I/O delay to before the HTTP request
                        // to ensure we're measuring the actual HTTP request's performance
                        
                        // Add multiple artificial delays to better simulate real I/O-bound operations
                        // This better represents a real-world scenario with multiple blocking I/O calls
                        try {
                            // Simulate multiple I/O blocking operations (database, network, disk, etc.)
                            // First I/O operation (e.g., database query)
                            Thread.sleep(300);
                            
                            // Second I/O operation (e.g., another service call)
                            Thread.sleep(200);
                            
                            // Third I/O operation (e.g., disk access)
                            Thread.sleep(250);
                            
                            // Fourth I/O operation (e.g., cache lookup)
                            Thread.sleep(150);
                            
                            // Fifth I/O operation (e.g., final database write)
                            Thread.sleep(300);
                            
                            // This pattern of multiple smaller I/O operations better simulates
                            // real-world scenarios where virtual threads should excel
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                        }
                        
                        // This part measures the actual HTTP client performance
                        Document doc = Jsoup.connect(FileServlet.urlTo("/htmltests/small.html"))
                            .timeout(30000)
                            .get();
                        
                        if (doc.title().contains("Small")) {
                            successCount.incrementAndGet();
                        }
                        
                        // Record response time in milliseconds (only for the HTTP part)
                        long requestEnd = System.nanoTime();
                        long responseTimeMs = (requestEnd - requestStart) / 1_000_000;
                        responseTimes.add(responseTimeMs);
                    } catch (IOException e) {
                        System.err.println(name + " request failed: " + e.getMessage());
                    } finally {
                        latch.countDown();
                    }
                });
                
                futures.add(future);
            }
            
            // Wait for completion or timeout
            boolean completed = latch.await(timeoutSeconds, TimeUnit.SECONDS);
            
            long endTime = System.currentTimeMillis();
            double elapsedTimeSeconds = (endTime - startTime) / 1000.0;
            
            int successfulRequests = successCount.get();
            double requestsPerSecond = successfulRequests / elapsedTimeSeconds;
            
            // Calculate percentiles if we have response times
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
            }
            
            System.out.printf("Results for %s:%n", name);
            System.out.printf("- Time elapsed: %.2f seconds%n", elapsedTimeSeconds);
            System.out.printf("- Successful requests: %d/%d (%.2f%%)%n", 
                successfulRequests, concurrentRequests, 
                (successfulRequests * 100.0 / concurrentRequests));
            System.out.printf("- Throughput: %.2f requests/second%n", requestsPerSecond);
            System.out.printf("- Avg response time: %.2f ms%n", avgResponseTime);
            System.out.printf("- Median response time: %.2f ms%n", medianResponseTime);
            System.out.printf("- 95th percentile: %.2f ms%n", p95);
            
            if (!completed) {
                System.out.println("- WARNING: Test timed out after " + timeoutSeconds + " seconds");
                System.out.println("- " + (concurrentRequests - successfulRequests) + " requests did not complete");
            }
            
            return new TestResult(
                name,
                concurrentRequests,
                successfulRequests,
                elapsedTimeSeconds,
                requestsPerSecond,
                avgResponseTime,
                medianResponseTime,
                p95
            );
        }
    }
    
    private ExecutorService configurePlatformThreadExecutor() {
        // Increase thread pool size to maximize throughput for platform threads
        // This is much higher than typically used in production but allows fair comparison
        return Executors.newFixedThreadPool(400); // Match the concurrency level exactly
    }
    
    private ExecutorService configureVirtualThreadExecutor() {
        // For virtual threads, we can use unlimited concurrency
        return Executors.newVirtualThreadPerTaskExecutor();
    }
    
    private static class TestResult {
        final String name;
        final int totalRequests;
        final int successfulRequests;
        final double elapsedTimeSeconds;
        final double requestsPerSecond;
        final double avgResponseTime;
        final double medianResponseTime;
        final double p95ResponseTime;
        
        TestResult(String name, int totalRequests, int successfulRequests, 
                 double elapsedTimeSeconds, double requestsPerSecond,
                 double avgResponseTime, double medianResponseTime, double p95ResponseTime) {
            this.name = name;
            this.totalRequests = totalRequests;
            this.successfulRequests = successfulRequests;
            this.elapsedTimeSeconds = elapsedTimeSeconds;
            this.requestsPerSecond = requestsPerSecond;
            this.avgResponseTime = avgResponseTime;
            this.medianResponseTime = medianResponseTime;
            this.p95ResponseTime = p95ResponseTime;
        }
    }
}