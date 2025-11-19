/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */
package org.elasticsearch.xpack.esql.plan.logical;

import org.elasticsearch.common.io.stream.NamedWriteableRegistry;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.xpack.esql.core.expression.Attribute;
import org.elasticsearch.xpack.esql.core.tree.NodeInfo;
import org.elasticsearch.xpack.esql.core.tree.NodeUtils;
import org.elasticsearch.xpack.esql.core.tree.Source;
import org.elasticsearch.xpack.esql.io.stream.PlanStreamInput;

import java.io.IOException;
import java.util.List;
import java.util.Objects;

/**
 * Logical plan node representing a data source from S3.
 * This is similar to EsRelation but for S3 URIs.
 */
public class S3Relation extends LeafPlan {
    public static final NamedWriteableRegistry.Entry ENTRY = new NamedWriteableRegistry.Entry(
        LogicalPlan.class,
        "S3Relation",
        S3Relation::readFrom
    );

    private final String s3Uri;
    private final List<Attribute> attrs;
    private final String format; // parquet, csv, json
    private final boolean resolved;

    public S3Relation(Source source, String s3Uri, List<Attribute> attributes, String format, boolean resolved) {
        super(source);
        this.s3Uri = s3Uri;
        this.attrs = attributes;
        this.format = format;
        this.resolved = resolved;
    }

    public S3Relation(Source source, String s3Uri, List<Attribute> attributes, String format) {
        this(source, s3Uri, attributes, format, true);
    }

    private static S3Relation readFrom(StreamInput in) throws IOException {
        Source source = Source.readFrom((PlanStreamInput) in);
        String s3Uri = in.readString();
        List<Attribute> attributes = in.readNamedWriteableCollectionAsList(Attribute.class);
        String format = in.readString();
        boolean resolved = in.readBoolean();
        return new S3Relation(source, s3Uri, attributes, format, resolved);
    }

    @Override
    public void writeTo(StreamOutput out) throws IOException {
        Source.EMPTY.writeTo(out);
        out.writeString(s3Uri);
        out.writeNamedWriteableCollection(attrs);
        out.writeString(format);
        out.writeBoolean(resolved);
    }

    @Override
    public String getWriteableName() {
        return ENTRY.name;
    }

    @Override
    protected NodeInfo<S3Relation> info() {
        return NodeInfo.create(this, S3Relation::new, s3Uri, attrs, format, resolved);
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

    public boolean resolved() {
        return resolved;
    }

    @Override
    public boolean expressionsResolved() {
        // Similar to EsRelation, unresolved expressions are fine in S3Relation
        return true;
    }

    @Override
    public int hashCode() {
        return Objects.hash(s3Uri, attrs, format, resolved);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }

        if (obj == null || getClass() != obj.getClass()) {
            return false;
        }

        S3Relation other = (S3Relation) obj;
        return Objects.equals(s3Uri, other.s3Uri)
            && Objects.equals(attrs, other.attrs)
            && Objects.equals(format, other.format)
            && Objects.equals(resolved, other.resolved);
    }

    @Override
    public String nodeString() {
        return nodeName() + "[" + s3Uri + "][" + format + "]" + NodeUtils.limitedToString(attrs);
    }

    public S3Relation withAttributes(List<Attribute> newAttributes) {
        return new S3Relation(source(), s3Uri, newAttributes, format, resolved);
    }
}
