/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.esql.expression.function.scalar.string;

import com.carrotsearch.randomizedtesting.annotations.Name;
import com.carrotsearch.randomizedtesting.annotations.ParametersFactory;

import org.apache.lucene.util.BytesRef;
import org.elasticsearch.xpack.esql.core.expression.Expression;
import org.elasticsearch.xpack.esql.core.tree.Source;
import org.elasticsearch.xpack.esql.core.type.DataType;
import org.elasticsearch.xpack.esql.expression.function.AbstractScalarFunctionTestCase;
import org.elasticsearch.xpack.esql.expression.function.TestCaseSupplier;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;

public class JsonExtractTests extends AbstractScalarFunctionTestCase {
    public JsonExtractTests(@Name("TestCase") Supplier<TestCaseSupplier.TestCase> testCaseSupplier) {
        this.testCase = testCaseSupplier.get();
    }

    @ParametersFactory
    public static Iterable<Object[]> parameters() {
        List<TestCaseSupplier> suppliers = new ArrayList<>();

        // Basic string extraction
        suppliers.add(fixedCase("extract simple string", "{\"name\":\"elasticsearch\"}", "$.name", "elasticsearch"));

        // Nested path extraction
        suppliers.add(
            fixedCase("extract nested path", "{\"user\":{\"address\":{\"city\":\"Amsterdam\"}}}", "$.user.address.city", "Amsterdam")
        );

        // Array element extraction
        suppliers.add(
            fixedCase("extract array element", "{\"tags\":[\"search\",\"analytics\",\"observability\"]}", "$.tags[1]", "analytics")
        );

        // First array element
        suppliers.add(fixedCase("extract first array element", "{\"tags\":[\"a\",\"b\",\"c\"]}", "$.tags[0]", "a"));

        // Number extraction (returned as string)
        suppliers.add(fixedCase("extract number as string", "{\"count\":42,\"price\":19.99}", "$.price", "19.99"));

        suppliers.add(fixedCase("extract integer as string", "{\"count\":42}", "$.count", "42"));

        // Boolean extraction
        suppliers.add(fixedCase("extract boolean true", "{\"active\":true}", "$.active", "true"));

        suppliers.add(fixedCase("extract boolean false", "{\"enabled\":false}", "$.enabled", "false"));

        // Missing path returns null
        suppliers.add(fixedNullCase("missing path returns null", "{\"name\":\"test\"}", "$.nonexistent"));

        // Malformed JSON returns null
        suppliers.add(fixedNullCase("malformed json returns null", "{invalid json}", "$.name"));

        // Invalid path returns null
        suppliers.add(fixedNullCase("invalid path returns null", "{\"name\":\"test\"}", "$[invalid"));

        // Object returns null (not a scalar)
        suppliers.add(fixedNullCase("object returns null", "{\"user\":{\"name\":\"john\"}}", "$.user"));

        // Array returns null (not a scalar)
        suppliers.add(fixedNullCase("array returns null", "{\"tags\":[\"a\",\"b\"]}", "$.tags"));

        // Null JSON returns null
        suppliers.add(new TestCaseSupplier("null json returns null", List.of(DataType.KEYWORD, DataType.KEYWORD), () -> {
            return new TestCaseSupplier.TestCase(
                List.of(
                    new TestCaseSupplier.TypedData(null, DataType.KEYWORD, "json"),
                    new TestCaseSupplier.TypedData(new BytesRef("$.name"), DataType.KEYWORD, "path")
                ),
                "JsonExtractEvaluator[jsonBytes=Attribute[channel=0], pathBytes=Attribute[channel=1]]",
                DataType.KEYWORD,
                nullValue()
            );
        }));

        // Null path returns null
        suppliers.add(new TestCaseSupplier("null path returns null", List.of(DataType.KEYWORD, DataType.KEYWORD), () -> {
            return new TestCaseSupplier.TestCase(
                List.of(
                    new TestCaseSupplier.TypedData(new BytesRef("{\"name\":\"test\"}"), DataType.KEYWORD, "json"),
                    new TestCaseSupplier.TypedData(null, DataType.KEYWORD, "path")
                ),
                "JsonExtractEvaluator[jsonBytes=Attribute[channel=0], pathBytes=Attribute[channel=1]]",
                DataType.KEYWORD,
                nullValue()
            );
        }));

        // Test with TEXT type
        suppliers.add(
            new TestCaseSupplier("extract with text type", List.of(DataType.TEXT, DataType.TEXT), () -> testCase(
                DataType.TEXT,
                DataType.TEXT,
                "{\"name\":\"test\"}",
                "$.name",
                "test"
            ))
        );

        // Test with mixed types
        suppliers.add(
            new TestCaseSupplier("extract with mixed types", List.of(DataType.KEYWORD, DataType.TEXT), () -> testCase(
                DataType.KEYWORD,
                DataType.TEXT,
                "{\"name\":\"test\"}",
                "$.name",
                "test"
            ))
        );

        // Complex nested structure
        suppliers.add(
            fixedCase(
                "extract from complex nested structure",
                "{\"data\":{\"items\":[{\"id\":1,\"value\":\"first\"},{\"id\":2,\"value\":\"second\"}]}}",
                "$.data.items[1].value",
                "second"
            )
        );

        // JSON with spaces
        suppliers.add(fixedCase("extract from json with spaces", "{ \"name\" : \"test\" }", "$.name", "test"));

        // Unicode in JSON
        suppliers.add(fixedCase("extract unicode string", "{\"greeting\":\"hello \\ud83d\\udc4b world\"}", "$.greeting", "hello 👋 world"));

        // Escaped characters
        suppliers.add(fixedCase("extract string with escaped quotes", "{\"msg\":\"hello \\\"world\\\"\"}", "$.msg", "hello \"world\""));

        return parameterSuppliersFromTypedDataWithDefaultChecks(false, suppliers);
    }

