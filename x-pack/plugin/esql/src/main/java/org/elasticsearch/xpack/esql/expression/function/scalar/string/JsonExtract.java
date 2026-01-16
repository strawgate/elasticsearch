/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.esql.expression.function.scalar.string;

import com.jayway.jsonpath.Configuration;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.Option;
import com.jayway.jsonpath.PathNotFoundException;

import org.apache.lucene.util.BytesRef;
import org.elasticsearch.TransportVersion;
import org.elasticsearch.common.io.stream.NamedWriteableRegistry;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.common.lucene.BytesRefs;
import org.elasticsearch.compute.ann.Evaluator;
import org.elasticsearch.compute.ann.Fixed;
import org.elasticsearch.compute.operator.EvalOperator.ExpressionEvaluator;
import org.elasticsearch.xpack.esql.core.expression.Expression;
import org.elasticsearch.xpack.esql.core.tree.NodeInfo;
import org.elasticsearch.xpack.esql.core.tree.Source;
import org.elasticsearch.xpack.esql.core.type.DataType;
import org.elasticsearch.xpack.esql.expression.function.Example;
import org.elasticsearch.xpack.esql.expression.function.FunctionInfo;
import org.elasticsearch.xpack.esql.expression.function.Param;
import org.elasticsearch.xpack.esql.expression.function.scalar.EsqlScalarFunction;
import org.elasticsearch.xpack.esql.io.stream.PlanStreamInput;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

import static org.elasticsearch.xpack.esql.core.expression.TypeResolutions.ParamOrdinal.FIRST;
import static org.elasticsearch.xpack.esql.core.expression.TypeResolutions.ParamOrdinal.SECOND;
import static org.elasticsearch.xpack.esql.core.expression.TypeResolutions.isString;

/**
 * Extracts a scalar value from a JSON string using JSONPath syntax.
 * This is the first dedicated JSON parsing capability in ESQL.
 */
public class JsonExtract extends EsqlScalarFunction {
    public static final NamedWriteableRegistry.Entry ENTRY = new NamedWriteableRegistry.Entry(
        Expression.class,
        "JsonExtract",
        JsonExtract::new
    );

    private static final TransportVersion ESQL_SERIALIZE_SOURCE_FUNCTIONS_WARNINGS = TransportVersion.fromName(
        "esql_serialize_source_functions_warnings"
    );

    private final Expression json;
    private final Expression path;

    // Static configuration for JsonPath - return null for missing paths
    private static final Configuration JSON_PATH_CONFIG = Configuration.builder()
        .options(Option.DEFAULT_PATH_LEAF_TO_NULL)
        .options(Option.SUPPRESS_EXCEPTIONS)
        .build();

    @FunctionInfo(
        returnType = "keyword",
        description = """
            Extracts a scalar value from a JSON string using JSONPath syntax.
            Returns the value at the specified path as a keyword, or null if
            the path doesn't exist, the JSON is malformed, or the value is
            not a scalar (object/array returns null).""",
        examples = {
            @Example(
                file = "json-extract",
                tag = "basic-extraction",
                description = "Extract a simple field from a JSON string:"
            ),
            @Example(file = "json-extract", tag = "nested-path", description = "Extract a nested field:"),
            @Example(file = "json-extract", tag = "array-access", description = "Extract an array element by index:") }
    )
    public JsonExtract(
        Source source,
        @Param(name = "json", type = { "keyword", "text" }, description = "JSON string to extract from.") Expression json,
        @Param(
            name = "path",
            type = { "keyword", "text" },
            description = "JSONPath expression (e.g., '$.foo.bar', '$.arr[0]')."
        ) Expression path
    ) {
        super(source, Arrays.asList(json, path));
        this.json = json;
        this.path = path;
    }

    private JsonExtract(StreamInput in) throws IOException {
        this(
            in.getTransportVersion().supports(ESQL_SERIALIZE_SOURCE_FUNCTIONS_WARNINGS)
                ? Source.readFrom((PlanStreamInput) in)
                : Source.EMPTY,
            in.readNamedWriteable(Expression.class),
            in.readNamedWriteable(Expression.class)
        );
    }

