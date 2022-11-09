/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.compute.data;

import org.apache.lucene.util.BytesRef;

import java.util.BitSet;

public class ConstantStringBlock extends Block {

    private final String value;

    public ConstantStringBlock(String value, int positionCount) {
        super(positionCount);
        this.value = value;
    }

    public ConstantStringBlock(String value, int positionCount, BitSet nulls) {
        super(positionCount, nulls);
        this.value = value;
    }

    @Override
    public BytesRef getBytesRef(int position, BytesRef spare) {
        assert assertPosition(position);
        return new BytesRef(value);
    }

    @Override
    public Object getObject(int position) {
        assert assertPosition(position);
        assert isNull(position) == false;
        return isNull(position) ? null : value;
    }

    @Override
    public Block filter(int... positions) {
        return new ConstantStringBlock(value, positions.length);
    }

    @Override
    public String toString() {
        return "ConstantStringBlock{positions=" + getPositionCount() + "}";
    }
}
