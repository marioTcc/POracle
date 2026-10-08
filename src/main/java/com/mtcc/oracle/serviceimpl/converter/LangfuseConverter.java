package com.mtcc.oracle.serviceimpl.converter;

import com.langfuse.client.resources.commons.types.DatasetItem;
import com.mtcc.common.converter.Converter;
import com.mtcc.oracle.entity.EvaluationItem;
import lombok.Getter;

import java.util.Map;
import java.util.function.Function;

public class LangfuseConverter {

    private static final String CATEGORY_FIELD = "category";

    private LangfuseConverter() {}

    @Getter
    private static final DatasetItemToEvaluationItemConverter datasetItemToEvaluationItemConverter = new DatasetItemToEvaluationItemConverter();

    public static class DatasetItemToEvaluationItemConverter implements Converter<DatasetItem, EvaluationItem> {

        @Override
        public Function<DatasetItem, EvaluationItem> convert() {
            return this::toEvaluationItem;
        }

        private EvaluationItem toEvaluationItem(final DatasetItem item) {
            return EvaluationItem.builder()
                    .id(item.getId())
                    .datasetId(item.getDatasetId())
                    .input(asText(item.getInput()))
                    .expectedOutput(asText(item.getExpectedOutput()))
                    .category(item.getMetadata() instanceof Map<?, ?> metadata ? asText(metadata.get(CATEGORY_FIELD)) : null)
                    .build();
        }

        private String asText(final Object value) {
            return value == null ? null : value.toString();
        }
    }
}
