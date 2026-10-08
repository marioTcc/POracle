package com.mtcc.rag.util;

import lombok.Builder;
import lombok.Getter;

import java.util.List;

@Builder
@Getter
public class KeywordVector {

    private List<Integer> indices;
    private List<Float> values;

    public boolean isEmpty() {
        return indices.isEmpty();
    }
}
