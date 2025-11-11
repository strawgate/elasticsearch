/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */
package org.elasticsearch.xpack.esql.s3;

import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.core.retry.RetryMode;
import software.amazon.awssdk.http.apache.ApacheHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

import java.io.Closeable;
import java.time.Duration;

/**
 * Service for managing S3 client lifecycle and configuration.
 * Provides thread-safe S3Client instances with proper retry and connection pooling.
 */
public class S3ClientService implements Closeable {

    private final S3Client s3Client;

    public S3ClientService() {
        this(Region.US_EAST_1);
    }

    public S3ClientService(Region region) {
        // Configure Apache HTTP client with connection pooling
        ApacheHttpClient.Builder httpClientBuilder = ApacheHttpClient.builder()
            .maxConnections(100) // High connection pool for parallel S3 reads
            .connectionTimeout(Duration.ofSeconds(10))
            .socketTimeout(Duration.ofSeconds(30));

        // Configure retry strategy
        ClientOverrideConfiguration.Builder clientConfig = ClientOverrideConfiguration.builder()
            .retryStrategy(builder -> builder
                .retryMode(RetryMode.STANDARD)
                .maxAttempts(5)
            );

        // Build S3 client with instance profile credentials
        this.s3Client = S3Client.builder()
            .region(region)
            .credentialsProvider(DefaultCredentialsProvider.create())
            .httpClientBuilder(httpClientBuilder)
            .overrideConfiguration(clientConfig.build())
            .build();
    }

    public S3Client getClient() {
        return s3Client;
    }

    @Override
    public void close() {
        if (s3Client != null) {
            s3Client.close();
        }
    }
}
