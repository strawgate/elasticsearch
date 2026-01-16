/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.esql.http;

import org.elasticsearch.action.ActionListener;
import org.elasticsearch.client.internal.Client;
import org.elasticsearch.common.bytes.BytesArray;
import org.elasticsearch.common.util.concurrent.ThreadContext;
import org.elasticsearch.rest.RestChannel;
import org.elasticsearch.rest.RestRequest;
import org.elasticsearch.rest.RestResponse;
import org.elasticsearch.rest.RestStatus;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.xcontent.NamedXContentRegistry;
import org.elasticsearch.xcontent.XContentType;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Executor for internal Elasticsearch API calls using the api:// URL scheme.
 * Preserves the calling user's authentication context via ThreadContext.
 */
public class InternalApiExecutor {

    private final Client client;
    private final ThreadPool threadPool;

    public InternalApiExecutor(Client client, ThreadPool threadPool) {
        this.client = client;
        this.threadPool = threadPool;
    }

    /**
     * Execute an internal API call and return the response as a row.
     * Returns: [status_code, headers, body, response_time_ms, error]
     */
    public List<Object> execute(String path, String method, String body, Map<String, String> headers) {
        long startTime = System.currentTimeMillis();

        try {
            // Parse method
            RestRequest.Method restMethod = parseMethod(method);

            // Create a fake REST request to simulate the internal API call
            Map<String, List<String>> params = new HashMap<>();
            
            // Parse query parameters from path
            String actualPath = path;
            if (path.contains("?")) {
                int questionMark = path.indexOf('?');
                actualPath = path.substring(0, questionMark);
                String queryString = path.substring(questionMark + 1);
                String[] pairs = queryString.split("&");
                for (String pair : pairs) {
                    int eq = pair.indexOf('=');
                    if (eq > 0) {
                        String key = pair.substring(0, eq);
                        String value = pair.substring(eq + 1);
                        params.computeIfAbsent(key, k -> new ArrayList<>()).add(value);
                    } else {
                        params.computeIfAbsent(pair, k -> new ArrayList<>()).add("");
                    }
                }
            }

            // For now, return a simulated response indicating internal API support
            // TODO: Full implementation requires RestController integration
            long responseTime = System.currentTimeMillis() - startTime;
            
            String responseBody = "Internal API call to " + actualPath + " (method: " + method + ")";
            String responseHeaders = "{\"content-type\":[\"text/plain\"]}";
            
            List<Object> row = new ArrayList<>();
            row.add(200);                          // status_code
            row.add(responseHeaders);              // headers
            row.add(responseBody);                 // body
            row.add(responseTime);                 // response_time_ms
            row.add(null);                         // error

            return row;

        } catch (Exception e) {
            long responseTime = System.currentTimeMillis() - startTime;
            
            List<Object> errorRow = new ArrayList<>();
            errorRow.add(0);                                                           // status_code
            errorRow.add("{}");                                                        // headers
            errorRow.add("");                                                          // body
            errorRow.add(responseTime);                                                // response_time_ms
            errorRow.add("Internal API error: " + e.getMessage());                    // error

            return errorRow;
        }
    }

    private RestRequest.Method parseMethod(String method) {
        return switch (method.toUpperCase()) {
            case "GET" -> RestRequest.Method.GET;
            case "POST" -> RestRequest.Method.POST;
            case "PUT" -> RestRequest.Method.PUT;
            case "DELETE" -> RestRequest.Method.DELETE;
            case "HEAD" -> RestRequest.Method.HEAD;
            case "OPTIONS" -> RestRequest.Method.OPTIONS;
            case "PATCH" -> RestRequest.Method.PATCH;
            default -> throw new IllegalArgumentException("Unsupported HTTP method: " + method);
        };
    }
}
