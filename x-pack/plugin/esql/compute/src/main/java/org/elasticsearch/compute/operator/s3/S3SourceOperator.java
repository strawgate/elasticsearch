/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */
package org.elasticsearch.compute.operator.s3;

import org.elasticsearch.compute.data.Block;
import org.elasticsearch.compute.data.BlockFactory;
import org.elasticsearch.compute.data.BytesRefBlock;
import org.elasticsearch.compute.data.IntBlock;
import org.elasticsearch.compute.data.LongBlock;
import org.elasticsearch.compute.data.Page;
import org.elasticsearch.compute.operator.DriverContext;
import org.elasticsearch.compute.operator.SourceOperator;
import org.elasticsearch.xpack.esql.core.expression.Attribute;
import org.elasticsearch.xpack.esql.s3.S3ClientService;
import org.elasticsearch.xpack.esql.s3.S3Uri;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.S3Object;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Source operator that reads data from S3.
 * Simplified implementation that reads CSV files for now.
 */
public class S3SourceOperator extends SourceOperator {
    private final S3ClientService s3ClientService;
    private final S3Uri s3Uri;
    private final List<Attribute> attributes;
    private final BlockFactory blockFactory;
    private final int pageSize;

    private S3Client s3Client;
    private Iterator<S3Object> objectIterator;
    private BufferedReader currentReader;
    private boolean finished;

    public S3SourceOperator(
        S3ClientService s3ClientService,
        String s3UriString,
        List<Attribute> attributes,
        BlockFactory blockFactory,
        int pageSize
    ) {
        this.s3ClientService = s3ClientService;
        this.s3Uri = S3Uri.parse(s3UriString);
        this.attributes = attributes;
        this.blockFactory = blockFactory;
        this.pageSize = pageSize;
        this.finished = false;
    }

    @Override
    public Page getOutput() {
        if (finished) {
            return null;
        }

        try {
            // Initialize S3 client and list objects on first call
            if (s3Client == null) {
                s3Client = s3ClientService.getClient();
                List<S3Object> objects = listS3Objects();
                if (objects.isEmpty()) {
                    finished = true;
                    return null;
                }
                objectIterator = objects.iterator();
            }

            // Move to next object if needed
            if (currentReader == null && objectIterator.hasNext()) {
                S3Object nextObject = objectIterator.next();
                currentReader = openS3Object(nextObject.key());
            }

            if (currentReader == null) {
                finished = true;
                return null;
            }

            // Read a page of data
            List<Block> blocks = readPage();
            if (blocks == null || blocks.isEmpty()) {
                // Try next file
                closeCurrentReader();
                if (objectIterator.hasNext()) {
                    return getOutput(); // Recursively try next file
                } else {
                    finished = true;
                    return null;
                }
            }

            return new Page(blocks.toArray(new Block[0]));

        } catch (IOException e) {
            finished = true;
            throw new RuntimeException("Error reading from S3: " + e.getMessage(), e);
        }
    }

    private List<S3Object> listS3Objects() {
        ListObjectsV2Request listRequest = ListObjectsV2Request.builder()
            .bucket(s3Uri.bucket())
            .prefix(s3Uri.prefix())
            .build();

        ListObjectsV2Response listResponse = s3Client.listObjectsV2(listRequest);
        return listResponse.contents();
    }

    private BufferedReader openS3Object(String key) throws IOException {
        GetObjectRequest getRequest = GetObjectRequest.builder()
            .bucket(s3Uri.bucket())
            .key(key)
            .build();

        ResponseInputStream<GetObjectResponse> s3Stream = s3Client.getObject(getRequest);
        return new BufferedReader(new InputStreamReader(s3Stream));
    }

    private List<Block> readPage() throws IOException {
        List<List<Object>> columnData = new ArrayList<>();
        for (int i = 0; i < attributes.size(); i++) {
            columnData.add(new ArrayList<>());
        }

        int rowsRead = 0;
        String line;

        // Skip header if first line
        if (currentReader != null) {
            currentReader.mark(1000);
            line = currentReader.readLine();
            if (line != null && line.contains(",")) {
                // Check if this looks like a header (contains text)
                boolean isHeader = false;
                for (String part : line.split(",")) {
                    if (part.matches(".*[a-zA-Z].*")) {
                        isHeader = true;
                        break;
                    }
                }
                if (!isHeader) {
                    currentReader.reset();
                }
            }
        }

        while (rowsRead < pageSize && (line = currentReader.readLine()) != null) {
            String[] values = line.split(",");
            for (int i = 0; i < Math.min(values.length, attributes.size()); i++) {
                columnData.get(i).add(values[i].trim());
            }
            rowsRead++;
        }

        if (rowsRead == 0) {
            return null;
        }

        // Build blocks from column data
        List<Block> blocks = new ArrayList<>();
        for (int i = 0; i < attributes.size(); i++) {
            blocks.add(buildBlock(columnData.get(i), attributes.get(i)));
        }

        return blocks;
    }

    private Block buildBlock(List<Object> data, Attribute attribute) {
        String typeName = attribute.dataType().typeName();

        // Simplified type handling - treat everything as keyword for now
        BytesRefBlock.Builder builder = blockFactory.newBytesRefBlockBuilder(data.size());
        for (Object value : data) {
            if (value == null) {
                builder.appendNull();
            } else {
                builder.appendBytesRef(new org.apache.lucene.util.BytesRef(value.toString()));
            }
        }
        return builder.build();
    }

    private void closeCurrentReader() {
        if (currentReader != null) {
            try {
                currentReader.close();
            } catch (IOException e) {
                // Log but don't fail
            }
            currentReader = null;
        }
    }

    @Override
    public boolean isFinished() {
        return finished;
    }

    @Override
    public void finish() {
        finished = true;
    }

    @Override
    public void close() {
        closeCurrentReader();
        if (s3ClientService != null) {
            try {
                s3ClientService.close();
            } catch (IOException e) {
                // Log but don't fail
            }
        }
    }

    /**
     * Factory for creating S3SourceOperator instances.
     */
    public static class S3SourceOperatorFactory implements SourceOperatorFactory {
        private final S3ClientService s3ClientService;
        private final String s3Uri;
        private final List<Attribute> attributes;
        private final int pageSize;

        public S3SourceOperatorFactory(
            S3ClientService s3ClientService,
            String s3Uri,
            List<Attribute> attributes,
            int pageSize
        ) {
            this.s3ClientService = s3ClientService;
            this.s3Uri = s3Uri;
            this.attributes = attributes;
            this.pageSize = pageSize;
        }

        @Override
        public SourceOperator get(DriverContext driverContext) {
            return new S3SourceOperator(
                s3ClientService,
                s3Uri,
                attributes,
                driverContext.blockFactory(),
                pageSize
            );
        }

        @Override
        public String describe() {
            return "S3SourceOperator[uri=" + s3Uri + "]";
        }
    }
}
