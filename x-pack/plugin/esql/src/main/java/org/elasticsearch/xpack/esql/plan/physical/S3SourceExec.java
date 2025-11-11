/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.esql.plan.physical;

import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.xpack.esql.core.expression.Attribute;
import org.elasticsearch.xpack.esql.core.expression.Expression;
import org.elasticsearch.xpack.esql.core.tree.NodeInfo;
import org.elasticsearch.xpack.esql.core.tree.NodeUtils;
import org.elasticsearch.xpack.esql.core.tree.Source;

import java.io.IOException;
import java.util.List;
import java.util.Objects;

/**
 * Physical execution plan node for reading from S3.
 * Similar to EsQueryExec but for S3 sources.
 */
public class S3SourceExec extends LeafExec implements EstimatesRowSize {
    private final String s3Uri;
    private final List<Attribute> attrs;
    private final String format; // parquet, csv, json
    private final Expression filter; // Pushed-down predicates
    private final List<Attribute> projections; // Column pruning
    private final Integer estimatedRowSize;

    public S3SourceExec(
        Source source,
        String s3Uri,
        List<Attribute> attrs,
        String format,
        Expression filter,
        List<Attribute> projections,
        Integer estimatedRowSize
    ) {
        super(source);
        this.s3Uri = s3Uri;
        this.attrs = attrs;
        this.format = format;
        this.filter = filter;
        this.projections = projections;
        this.estimatedRowSize = estimatedRowSize;
    }

    public S3SourceExec(Source source, String s3Uri, List<Attribute> attrs, String format) {
        this(source, s3Uri, attrs, format, null, attrs, null);
    }

    @Override
    public void writeTo(StreamOutput out) throws IOException {
        out.writeString(s3Uri);
        out.writeNamedWriteableCollection(attrs);
        out.writeString(format);
        out.writeOptionalWriteable(filter);
        out.writeNamedWriteableCollection(projections);
        out.writeOptionalVInt(estimatedRowSize);
    }

    @Override
    public String getWriteableName() {
        return "S3SourceExec";
    }

    @Override
    protected NodeInfo<S3SourceExec> info() {
        return NodeInfo.create(this, S3SourceExec::new, s3Uri, attrs, format, filter, projections, estimatedRowSize);
    }

    public String s3Uri() {
        return s3Uri;
    }

    public String format() {
        return format;
    }

    @Override
    public List<Attribute> output() {
        return attrs;
    }

    public Expression filter() {
        return filter;
    }

    public List<Attribute> projections() {
        return projections;
    }

    public Integer estimatedRowSize() {
        return estimatedRowSize;
    }

    @Override
    public PhysicalPlan estimateRowSize(State state) {
        // Simple estimation based on number of attributes
        // For S3, we assume each attribute takes some average space
        int size = 0;
        for (Attribute attr : attrs) {
            // Rough estimate: 8 bytes for primitives, 32 for strings
            size += switch (attr.dataType().typeName()) {
                case "integer", "long", "double", "boolean", "date" -> 8;
                case "keyword", "text" -> 32;
                default -> 16;
            };
        }
        return Objects.equals(this.estimatedRowSize, size)
            ? this
            : new S3SourceExec(source(), s3Uri, attrs, format, filter, projections, size);
    }

    public S3SourceExec withFilter(Expression filter) {
        return Objects.equals(this.filter, filter)
            ? this
            : new S3SourceExec(source(), s3Uri, attrs, format, filter, projections, estimatedRowSize);
    }

    public S3SourceExec withProjections(List<Attribute> projections) {
        return Objects.equals(this.projections, projections)
            ? this
            : new S3SourceExec(source(), s3Uri, attrs, format, filter, projections, estimatedRowSize);
    }

    @Override
    public int hashCode() {
        return Objects.hash(s3Uri, attrs, format, filter, projections, estimatedRowSize);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }

        if (obj == null || getClass() != obj.getClass()) {
            return false;
        }

        S3SourceExec other = (S3SourceExec) obj;
        return Objects.equals(s3Uri, other.s3Uri)
            && Objects.equals(attrs, other.attrs)
            && Objects.equals(format, other.format)
            && Objects.equals(filter, other.filter)
            && Objects.equals(projections, other.projections)
            && Objects.equals(estimatedRowSize, other.estimatedRowSize);
    }

    @Override
    public String nodeString() {
        return nodeName()
            + "["
            + s3Uri
            + "], format["
            + format
            + "], "
            + NodeUtils.limitedToString(attrs)
            + ", filter["
            + (filter != null ? filter.toString() : "")
            + "] estimatedRowSize["
            + estimatedRowSize
            + "]";
    }
}
