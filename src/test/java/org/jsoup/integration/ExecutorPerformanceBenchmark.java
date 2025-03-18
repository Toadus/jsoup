package org.jsoup.integration;

/**
 * Main class to run the ExecutorPerformanceTest as a standalone benchmark.
 * This class can be run directly from the command line.
 */
public class ExecutorPerformanceBenchmark {
    
    public static void main(String[] args) throws Exception {
        System.out.println("Starting jsoup Executor Performance Benchmark");
        System.out.println("This benchmark compares the performance of jsoup's three executor implementations:");
        System.out.println("1. UrlConnectionExecutor (pre-Java 11)");
        System.out.println("2. HttpClientExecutor (Java 11+)");
        System.out.println("3. VirtualThreadExecutor (Java 21+)");
        System.out.println("\nJava version: " + System.getProperty("java.version"));
        System.out.println("OS: " + System.getProperty("os.name") + " " + System.getProperty("os.version"));
        System.out.println("----------------------------------------------------");
        
        // Create and run the test
        ExecutorPerformanceTest test = new ExecutorPerformanceTest();
        
        // Setup
        try {
            ExecutorPerformanceTest.class.getMethod("setup").invoke(null);
        } catch (Exception e) {
            System.err.println("Error during setup: " + e.getMessage());
            return;
        }
        
        // Run the test
        try {
            test.compareExecutorPerformance();
        } catch (Exception e) {
            System.err.println("Error during test execution: " + e.getMessage());
            e.printStackTrace();
        }
        
        // Cleanup
        try {
            ExecutorPerformanceTest.class.getMethod("cleanup").invoke(null);
        } catch (Exception e) {
            System.err.println("Error during cleanup: " + e.getMessage());
        }
    }
}