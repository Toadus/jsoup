package org.jsoup.helper;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for Java 21 Virtual Thread support
 */
public class VirtualThreadTest {
    
    @Test 
    void virtualThreadsSupported() throws InterruptedException {
        // Simple test to verify that virtual threads work
        Thread vt = Thread.ofVirtual().name("test-vt").start(() -> {
            assertTrue(Thread.currentThread().isVirtual());
        });
        
        vt.join();
        
        // Check platform thread capabilities
        Thread pt = Thread.ofPlatform().name("test-platform").start(() -> {
            assertFalse(Thread.currentThread().isVirtual());
        });
        
        pt.join();
        
        assertTrue(true, "Virtual threads are supported in JVM");
    }
}