    private static TestCaseSupplier fixedCase(String name, String json, String path, String expectedResult) {
        return new TestCaseSupplier(
            name,
            List.of(DataType.KEYWORD, DataType.KEYWORD),
            () -> testCase(DataType.KEYWORD, DataType.KEYWORD, json, path, expectedResult)
        );
    }

    private static TestCaseSupplier fixedNullCase(String name, String json, String path) {
        // Determine the specific exception message based on the test case
        String exceptionMessage;
        if (json.contains("{invalid")) {
            exceptionMessage = "Failed to parse JSON or extract value";
        } else if (path.contains("[invalid")) {
            exceptionMessage = "Invalid JSONPath syntax: " + path;
        } else {
            exceptionMessage = "Missing path in JSON: " + path;
        }
        
        return new TestCaseSupplier(name, List.of(DataType.KEYWORD, DataType.KEYWORD), () -> {
            return new TestCaseSupplier.TestCase(
                List.of(
                    new TestCaseSupplier.TypedData(new BytesRef(json), DataType.KEYWORD, "json"),
                    new TestCaseSupplier.TypedData(new BytesRef(path), DataType.KEYWORD, "path")
                ),
                "JsonExtractEvaluator[jsonBytes=Attribute[channel=0], pathBytes=Attribute[channel=1]]",
                DataType.KEYWORD,
                nullValue()
            ).withWarning("Line 1:1: evaluation of [source] failed, treating result as null. Only first 20 failures recorded.")
                .withWarning("Line 1:1: org.elasticsearch.xpack.esql.expression.function.scalar.string.JsonExtract$JsonExtractException: " + exceptionMessage);
        });
    }

    private static TestCaseSupplier.TestCase testCase(
        DataType jsonType,
        DataType pathType,
        String json,
        String path,
        String expectedResult
    ) {
        return new TestCaseSupplier.TestCase(
            List.of(
                new TestCaseSupplier.TypedData(new BytesRef(json), jsonType, "json"),
                new TestCaseSupplier.TypedData(new BytesRef(path), pathType, "path")
            ),
            "JsonExtractEvaluator[jsonBytes=Attribute[channel=0], pathBytes=Attribute[channel=1]]",
            DataType.KEYWORD,
            equalTo(new BytesRef(expectedResult))
        );
    }

    @Override
    protected Expression build(Source source, List<Expression> args) {
        return new JsonExtract(source, args.get(0), args.get(1));
    }

    @Override
    protected Expression serializeDeserializeExpression(Expression expression) {
        // TODO: This function doesn't serialize the Source, and must be fixed.
        return expression;
    }
}
