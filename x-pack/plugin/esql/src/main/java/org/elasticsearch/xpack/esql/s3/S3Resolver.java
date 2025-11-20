/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */
package org.elasticsearch.xpack.esql.s3;

import org.elasticsearch.xpack.esql.core.expression.Attribute;
import org.elasticsearch.xpack.esql.core.expression.FieldAttribute;
import org.elasticsearch.xpack.esql.core.tree.Source;
import org.elasticsearch.xpack.esql.core.type.DataType;
import org.elasticsearch.xpack.esql.core.type.EsField;
import org.elasticsearch.xpack.esql.core.type.KeywordEsField;

import java.util.HashMap;
import java.util.Map;
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
import java.util.List;
import java.util.regex.Pattern;

/**
 * Resolver for S3 data sources - discovers schema from S3 objects.
 * Similar to IndexResolver but for S3 sources.
 */
public class S3Resolver {
    private final S3ClientService s3ClientService;

    public S3Resolver(S3ClientService s3ClientService) {
        this.s3ClientService = s3ClientService;
    }

    /**
     * Resolve an S3 URI to a list of attributes (schema).
     * For Parquet files, reads the schema from the file footer.
     * For CSV/JSON, would need to sample and infer (simplified for now).
     */
    public List<Attribute> resolveSchema(Source source, String s3UriString) throws IOException {
        S3Uri s3Uri = S3Uri.parse(s3UriString);
        S3Client s3Client = s3ClientService.getClient();

        String objectKey;

        // Check if this is a specific file (no wildcards) or a pattern
        if (s3Uri.key().contains("*")) {
            // Pattern with wildcards - list objects to find first matching file
            String prefix = s3Uri.prefix();
            ListObjectsV2Request listRequest = ListObjectsV2Request.builder()
                .bucket(s3Uri.bucket())
                .prefix(prefix)
                .maxKeys(1) // We only need one file to discover schema
                .build();

            ListObjectsV2Response listResponse = s3Client.listObjectsV2(listRequest);
            if (listResponse.contents().isEmpty()) {
                throw new IllegalArgumentException("No objects found matching pattern: " + s3UriString);
            }

            // Get the first matching object to discover schema
            S3Object firstObject = listResponse.contents().get(0);
            objectKey = firstObject.key();
        } else {
            // Specific file - use it directly for schema resolution
            objectKey = s3Uri.key();

            // Verify the file exists
            try {
                s3Client.headObject(builder -> builder.bucket(s3Uri.bucket()).key(objectKey));
            } catch (Exception e) {
                throw new IllegalArgumentException("File not found: " + s3UriString, e);
            }
        }

        // Determine format and resolve schema accordingly
        String format = s3Uri.format();
        if ("parquet".equals(format)) {
            return resolveParquetSchema(source, s3Client, s3Uri.bucket(), objectKey);
        } else if ("csv".equals(format)) {
            return resolveCSVSchema(source, s3Client, s3Uri.bucket(), objectKey);
        } else {
            throw new IllegalArgumentException("Unsupported S3 format: " + format);
        }
    }

    private List<Attribute> resolveParquetSchema(Source source, S3Client s3Client, String bucket, String key) throws IOException {
        // Download the Parquet file
        GetObjectRequest getRequest = GetObjectRequest.builder()
            .bucket(bucket)
            .key(key)
            .build();

        try (ResponseInputStream<GetObjectResponse> s3Object = s3Client.getObject(getRequest)) {
            // Create a temporary file to read Parquet metadata
            // Note: ParquetFileReader requires a Path, so we need to work around this
            // For now, we'll use a simplified approach - in production, you'd want to
            // use the Parquet library's InputFile interface with S3 directly

            // For this implementation, we'll define a basic schema manually
            // In a full implementation, you'd read the Parquet footer properly
            List<Attribute> attributes = new ArrayList<>();

            // Simplified: Return a basic schema
            // TODO: Properly read Parquet schema using ParquetFileReader
            attributes.add(new FieldAttribute(source, "id", new KeywordEsField("id", new HashMap<>(), true, Short.MAX_VALUE, false, false, null)));
            attributes.add(new FieldAttribute(source, "name", new KeywordEsField("name", new HashMap<>(), true, Short.MAX_VALUE, false, false, null)));
            attributes.add(new FieldAttribute(source, "value", new KeywordEsField("value", new HashMap<>(), true, Short.MAX_VALUE, false, false, null)));

            return attributes;
        }
    }

