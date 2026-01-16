/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.esql.parser;

import org.elasticsearch.core.TimeValue;
import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.xpack.esql.plan.logical.HttpRelation;
import org.elasticsearch.xpack.esql.plan.logical.LogicalPlan;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

/**
 * Tests for parsing HTTP source commands.
 */
public class HttpCommandParserTests extends ESTestCase {

    protected final EsqlParser parser = EsqlParser.INSTANCE;

    protected LogicalPlan query(String q) {
        return parser.parseQuery(q, new QueryParams());
    }

    public void testSimpleHttpGet() {
        LogicalPlan plan = query("HTTP GET \"https://api.example.com/users\"");
        assertThat(plan, instanceOf(HttpRelation.class));

        HttpRelation http = (HttpRelation) plan;
        assertThat(http.method(), equalTo(HttpRelation.HttpMethod.GET));
        assertThat(http.url(), equalTo("https://api.example.com/users"));
        assertThat(http.body(), nullValue());
        assertThat(http.headers().isEmpty(), is(true));
    }

    public void testHttpPost() {
        LogicalPlan plan = query("HTTP POST \"https://api.example.com/data\"");
        assertThat(plan, instanceOf(HttpRelation.class));

        HttpRelation http = (HttpRelation) plan;
        assertThat(http.method(), equalTo(HttpRelation.HttpMethod.POST));
        assertThat(http.url(), equalTo("https://api.example.com/data"));
    }

    public void testHttpPut() {
        LogicalPlan plan = query("HTTP PUT \"https://api.example.com/data\"");
        assertThat(plan, instanceOf(HttpRelation.class));

        HttpRelation http = (HttpRelation) plan;
        assertThat(http.method(), equalTo(HttpRelation.HttpMethod.PUT));
    }

    public void testHttpDelete() {
        LogicalPlan plan = query("HTTP DELETE \"https://api.example.com/data/123\"");
        assertThat(plan, instanceOf(HttpRelation.class));

        HttpRelation http = (HttpRelation) plan;
        assertThat(http.method(), equalTo(HttpRelation.HttpMethod.DELETE));
    }

    public void testHttpPostWithBody() {
        LogicalPlan plan = query("HTTP POST \"https://api.example.com/data\" WITH body=\"{\\\"key\\\":\\\"value\\\"}\"");
        assertThat(plan, instanceOf(HttpRelation.class));

        HttpRelation http = (HttpRelation) plan;
        assertThat(http.method(), equalTo(HttpRelation.HttpMethod.POST));
        assertThat(http.body(), equalTo("{\"key\":\"value\"}"));
    }

    public void testHttpWithTimeout() {
        LogicalPlan plan = query("HTTP GET \"https://api.example.com/slow\" WITH timeout=\"60s\"");
        assertThat(plan, instanceOf(HttpRelation.class));

        HttpRelation http = (HttpRelation) plan;
        assertThat(http.timeout(), equalTo(TimeValue.timeValueSeconds(60)));
    }

    public void testHttpWithHeaders() {
        LogicalPlan plan = query("HTTP GET \"https://api.example.com/users\" WITH headers=\"{\\\"Authorization\\\": \\\"Bearer token123\\\"}\"");
        assertThat(plan, instanceOf(HttpRelation.class));

        HttpRelation http = (HttpRelation) plan;
        assertThat(http.headers().get("Authorization"), equalTo("Bearer token123"));
    }

    public void testHttpWithAuth() {
        LogicalPlan plan = query("HTTP GET \"https://api.example.com/users\" WITH auth=\"user:password\"");
        assertThat(plan, instanceOf(HttpRelation.class));

        HttpRelation http = (HttpRelation) plan;
        assertThat(http.auth(), equalTo("user:password"));
    }

    public void testHttpWithMultipleOptions() {
        LogicalPlan plan = query("HTTP POST \"https://api.example.com/data\" WITH body=\"{}\", timeout=\"30s\", auth=\"user:pass\"");
        assertThat(plan, instanceOf(HttpRelation.class));

        HttpRelation http = (HttpRelation) plan;
        assertThat(http.method(), equalTo(HttpRelation.HttpMethod.POST));
        assertThat(http.body(), equalTo("{}"));
        assertThat(http.timeout(), equalTo(TimeValue.timeValueSeconds(30)));
        assertThat(http.auth(), equalTo("user:pass"));
    }

    public void testInternalApiUrl() {
        LogicalPlan plan = query("HTTP GET \"api:///_cat/indices?v\"");
        assertThat(plan, instanceOf(HttpRelation.class));

        HttpRelation http = (HttpRelation) plan;
        assertThat(http.isInternalApiCall(), is(true));
        assertThat(http.getInternalPath(), equalTo("/_cat/indices?v"));
    }

