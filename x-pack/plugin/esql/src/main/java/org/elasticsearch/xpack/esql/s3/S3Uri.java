/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */
package org.elasticsearch.xpack.esql.s3;

import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parser and representation of S3 URIs.
 * Supports formats like: s3://bucket/path/*.parquet
 */
public class S3Uri {
    private static final Pattern S3_URI_PATTERN = Pattern.compile("s3://([^/]+)(/.*)?");

    private final String bucket;
    private final String key;
    private final String format;

    public S3Uri(String bucket, String key, String format) {
        this.bucket = bucket;
        this.key = key;
        this.format = format;
    }

    /**
     * Parse an S3 URI string into components.
     * @param uri The S3 URI (e.g., s3://bucket/path/*.parquet)
     * @return Parsed S3Uri object
     */
    public static S3Uri parse(String uri) {
        Matcher matcher = S3_URI_PATTERN.matcher(uri);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("Invalid S3 URI: " + uri);
        }

        String bucket = matcher.group(1);
        String path = matcher.group(2);
        String key = path != null && path.startsWith("/") ? path.substring(1) : (path != null ? path : "");

        // Determine format from file extension
        String format = "parquet"; // default
        if (uri.endsWith(".csv") || uri.contains("*.csv")) {
            format = "csv";
        } else if (uri.endsWith(".json") || uri.contains("*.json")) {
            format = "json";
        }

        return new S3Uri(bucket, key, format);
    }

    public String bucket() {
        return bucket;
    }

    public String key() {
        return key;
    }

    public String prefix() {
        // Remove wildcard pattern to get prefix for listing
        String cleanKey = key.replace("*", "");
        int lastSlash = cleanKey.lastIndexOf('/');
        return lastSlash > 0 ? cleanKey.substring(0, lastSlash + 1) : "";
    }

    public String format() {
        return format;
    }

    @Override
    public String toString() {
        return "s3://" + bucket + "/" + key;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        S3Uri s3Uri = (S3Uri) o;
        return Objects.equals(bucket, s3Uri.bucket) && Objects.equals(key, s3Uri.key) && Objects.equals(format, s3Uri.format);
    }

    @Override
    public int hashCode() {
        return Objects.hash(bucket, key, format);
    }
}
