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
}
