/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.esql.plan.logical;

import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.core.TimeValue;
import org.elasticsearch.xpack.esql.capabilities.TelemetryAware;
import org.elasticsearch.xpack.esql.core.expression.Attribute;
import org.elasticsearch.xpack.esql.core.expression.ReferenceAttribute;
import org.elasticsearch.xpack.esql.core.tree.NodeInfo;
import org.elasticsearch.xpack.esql.core.tree.Source;
import org.elasticsearch.xpack.esql.core.type.DataType;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.elasticsearch.xpack.esql.core.type.DataType.INTEGER;
import static org.elasticsearch.xpack.esql.core.type.DataType.KEYWORD;
import static org.elasticsearch.xpack.esql.core.type.DataType.LONG;

/**
 * Logical plan node for HTTP source command that fetches data from an HTTP endpoint.
 * This represents the parsed HTTP command before physical execution.
 */
public class HttpRelation extends LeafPlan implements TelemetryAware {

    /**
     * HTTP methods supported by the HTTP command.
     */
    public enum HttpMethod {
        GET,
        POST,
        PUT,
        DELETE
    }

    private final HttpMethod method;
    private final String url;
    private final String body;
    private final Map<String, String> headers;
    private final TimeValue timeout;
    private final String auth;

    // Fixed output schema for HTTP responses
    private final List<Attribute> attributes;

    public HttpRelation(
        Source source,
        HttpMethod method,
        String url,
        String body,
        Map<String, String> headers,
        TimeValue timeout,
        String auth
    ) {
        super(source);
        this.method = method;
        this.url = url;
        this.body = body;
        this.headers = headers;
        this.timeout = timeout;
        this.auth = auth;

        // Create fixed output schema
        this.attributes = List.of(
            new ReferenceAttribute(Source.EMPTY, null, "status_code", INTEGER),
            new ReferenceAttribute(Source.EMPTY, null, "headers", KEYWORD),
            new ReferenceAttribute(Source.EMPTY, null, "body", KEYWORD),
            new ReferenceAttribute(Source.EMPTY, null, "response_time_ms", LONG),
            new ReferenceAttribute(Source.EMPTY, null, "error", KEYWORD)
        );
    }

    @Override
    public void writeTo(StreamOutput out) throws IOException {
        throw new UnsupportedOperationException("not serialized");
    }

    @Override
    public String getWriteableName() {
        throw new UnsupportedOperationException("not serialized");
    }

    public HttpMethod method() {
        return method;
    }

    public String url() {
        return url;
    }

    public String body() {
        return body;
    }

    public Map<String, String> headers() {
        return headers;
    }

    public TimeValue timeout() {
        return timeout;
    }

    public String auth() {
        return auth;
    }

    /**
     * Check if this is an internal API call using the api:// URL scheme.
     */
    public boolean isInternalApiCall() {
        return url != null && url.startsWith("api://");
    }

    /**
     * Get the internal API path from the api:// URL scheme.
     */
    public String getInternalPath() {
        if (isInternalApiCall()) {
            return url.substring("api://".length());
        }
        return null;
    }

    @Override
    public List<Attribute> output() {
        return attributes;
    }

    @Override
    public boolean expressionsResolved() {
        return true;
    }

    @Override
    public String telemetryLabel() {
        return "HTTP";
    }

    @Override
    protected NodeInfo<? extends LogicalPlan> info() {
        return NodeInfo.create(this, HttpRelation::new, method, url, body, headers, timeout, auth);
    }

    @Override
    public int hashCode() {
        return Objects.hash(method, url, body, headers, timeout, auth);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null || getClass() != obj.getClass()) {
            return false;
        }
        HttpRelation other = (HttpRelation) obj;
        return method == other.method
            && Objects.equals(url, other.url)
            && Objects.equals(body, other.body)
            && Objects.equals(headers, other.headers)
            && Objects.equals(timeout, other.timeout)
            && Objects.equals(auth, other.auth);
    }
}