    @Override
    public void writeTo(StreamOutput out) throws IOException {
        if (out.getTransportVersion().supports(ESQL_SERIALIZE_SOURCE_FUNCTIONS_WARNINGS)) {
            source().writeTo(out);
        }
        out.writeNamedWriteable(json);
        out.writeNamedWriteable(path);
    }

    @Override
    public String getWriteableName() {
        return ENTRY.name;
    }

    @Override
    protected TypeResolution resolveType() {
        if (childrenResolved() == false) {
            return new TypeResolution("Unresolved children");
        }

        TypeResolution resolution = isString(json, sourceText(), FIRST);
        if (resolution.unresolved()) {
            return resolution;
        }

        return isString(path, sourceText(), SECOND);
    }

    @Override
    public DataType dataType() {
        return DataType.KEYWORD;
    }

    @Override
    public boolean foldable() {
        return json.foldable() && path.foldable();
    }

    @Override
    public Expression replaceChildren(List<Expression> newChildren) {
        return new JsonExtract(source(), newChildren.get(0), newChildren.get(1));
    }

    @Override
    protected NodeInfo<? extends Expression> info() {
        return NodeInfo.create(this, JsonExtract::new, json, path);
    }

    /**
     * Optimized version when path is a compile-time constant.
     * Avoids re-parsing the JSONPath expression for each row.
     */
    @Evaluator(extraName = "Constant")
    static BytesRef process(BytesRef jsonBytes, @Fixed JsonPath compiledPath) {
        if (jsonBytes == null || compiledPath == null) {
            return null;
        }

        String jsonStr = jsonBytes.utf8ToString();
        return extractWithCompiledPath(jsonStr, compiledPath);
    }

    /**
     * Core extraction logic when both json and path are dynamic.
     * Returns null for: malformed JSON, missing path, non-scalar values.
     */
    @Evaluator
    static BytesRef process(BytesRef jsonBytes, BytesRef pathBytes) {
        if (jsonBytes == null || pathBytes == null) {
            return null;
        }

        String jsonStr = jsonBytes.utf8ToString();
        String pathStr = pathBytes.utf8ToString();

        try {
            JsonPath compiledPath = JsonPath.compile(pathStr);
            return extractWithCompiledPath(jsonStr, compiledPath);
        } catch (Exception e) {
            // Invalid path syntax or other errors
            return null;
        }
    }

    private static BytesRef extractWithCompiledPath(String json, JsonPath path) {
        try {
            Object result = path.read(json, JSON_PATH_CONFIG);

            if (result == null) {
                return null;
            }

            // Only return scalar values
            if (result instanceof String s) {
                return new BytesRef(s);
            } else if (result instanceof Number n) {
                return new BytesRef(n.toString());
            } else if (result instanceof Boolean b) {
                return new BytesRef(b.toString());
            } else {
                // Arrays and objects return null (use JSON_QUERY for those in future)
                return null;
            }
        } catch (PathNotFoundException e) {
            return null;
        } catch (Exception e) {
            // Malformed JSON or other parsing errors
            return null;
        }
    }

    @Override
    public ExpressionEvaluator.Factory toEvaluator(ToEvaluator toEvaluator) {
        var jsonEval = toEvaluator.apply(json);
        var pathEval = toEvaluator.apply(path);

        // Optimization: if path is constant, compile it once
        if (path.foldable() && path.dataType() == DataType.KEYWORD) {
            try {
                String pathStr = BytesRefs.toString(path.fold(toEvaluator.foldCtx()));
                JsonPath compiledPath = JsonPath.compile(pathStr);
                return new JsonExtractConstantEvaluator.Factory(source(), jsonEval, compiledPath);
            } catch (Exception e) {
                // Invalid path - fall back to regular evaluator which will return null
            }
        }

        return new JsonExtractEvaluator.Factory(source(), jsonEval, pathEval);
    }

    public Expression json() {
        return json;
    }

    public Expression path() {
        return path;
    }
}
