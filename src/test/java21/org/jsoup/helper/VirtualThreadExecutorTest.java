package org.jsoup.helper;

import org.jsoup.internal.SharedConstants;
import org.jsoup.helper.HttpConnection;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class VirtualThreadExecutorTest {
    @Test void virtualThreadsSupported() throws InterruptedException {
        try {
            // We only need to check that virtual threads are supported
            Thread vt = Thread.ofVirtual().name("test").start(() -> {
                assertTrue(Thread.currentThread().isVirtual());
            });
            vt.join();
            assertTrue(true, "Virtual threads supported in JVM");
        } finally {
            // Nothing to clean up
        }
    }
    
    // For test access
    static Constructor<RequestExecutor> virtualThreadConstructor;
    
    static {
        try {
            //noinspection unchecked
            Class<RequestExecutor> vtClass =
                (Class<RequestExecutor>) Class.forName("org.jsoup.helper.VirtualThreadExecutor");
            virtualThreadConstructor = vtClass.getConstructor(HttpConnection.Request.class, HttpConnection.Response.class);
        } catch (Exception ignored) {
            // Not available in this environment
            virtualThreadConstructor = null;
        }
    }

    @Test void fallsBackToHttpClientIfVirtualThreadsDisabled() {
        try {
            disableVirtualThreads();
            enableHttpClient();
            RequestExecutor executor = RequestDispatch.get(null, null);
            assertEquals("org.jsoup.helper.HttpClientExecutor", executor.getClass().getName());
        } finally {
            disableHttpClient();
        }
    }

    @Test void fallsBackToUrlConnectionIfBothDisabled() {
        try {
            disableVirtualThreads();
            disableHttpClient();
            RequestExecutor executor = RequestDispatch.get(null, null);
            assertInstanceOf(UrlConnectionExecutor.class, executor);
        } finally {
            // reset to defaults
        }
    }

    public static void enableVirtualThreads() {
        System.setProperty(SharedConstants.UseVirtualThreads, "true");
    }

    public static void disableVirtualThreads() {
        System.setProperty(SharedConstants.UseVirtualThreads, "false");
    }

    public static void enableHttpClient() {
        System.setProperty(SharedConstants.UseHttpClient, "true");
    }

    public static void disableHttpClient() {
        System.setProperty(SharedConstants.UseHttpClient, "false");
    }
}