    public void testCaseInsensitiveKeywords() {
        // Test various case combinations
        LogicalPlan plan1 = query("http get \"https://example.com\"");
        assertThat(plan1, instanceOf(HttpRelation.class));

        LogicalPlan plan2 = query("HTTP GET \"https://example.com\"");
        assertThat(plan2, instanceOf(HttpRelation.class));

        LogicalPlan plan3 = query("Http Get \"https://example.com\"");
        assertThat(plan3, instanceOf(HttpRelation.class));
    }

    public void testOutputSchema() {
        LogicalPlan plan = query("HTTP GET \"https://example.com\"");
        assertThat(plan, instanceOf(HttpRelation.class));

        HttpRelation http = (HttpRelation) plan;
        var output = http.output();

        assertThat(output.size(), equalTo(5));
        assertThat(output.get(0).name(), equalTo("status_code"));
        assertThat(output.get(1).name(), equalTo("headers"));
        assertThat(output.get(2).name(), equalTo("body"));
        assertThat(output.get(3).name(), equalTo("response_time_ms"));
        assertThat(output.get(4).name(), equalTo("error"));
    }

    // Test headers with values containing colons (like URLs)
    public void testHttpHeadersWithColonInValue() {
        LogicalPlan plan = query("HTTP GET \"https://example.com\" WITH headers=\"{\\\"X-Callback\\\": \\\"http://host:8080/path\\\"}\"");
        assertThat(plan, instanceOf(HttpRelation.class));

        HttpRelation http = (HttpRelation) plan;
        assertThat(http.headers().get("X-Callback"), equalTo("http://host:8080/path"));
    }

    // Test headers with multiple entries containing special characters
    public void testHttpHeadersMultipleWithSpecialChars() {
        LogicalPlan plan = query(
            "HTTP GET \"https://example.com\" WITH headers=\"{\\\"Authorization\\\": \\\"Bearer abc:123\\\", \\\"X-Custom\\\": \\\"a,b,c\\\"}\""
        );
        assertThat(plan, instanceOf(HttpRelation.class));

        HttpRelation http = (HttpRelation) plan;
        assertThat(http.headers().get("Authorization"), equalTo("Bearer abc:123"));
        assertThat(http.headers().get("X-Custom"), equalTo("a,b,c"));
    }

    // Negative test: invalid HTTP method
    public void testInvalidHttpMethod() {
        ParsingException e = expectThrows(ParsingException.class, () -> query("HTTP PATCH \"https://example.com\""));
        assertThat(e.getMessage(), containsString("PATCH"));
    }

    // Negative test: missing URL
    public void testMissingUrl() {
        ParsingException e = expectThrows(ParsingException.class, () -> query("HTTP GET"));
        assertNotNull(e);
    }

    // Negative test: invalid option name
    public void testInvalidOption() {
        ParsingException e = expectThrows(ParsingException.class, () -> query("HTTP GET \"https://example.com\" WITH invalid=\"value\""));
        assertNotNull(e);
    }

    // Negative test: malformed headers JSON (not an object)
    public void testMalformedHeadersNotObject() {
        ParsingException e = expectThrows(
            ParsingException.class,
            () -> query("HTTP GET \"https://example.com\" WITH headers=\"not json\"")
        );
        assertThat(e.getMessage(), containsString("Invalid headers JSON format"));
    }

    // Negative test: malformed headers JSON (missing value)
    public void testMalformedHeadersMissingValue() {
        ParsingException e = expectThrows(
            ParsingException.class,
            () -> query("HTTP GET \"https://example.com\" WITH headers=\"{\\\"key\\\"}\"")
        );
        assertThat(e.getMessage(), containsString("Invalid headers JSON format"));
    }

    // Test that headers map is immutable
    public void testHeadersImmutability() {
        LogicalPlan plan = query("HTTP GET \"https://example.com\" WITH headers=\"{\\\"Key\\\": \\\"Value\\\"}\"");
        HttpRelation http = (HttpRelation) plan;

        expectThrows(UnsupportedOperationException.class, () -> http.headers().put("New", "Entry"));
    }

    // Test empty headers
    public void testEmptyHeaders() {
        LogicalPlan plan = query("HTTP GET \"https://example.com\" WITH headers=\"{}\"");
        assertThat(plan, instanceOf(HttpRelation.class));

        HttpRelation http = (HttpRelation) plan;
        assertThat(http.headers().isEmpty(), is(true));
    }

    // Test escape sequences in body
    public void testEscapeSequencesInBody() {
        LogicalPlan plan = query("HTTP POST \"https://example.com\" WITH body=\"line1\\nline2\\ttabbed\"");
        assertThat(plan, instanceOf(HttpRelation.class));

        HttpRelation http = (HttpRelation) plan;
        assertThat(http.body(), equalTo("line1\nline2\ttabbed"));
    }
}
