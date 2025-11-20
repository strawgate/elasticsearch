/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */
package org.elasticsearch.xpack.esql.operator.s3;

import org.elasticsearch.compute.data.Block;
import org.elasticsearch.compute.data.BlockFactory;
import org.elasticsearch.compute.data.BytesRefBlock;
import org.elasticsearch.compute.data.DoubleBlock;
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
    private boolean headerSkipped;

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
        // Check if this is a specific file (no wildcards) or a pattern
        if (s3Uri.key().contains("*")) {
            // Pattern with wildcards - list and filter
            ListObjectsV2Request listRequest = ListObjectsV2Request.builder()
                .bucket(s3Uri.bucket())
                .prefix(s3Uri.prefix())
                .build();

            ListObjectsV2Response listResponse = s3Client.listObjectsV2(listRequest);

            // Filter results to match the pattern
            String pattern = s3Uri.key().replace("*", ".*");
            return listResponse.contents().stream()
                .filter(obj -> obj.key().matches(pattern))
                .toList();
        } else {
            // Specific file - return just this one file
            // We don't need to list, just verify it exists (done in schema resolution)
            // Create a minimal S3Object for this single file
            return List.of(
                software.amazon.awssdk.services.s3.model.S3Object.builder()
                    .key(s3Uri.key())
                    .build()
            );
        }
    }

    private BufferedReader openS3Object(String key) throws IOException {
        GetObjectRequest getRequest = GetObjectRequest.builder()
            .bucket(s3Uri.bucket())
            .key(key)
            .build();

        ResponseInputStream<GetObjectResponse> s3Stream = s3Client.getObject(getRequest);
        headerSkipped = false; // Reset for new file
        return new BufferedReader(new InputStreamReader(s3Stream));
    }

    private List<Block> readPage() throws IOException {
        List<List<Object>> columnData = new ArrayList<>();
        for (int i = 0; i < attributes.size(); i++) {
            columnData.add(new ArrayList<>());
        }

        // Skip header line on first read of each file
        if (!headerSkipped && currentReader != null) {
            String headerLine = currentReader.readLine();
            if (headerLine == null) {
                return null; // Empty file
            }
            headerSkipped = true;
        }

        int rowsRead = 0;
        String line;

        while (rowsRead < pageSize && (line = currentReader.readLine()) != null) {
            if (line.trim().isEmpty()) {
                continue; // Skip empty lines
            }

            String[] values = parseCSVLine(line);
            for (int i = 0; i < Math.min(values.length, attributes.size()); i++) {
                columnData.get(i).add(values[i].trim());
            }
            // Pad missing columns with null
            for (int i = values.length; i < attributes.size(); i++) {
                columnData.get(i).add(null);
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

    /**
     * Parse a CSV line handling quotes and escaped characters.
     * Matches the logic in S3Resolver for consistency.
     */
    private String[] parseCSVLine(String line) {
        List<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;

        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);

            if (c == '"') {
                // Handle escaped quotes ("")
                if (inQuotes && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    current.append('"');
                    i++; // Skip next quote
                } else {
                    inQuotes = !inQuotes;
                }
            } else if (c == ',' && !inQuotes) {
                // End of field
                result.add(current.toString());
                current = new StringBuilder();
            } else {
                current.append(c);
            }
        }

        // Add last field
        result.add(current.toString());

        return result.toArray(new String[0]);
    }

    private Block buildBlock(List<Object> data, Attribute attribute) {
        String typeName = attribute.dataType().typeName();

        // Handle different data types
        switch (typeName) {
            case "integer":
                IntBlock.Builder intBuilder = blockFactory.newIntBlockBuilder(data.size());
                for (Object value : data) {
                    if (value == null || value.toString().trim().isEmpty()) {
                        intBuilder.appendNull();
                    } else {
                        try {
                            intBuilder.appendInt(Integer.parseInt(value.toString().trim()));
                        } catch (NumberFormatException e) {
                            intBuilder.appendNull(); // Fallback to null on parse error
                        }
                    }
                }
                return intBuilder.build();

            case "long":
                LongBlock.Builder longBuilder = blockFactory.newLongBlockBuilder(data.size());
                for (Object value : data) {
                    if (value == null || value.toString().trim().isEmpty()) {
                        longBuilder.appendNull();
                    } else {
                        try {
                            longBuilder.appendLong(Long.parseLong(value.toString().trim()));
                        } catch (NumberFormatException e) {
                            longBuilder.appendNull();
                        }
                    }
                }
                return longBuilder.build();

            case "double":
                // Use DoubleBlock for double values
                DoubleBlock.Builder doubleBuilder = blockFactory.newDoubleBlockBuilder(data.size());
                for (Object value : data) {
                    if (value == null || value.toString().trim().isEmpty()) {
                        doubleBuilder.appendNull();
                    } else {
                        try {
                            double d = Double.parseDouble(value.toString().trim());
                            doubleBuilder.appendDouble(d);
                        } catch (NumberFormatException e) {
                            doubleBuilder.appendNull();
                        }
                    }
                }
                return doubleBuilder.build();

            case "boolean":
                // Booleans stored as bytes (0 or 1) in IntBlock
                IntBlock.Builder boolBuilder = blockFactory.newIntBlockBuilder(data.size());
                for (Object value : data) {
                    if (value == null || value.toString().trim().isEmpty()) {
                        boolBuilder.appendNull();
                    } else {
                        String strValue = value.toString().trim().toLowerCase();
                        if (strValue.equals("true")) {
                            boolBuilder.appendInt(1);
                        } else if (strValue.equals("false")) {
                            boolBuilder.appendInt(0);
                        } else {
                            boolBuilder.appendNull();
                        }
                    }
                }
                return boolBuilder.build();

            case "datetime":
            case "date":
                // Datetime stored as epoch milliseconds in LongBlock
                LongBlock.Builder dateBuilder = blockFactory.newLongBlockBuilder(data.size());
                for (Object value : data) {
                    if (value == null || value.toString().trim().isEmpty()) {
                        dateBuilder.appendNull();
                    } else {
                        try {
                            String dateStr = value.toString().trim();
                            // Parse ISO-8601 format or epoch milliseconds
                            long epochMillis;
                            if (dateStr.matches("\\d+")) {
                                // Already epoch milliseconds
                                epochMillis = Long.parseLong(dateStr);
                            } else {
                                // Parse ISO-8601 date string
                                epochMillis = java.time.Instant.parse(dateStr).toEpochMilli();
                            }
                            dateBuilder.appendLong(epochMillis);
                        } catch (Exception e) {
                            // If parsing fails, append null
                            dateBuilder.appendNull();
                        }
                    }
                }
                return dateBuilder.build();

            default:
                // Default to BytesRef for keyword/text types
                BytesRefBlock.Builder bytesBuilder = blockFactory.newBytesRefBlockBuilder(data.size());
                for (Object value : data) {
                    if (value == null || value.toString().trim().isEmpty()) {
                        bytesBuilder.appendNull();
                    } else {
                        bytesBuilder.appendBytesRef(new org.apache.lucene.util.BytesRef(value.toString()));
                    }
                }
                return bytesBuilder.build();
        }
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
            s3ClientService.close();
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
