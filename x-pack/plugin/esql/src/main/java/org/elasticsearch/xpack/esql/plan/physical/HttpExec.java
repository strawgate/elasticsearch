/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.esql.plan.physical;

import org.elasticsearch.common.io.stream.NamedWriteableRegistry;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.core.TimeValue;
import org.elasticsearch.xpack.esql.core.expression.Attribute;
import org.elasticsearch.xpack.esql.core.tree.NodeInfo;
import org.elasticsearch.xpack.esql.core.tree.Source;
import org.elasticsearch.xpack.esql.io.stream.PlanStreamInput;
import org.elasticsearch.xpack.esql.plan.logical.HttpRelation;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Physical execution plan node for HTTP requests.
 * This node is executed during query processing to fetch data from HTTP endpoints.
 */
public class HttpExec extends LeafExec {
    public static final NamedWriteableRegistry.Entry ENTRY = new NamedWriteableRegistry.Entry(
        PhysicalPlan.class,
        "HttpExec",
        HttpExec::new
    );

    private final HttpRelation.HttpMethod method;
    private final String url;
    private final String body;
    private final Map<String, String> headers;
    private final TimeValue timeout;
    private final String auth;
    private final List<Attribute> output;

    public HttpExec(Source source, HttpRelation relation) {
        this(
            source,
            relation.method(),
            relation.url(),
            relation.body(),
            relation.headers(),
            relation.timeout(),
            relation.auth(),
            relation.output()
        );
    }

    public HttpExec(
        Source source,
        HttpRelation.HttpMethod method,
        String url,
        String body,
        Map<String, String> headers,
        TimeValue timeout,
        String auth,
        List<Attribute> output
    ) {
        super(source);
        this.method = method;
        this.url = url;
        this.body = body;
        this.headers = headers;
        this.timeout = timeout;
        this.auth = auth;
        this.output = output;
    }

    @SuppressWarnings("unchecked")
    private HttpExec(StreamInput in) throws IOException {
        this(
            Source.readFrom((PlanStreamInput) in),
            HttpRelation.HttpMethod.valueOf(in.readString()),
            in.readString(),
            in.readOptionalString(),
            (Map<String, String>) in.readGenericValue(),
            in.readOptionalTimeValue(),
            in.readOptionalString(),
            in.readNamedWriteableCollectionAsList(Attribute.class)
        );
    }

    @Override
    public void writeTo(StreamOutput out) throws IOException {
        Source.EMPTY.writeTo(out);
        out.writeString(method.name());
        out.writeString(url);
        out.writeOptionalString(body);
        out.writeGenericValue(headers);
        out.writeOptionalTimeValue(timeout);
        out.writeOptionalString(auth);
        out.writeNamedWriteableCollection(output);
    }

    @Override
    public String getWriteableName() {
        return ENTRY.name;
    }

    public HttpRelation.HttpMethod method() {
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
        return output;
    }

    @Override
    protected NodeInfo<? extends PhysicalPlan> info() {
        return NodeInfo.create(this, HttpExec::new, method, url, body, headers, timeout, auth, output);
    }

    @Override
    public int hashCode() {
        return Objects.hash(method, url, body, headers, timeout, auth, output);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null || getClass() != obj.getClass()) {
            return false;
        }
        HttpExec other = (HttpExec) obj;
        return method == other.method
            && Objects.equals(url, other.url)
            && Objects.equals(body, other.body)
            && Objects.equals(headers, other.headers)
            && Objects.equals(timeout, other.timeout)
            && Objects.equals(auth, other.auth)
            && Objects.equals(output, other.output);
    }
}
