/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */
package org.elasticsearch.xpack.esql.plan.logical;

import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.xpack.esql.core.capabilities.Unresolvable;
import org.elasticsearch.xpack.esql.core.expression.Attribute;
import org.elasticsearch.xpack.esql.core.tree.NodeInfo;
import org.elasticsearch.xpack.esql.core.tree.Source;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Unresolved S3 relation - used during parsing before schema is discovered.
 * Similar to UnresolvedRelation for Elasticsearch indices.
 */
public class UnresolvedS3Relation extends LeafPlan implements Unresolvable {
    private final String s3Uri;
    private final String unresolvedMsg;

    public UnresolvedS3Relation(Source source, String s3Uri, String unresolvedMessage) {
        super(source);
        this.s3Uri = s3Uri;
        this.unresolvedMsg = unresolvedMessage == null ? "Unknown S3 source [" + s3Uri + "]" : unresolvedMessage;
    }

    public UnresolvedS3Relation(Source source, String s3Uri) {
        this(source, s3Uri, null);
    }

    public String s3Uri() {
        return s3Uri;
    }

    @Override
    public boolean resolved() {
        return false;
    }

    @Override
    public boolean expressionsResolved() {
        return false;
    }

    @Override
    public List<Attribute> output() {
        return Collections.emptyList();
    }

    @Override
    public String unresolvedMessage() {
        return unresolvedMsg;
    }

    @Override
    public void writeTo(StreamOutput out) {
        throw new UnsupportedOperationException("not serialized");
    }

    @Override
    public String getWriteableName() {
        throw new UnsupportedOperationException("not serialized");
    }

    @Override
    protected NodeInfo<UnresolvedS3Relation> info() {
        return NodeInfo.create(this, UnresolvedS3Relation::new, s3Uri, unresolvedMsg);
    }

    @Override
    public int hashCode() {
        return Objects.hash(s3Uri, unresolvedMsg);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }

        if (obj == null || getClass() != obj.getClass()) {
            return false;
        }

        UnresolvedS3Relation other = (UnresolvedS3Relation) obj;
        return Objects.equals(s3Uri, other.s3Uri) && Objects.equals(unresolvedMsg, other.unresolvedMsg);
    }

    @Override
    public String toString() {
        return UNRESOLVED_PREFIX + s3Uri;
    }
}
