/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.compute.operator;

import org.elasticsearch.compute.data.BlockFactory;
import org.elasticsearch.core.TimeValue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Operator for executing HTTP requests and producing pages from the response.
 */
public class HttpOperator extends LocalSourceOperator {

    public record HttpOperatorFactory(
        String method,
        String url,
        String body,
        Map<String, String> headers,
        TimeValue timeout,
        String auth
    ) implements SourceOperatorFactory {
        @Override
        public String describe() {
            return "HttpOperator[method=" + method + ", url=" + url + "]";
        }

        @Override
        public SourceOperator get(DriverContext driverContext) {
            return new HttpOperator(driverContext.blockFactory(), method, url, body, headers, timeout, auth);
        }
    }

    private final String method;
    private final String url;
    private final String body;
    private final Map<String, String> headers;
    private final TimeValue timeout;
    private final String auth;

    public HttpOperator(
        BlockFactory blockFactory,
        String method,
        String url,
        String body,
        Map<String, String> headers,
        TimeValue timeout,
        String auth
    ) {
        super(blockFactory, () -> executeHttpRequest(method, url, body, headers, timeout, auth));
        this.method = method;
        this.url = url;
        this.body = body;
        this.headers = headers;
        this.timeout = timeout;
        this.auth = auth;
    }

    /**
     * Execute the HTTP request and return the results as a list of rows.
     * Returns a single row with columns: status_code, headers, body, response_time_ms, error
     */
    private static List<List<Object>> executeHttpRequest(
        String method,
        String url,
        String body,
        Map<String, String> headers,
        TimeValue timeout,
        String auth
    ) {
        long startTime = System.currentTimeMillis();
        HttpURLConnection connection = null;
        
        try {
            // Handle internal API calls (api:// scheme)
            // Converts api:///_cat/indices -> http://localhost:9200/_cat/indices
            String actualUrl = url;
            if (url.startsWith("api://")) {
                String path = url.substring(6); // Remove "api://"
                // TODO: Make port configurable or detect from environment
                // For now, assumes standard Elasticsearch HTTP port
                actualUrl = "http://localhost:9200" + path;
            }
            
            // Create connection
            URL urlObj = new URL(actualUrl);
            connection = (HttpURLConnection) urlObj.openConnection();
            connection.setRequestMethod(method.toUpperCase());
            connection.setConnectTimeout((int) timeout.millis());
            connection.setReadTimeout((int) timeout.millis());

            // Add custom headers
            if (headers != null) {
                for (Map.Entry<String, String> header : headers.entrySet()) {
                    connection.setRequestProperty(header.getKey(), header.getValue());
                }
            }

            // Add authentication
            if (auth != null && !auth.isEmpty()) {
                String encodedAuth = Base64.getEncoder().encodeToString(auth.getBytes(StandardCharsets.UTF_8));
                connection.setRequestProperty("Authorization", "Basic " + encodedAuth);
            }

            // Send body for POST/PUT
            if (body != null && ("POST".equals(method.toUpperCase()) || "PUT".equals(method.toUpperCase()))) {
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json");
                try (OutputStream os = connection.getOutputStream()) {
                    byte[] input = body.getBytes(StandardCharsets.UTF_8);
                    os.write(input, 0, input.length);
                }
            }

            // Get response
            int statusCode = connection.getResponseCode();
            long responseTime = System.currentTimeMillis() - startTime;

            // Build response headers as JSON-like string
            Map<String, List<String>> responseHeadersMap = connection.getHeaderFields();
            String responseHeaders = responseHeadersMap.entrySet().stream()
                .filter(e -> e.getKey() != null) // Skip null key (status line)
                .map(e -> "\"" + e.getKey() + "\":[" + 
                    e.getValue().stream()
                        .map(v -> "\"" + (v != null ? v.replace("\"", "\\\"") : "") + "\"")
                        .collect(Collectors.joining(",")) + "]")
                .collect(Collectors.joining(",", "{", "}"));

            // Read response body
            StringBuilder responseBody = new StringBuilder();
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(
                        statusCode >= 400 ? connection.getErrorStream() : connection.getInputStream(),
                        StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    responseBody.append(line).append("\n");
                }
            } catch (IOException e) {
                // If error stream is null, just use empty body
            }

            // Return single row: [status_code, headers, body, response_time_ms, error]
            List<Object> row = new ArrayList<>();
            row.add(statusCode);                      // status_code (integer)
            row.add(responseHeaders);                 // headers (keyword/string)
            row.add(responseBody.toString());         // body (keyword/string)
            row.add(responseTime);                    // response_time_ms (long)
            row.add(null);                            // error (null on success)

            return List.of(row);

        } catch (Exception e) {
            long responseTime = System.currentTimeMillis() - startTime;
            
            // Return error row: [0, {}, "", response_time_ms, error_message]
            List<Object> errorRow = new ArrayList<>();
            errorRow.add(0);                          // status_code (0 for errors)
            errorRow.add("{}");                       // headers (empty JSON)
            errorRow.add("");                         // body (empty)
            errorRow.add(responseTime);               // response_time_ms
            errorRow.add(e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());

            return List.of(errorRow);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        HttpOperator that = (HttpOperator) o;
        return Objects.equals(method, that.method)
            && Objects.equals(url, that.url)
            && Objects.equals(body, that.body)
            && Objects.equals(headers, that.headers)
            && Objects.equals(timeout, that.timeout)
            && Objects.equals(auth, that.auth);
    }

    @Override
    public int hashCode() {
        return Objects.hash(method, url, body, headers, timeout, auth);
    }
}
