// Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
// or more contributor license agreements. Licensed under the Elastic License
// 2.0; you may not use this file except in compliance with the Elastic License
// 2.0.
package org.elasticsearch.xpack.esql.expression.function.scalar.string;

import com.jayway.jsonpath.JsonPath;
import java.lang.IllegalArgumentException;
import java.lang.Override;
import java.lang.String;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.RamUsageEstimator;
import org.elasticsearch.compute.data.Block;
import org.elasticsearch.compute.data.BytesRefBlock;
import org.elasticsearch.compute.data.BytesRefVector;
import org.elasticsearch.compute.data.Page;
import org.elasticsearch.compute.operator.DriverContext;
import org.elasticsearch.compute.operator.EvalOperator;
import org.elasticsearch.compute.operator.Warnings;
import org.elasticsearch.core.Releasables;
import org.elasticsearch.xpack.esql.core.tree.Source;

/**
 * {@link EvalOperator.ExpressionEvaluator} implementation for {@link JsonExtract}.
 * This class is generated. Edit {@code EvaluatorImplementer} instead.
 */
public final class JsonExtractConstantEvaluator implements EvalOperator.ExpressionEvaluator {
  private static final long BASE_RAM_BYTES_USED = RamUsageEstimator.shallowSizeOfInstance(JsonExtractConstantEvaluator.class);

  private final Source source;

  private final EvalOperator.ExpressionEvaluator jsonBytes;

  private final JsonPath compiledPath;

  private final DriverContext driverContext;

  private Warnings warnings;

  public JsonExtractConstantEvaluator(Source source, EvalOperator.ExpressionEvaluator jsonBytes,
      JsonPath compiledPath, DriverContext driverContext) {
    this.source = source;
    this.jsonBytes = jsonBytes;
    this.compiledPath = compiledPath;
    this.driverContext = driverContext;
  }

  @Override
  public Block eval(Page page) {
    try (BytesRefBlock jsonBytesBlock = (BytesRefBlock) jsonBytes.eval(page)) {
      BytesRefVector jsonBytesVector = jsonBytesBlock.asVector();
      if (jsonBytesVector == null) {
        return eval(page.getPositionCount(), jsonBytesBlock);
      }
      return eval(page.getPositionCount(), jsonBytesVector);
    }
  }

  @Override
  public long baseRamBytesUsed() {
    long baseRamBytesUsed = BASE_RAM_BYTES_USED;
    baseRamBytesUsed += jsonBytes.baseRamBytesUsed();
    return baseRamBytesUsed;
  }

  public BytesRefBlock eval(int positionCount, BytesRefBlock jsonBytesBlock) {
    try(BytesRefBlock.Builder result = driverContext.blockFactory().newBytesRefBlockBuilder(positionCount)) {
      BytesRef jsonBytesScratch = new BytesRef();
      position: for (int p = 0; p < positionCount; p++) {
        switch (jsonBytesBlock.getValueCount(p)) {
          case 0:
              result.appendNull();
              continue position;
          case 1:
              break;
          default:
              warnings().registerException(new IllegalArgumentException("single-value function encountered multi-value"));
              result.appendNull();
              continue position;
        }
        BytesRef jsonBytes = jsonBytesBlock.getBytesRef(jsonBytesBlock.getFirstValueIndex(p), jsonBytesScratch);
        try {
          result.appendBytesRef(JsonExtract.process(jsonBytes, this.compiledPath));
        } catch (JsonExtract.JsonExtractException e) {
          warnings().registerException(e);
          result.appendNull();
        }
      }
      return result.build();
    }
  }

  public BytesRefBlock eval(int positionCount, BytesRefVector jsonBytesVector) {
    try(BytesRefBlock.Builder result = driverContext.blockFactory().newBytesRefBlockBuilder(positionCount)) {
      BytesRef jsonBytesScratch = new BytesRef();
      position: for (int p = 0; p < positionCount; p++) {
        BytesRef jsonBytes = jsonBytesVector.getBytesRef(p, jsonBytesScratch);
        try {
          result.appendBytesRef(JsonExtract.process(jsonBytes, this.compiledPath));
        } catch (JsonExtract.JsonExtractException e) {
          warnings().registerException(e);
          result.appendNull();
        }
      }
      return result.build();
    }
  }

  @Override
  public String toString() {
    return "JsonExtractConstantEvaluator[" + "jsonBytes=" + jsonBytes + ", compiledPath=" + compiledPath + "]";
  }

  @Override
  public void close() {
    Releasables.closeExpectNoException(jsonBytes);
  }

  private Warnings warnings() {
    if (warnings == null) {
      this.warnings = Warnings.createWarnings(
              driverContext.warningsMode(),
              source.source().getLineNumber(),
              source.source().getColumnNumber(),
              source.text()
          );
    }
    return warnings;
  }

  static class Factory implements EvalOperator.ExpressionEvaluator.Factory {
    private final Source source;

    private final EvalOperator.ExpressionEvaluator.Factory jsonBytes;

    private final JsonPath compiledPath;

    public Factory(Source source, EvalOperator.ExpressionEvaluator.Factory jsonBytes,
        JsonPath compiledPath) {
      this.source = source;
      this.jsonBytes = jsonBytes;
      this.compiledPath = compiledPath;
    }

    @Override
    public JsonExtractConstantEvaluator get(DriverContext context) {
      return new JsonExtractConstantEvaluator(source, jsonBytes.get(context), compiledPath, context);
    }

    @Override
    public String toString() {
      return "JsonExtractConstantEvaluator[" + "jsonBytes=" + jsonBytes + ", compiledPath=" + compiledPath + "]";
    }
  }
}
