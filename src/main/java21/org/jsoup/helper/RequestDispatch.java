package org.jsoup.helper;

import org.jsoup.internal.SharedConstants;
import org.jspecify.annotations.Nullable;

import static org.jsoup.helper.HttpConnection.Request;
import static org.jsoup.helper.HttpConnection.Response;

import java.lang.reflect.Constructor;

/**
 * Dispatches requests to either VirtualThreadExecutor (Java 21+), HttpClient (JDK 11+) or HttpURLConnection implementations.
 * At startup, if we can instantiate the VirtualThreadExecutor class, requests will use that if the system property
 * {@link SharedConstants#UseVirtualThreads} is set to {@code true}.
 */
class RequestDispatch {

    @Nullable
    static Constructor<RequestExecutor> clientConstructor;
    
    @Nullable
    static Constructor<RequestExecutor> virtualThreadConstructor;

    static {
        try {
            //noinspection unchecked
            Class<RequestExecutor> httpClass =
                (Class<RequestExecutor>) Class.forName("org.jsoup.helper.HttpClientExecutor");
            clientConstructor = httpClass.getConstructor(Request.class, Response.class);
        } catch (Exception ignored) {
            // either not on Java11+, or on Android; will provide UrlConnectionExecutor
        }
        
        try {
            //noinspection unchecked
            Class<RequestExecutor> vtClass =
                (Class<RequestExecutor>) Class.forName("org.jsoup.helper.VirtualThreadExecutor");
            virtualThreadConstructor = vtClass.getConstructor(Request.class, Response.class);
        } catch (Exception ignored) {
            // not on Java 21+; will try to provide HttpClientExecutor or fallback to UrlConnectionExecutor
        }
    }

    static RequestExecutor get(Request request, @Nullable Response previousResponse) {
        if (Boolean.getBoolean(SharedConstants.UseVirtualThreads) && virtualThreadConstructor != null) {
            try {
                return virtualThreadConstructor.newInstance(request, previousResponse);
            } catch (Exception e) {
                // Fall through to other executors
            }
        }
        
        if (Boolean.getBoolean(SharedConstants.UseHttpClient) && clientConstructor != null) {
            try {
                return clientConstructor.newInstance(request, previousResponse);
            } catch (Exception e) {
                return new UrlConnectionExecutor(request, previousResponse);
            }
        } else {
            return new UrlConnectionExecutor(request, previousResponse);
        }
    }
}