package com.mtcc.oracle.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Dataset {
    private String datasetName;
    private String datasetDescription;
    private List<DatasetRecord> records;
}
