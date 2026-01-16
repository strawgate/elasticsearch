// Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
// or more contributor license agreements. Licensed under the Elastic License
// 2.0; you may not use this file except in compliance with the Elastic License
// 2.0.
package org.elasticsearch.xpack.esql.expression.function.scalar.string;

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
public final class JsonExtractEvaluator implements EvalOperator.ExpressionEvaluator {
  private static final long BASE_RAM_BYTES_USED = RamUsageEstimator.shallowSizeOfInstance(JsonExtractEvaluator.class);

  private final Source source;

  private final EvalOperator.ExpressionEvaluator jsonBytes;

  private final EvalOperator.ExpressionEvaluator pathBytes;

  private final DriverContext driverContext;

  private Warnings warnings;

  public JsonExtractEvaluator(Source source, EvalOperator.ExpressionEvaluator jsonBytes,
      EvalOperator.ExpressionEvaluator pathBytes, DriverContext driverContext) {
    this.source = source;
    this.jsonBytes = jsonBytes;
    this.pathBytes = pathBytes;
    this.driverContext = driverContext;
  }

  @Override
  public Block eval(Page page) {
    try (BytesRefBlock jsonBytesBlock = (BytesRefBlock) jsonBytes.eval(page)) {
      try (BytesRefBlock pathBytesBlock = (BytesRefBlock) pathBytes.eval(page)) {
        BytesRefVector jsonBytesVector = jsonBytesBlock.asVector();
        if (jsonBytesVector == null) {
          return eval(page.getPositionCount(), jsonBytesBlock, pathBytesBlock);
        }
        BytesRefVector pathBytesVector = pathBytesBlock.asVector();
        if (pathBytesVector == null) {
          return eval(page.getPositionCount(), jsonBytesBlock, pathBytesBlock);
        }
        return eval(page.getPositionCount(), jsonBytesVector, pathBytesVector);
      }
    }
  }

  @Override
  public long baseRamBytesUsed() {
    long baseRamBytesUsed = BASE_RAM_BYTES_USED;
    baseRamBytesUsed += jsonBytes.baseRamBytesUsed();
    baseRamBytesUsed += pathBytes.baseRamBytesUsed();
    return baseRamBytesUsed;
  }

  public BytesRefBlock eval(int positionCount, BytesRefBlock jsonBytesBlock,
      BytesRefBlock pathBytesBlock) {
    try(BytesRefBlock.Builder result = driverContext.blockFactory().newBytesRefBlockBuilder(positionCount)) {
      BytesRef jsonBytesScratch = new BytesRef();
      BytesRef pathBytesScratch = new BytesRef();
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
        switch (pathBytesBlock.getValueCount(p)) {
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
        BytesRef pathBytes = pathBytesBlock.getBytesRef(pathBytesBlock.getFirstValueIndex(p), pathBytesScratch);
        try {
          result.appendBytesRef(JsonExtract.process(jsonBytes, pathBytes));
        } catch (JsonExtract.JsonExtractException e) {
          warnings().registerException(e);
          result.appendNull();
        }
      }
      return result.build();
    }
  }

  public BytesRefBlock eval(int positionCount, BytesRefVector jsonBytesVector,
      BytesRefVector pathBytesVector) {
    try(BytesRefBlock.Builder result = driverContext.blockFactory().newBytesRefBlockBuilder(positionCount)) {
      BytesRef jsonBytesScratch = new BytesRef();
      BytesRef pathBytesScratch = new BytesRef();
      position: for (int p = 0; p < positionCount; p++) {
        BytesRef jsonBytes = jsonBytesVector.getBytesRef(p, jsonBytesScratch);
        BytesRef pathBytes = pathBytesVector.getBytesRef(p, pathBytesScratch);
        try {
          result.appendBytesRef(JsonExtract.process(jsonBytes, pathBytes));
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
    return "JsonExtractEvaluator[" + "jsonBytes=" + jsonBytes + ", pathBytes=" + pathBytes + "]";
  }

  @Override
  public void close() {
    Releasables.closeExpectNoException(jsonBytes, pathBytes);
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

    private final EvalOperator.ExpressionEvaluator.Factory pathBytes;

    public Factory(Source source, EvalOperator.ExpressionEvaluator.Factory jsonBytes,
        EvalOperator.ExpressionEvaluator.Factory pathBytes) {
      this.source = source;
      this.jsonBytes = jsonBytes;
      this.pathBytes = pathBytes;
    }

    @Override
    public JsonExtractEvaluator get(DriverContext context) {
      return new JsonExtractEvaluator(source, jsonBytes.get(context), pathBytes.get(context), context);
    }

    @Override
    public String toString() {
      return "JsonExtractEvaluator[" + "jsonBytes=" + jsonBytes + ", pathBytes=" + pathBytes + "]";
    }
  }
}
