package com.mtcc.oracle.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class DatasetRecord {
    private String category;
    private String question;
    private String answer;
}
