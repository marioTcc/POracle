package com.mtcc.common.converter;

import java.util.function.Function;

public interface Converter<I, O> {
    Function<I, O> convert();
}