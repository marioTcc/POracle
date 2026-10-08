package com.mtcc.common.security;

import lombok.Builder;
import lombok.Getter;

@Builder
@Getter
public class Redaction {

    private String text;
    private int count;
}