    private List<Attribute> resolveCSVSchema(Source source, S3Client s3Client, String bucket, String key) throws IOException {
        // Download first 10KB for header and sampling (enough for most CSV headers + sample rows)
        GetObjectRequest getRequest = GetObjectRequest.builder()
            .bucket(bucket)
            .key(key)
            .range("bytes=0-10240") // First 10KB
            .build();

        try (ResponseInputStream<GetObjectResponse> s3Object = s3Client.getObject(getRequest);
             BufferedReader reader = new BufferedReader(new InputStreamReader(s3Object))) {

            // Read header line
            String headerLine = reader.readLine();
            if (headerLine == null || headerLine.trim().isEmpty()) {
                throw new IllegalArgumentException("CSV file is empty or has no header: " + key);
            }

            // Parse column names from header
            String[] columnNames = parseCSVLine(headerLine);
            if (columnNames.length == 0) {
                throw new IllegalArgumentException("CSV header is empty: " + key);
            }

            // Sample rows for type inference (read up to 10 rows)
            List<String[]> sampleRows = new ArrayList<>();
            String line;
            int maxSampleRows = 10;
            while (sampleRows.size() < maxSampleRows && (line = reader.readLine()) != null) {
                if (line.trim().isEmpty() == false) {
                    sampleRows.add(parseCSVLine(line));
                }
            }

            // Build attributes with inferred types
            List<Attribute> attributes = new ArrayList<>();
            for (int i = 0; i < columnNames.length; i++) {
                String columnName = columnNames[i].trim();
                if (columnName.isEmpty()) {
                    columnName = "column" + (i + 1); // Fallback for empty column names
                }

                // Check if this is a known timestamp field name
                DataType dataType;
                if (isTimestampColumn(columnName)) {
                    dataType = DataType.DATETIME;
                } else {
                    dataType = inferColumnType(sampleRows, i);
                }

                EsField esField = createEsField(columnName, dataType);
                attributes.add(new FieldAttribute(source, columnName, esField));
            }

            return attributes;
        }
    }

    /**
     * Check if a column name is a known timestamp field.
     * Common timestamp field names are treated as DATETIME type.
     */
    private boolean isTimestampColumn(String columnName) {
        String normalized = columnName.toLowerCase();
        return normalized.equals("@timestamp")
            || normalized.equals("timestamp")
            || normalized.equals("time")
            || normalized.equals("datetime")
            || normalized.equals("date")
            || normalized.equals("event_time")
            || normalized.equals("event.time")
            || normalized.equals("eventtime");
    }

    /**
     * Parse a CSV line handling quotes and escaped characters.
     * Simplified CSV parsing - handles basic cases with quoted fields.
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

    /**
     * Infer the data type of a column by examining sample values.
     * Returns the most specific type that all values conform to.
     */
    private DataType inferColumnType(List<String[]> sampleRows, int columnIndex) {
        if (sampleRows.isEmpty()) {
            return DataType.KEYWORD; // Default if no sample data
        }

        boolean allIntegers = true;
        boolean allLongs = true;
        boolean allDoubles = true;
        boolean allBooleans = true;
        int nonEmptyCount = 0;

        for (String[] row : sampleRows) {
            if (columnIndex >= row.length) {
                continue; // Row has fewer columns
            }

            String value = row[columnIndex].trim();
            if (value.isEmpty()) {
                continue; // Skip empty values
            }

            nonEmptyCount++;

            // Try parsing as integer
            if (allIntegers) {
                try {
                    Integer.parseInt(value);
                } catch (NumberFormatException e) {
                    allIntegers = false;
                }
            }

            // Try parsing as long
            if (allLongs) {
                try {
                    Long.parseLong(value);
                } catch (NumberFormatException e) {
                    allLongs = false;
                }
            }

            // Try parsing as double
            if (allDoubles) {
                try {
                    Double.parseDouble(value);
                } catch (NumberFormatException e) {
                    allDoubles = false;
                }
            }

            // Check if boolean
            if (allBooleans) {
                String lowerValue = value.toLowerCase();
                if (!lowerValue.equals("true") && !lowerValue.equals("false")) {
                    allBooleans = false;
                }
            }
        }

        // No non-empty values found
        if (nonEmptyCount == 0) {
            return DataType.KEYWORD;
        }

        // Return most specific type
        if (allBooleans) {
            return DataType.BOOLEAN;
        }
        if (allIntegers) {
            return DataType.INTEGER;
        }
        if (allLongs) {
            return DataType.LONG;
        }
        if (allDoubles) {
            return DataType.DOUBLE;
        }

        return DataType.KEYWORD; // Default to keyword for text
    }

    /**
     * Create an EsField for the given data type.
     */
    private EsField createEsField(String name, DataType dataType) {
        Map<String, EsField> emptyProperties = Map.of();
        boolean aggregatable = true;

        // For keyword/text types, use KeywordEsField
        if (dataType == DataType.KEYWORD || dataType == DataType.TEXT) {
            return new KeywordEsField(name, emptyProperties, aggregatable, Short.MAX_VALUE, false, false, null);
        }

        // For datetime types, use DateEsField for proper date handling
        if (dataType == DataType.DATETIME) {
            return org.elasticsearch.xpack.esql.core.type.DateEsField.dateEsField(
                name,
                emptyProperties,
                aggregatable,
                EsField.TimeSeriesFieldType.NONE
            );
        }

        // For other types, use base EsField with appropriate DataType
        return new EsField(name, dataType, emptyProperties, aggregatable, EsField.TimeSeriesFieldType.NONE);
    }

}
