package com.mtcc.rag.util;

import org.apache.commons.lang3.StringUtils;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.LongToDoubleFunction;
import java.util.regex.MatchResult;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class KeywordVectorizer {

    private static final Pattern WORD_PATTERN = Pattern.compile("[\\p{L}\\p{N}_]+");
    private static final int MIN_TOKEN_CHARS = 2;
    private static final double TERM_SATURATION = 1.2;
    private static final double LENGTH_NORMALIZATION = 0.75;
    private static final double AVERAGE_PASSAGE_TOKENS = 256;

    private KeywordVectorizer() {}

    public static KeywordVector ofPassage(final String passage) {
        final List<String> tokens = tokenize(passage);
        final double lengthPenalty = TERM_SATURATION
                * (1 - LENGTH_NORMALIZATION + LENGTH_NORMALIZATION * tokens.size() / AVERAGE_PASSAGE_TOKENS);

        return toVector(tokens, frequency -> frequency * (TERM_SATURATION + 1) / (frequency + lengthPenalty));
    }

    public static KeywordVector ofQuery(final String query) {
        return toVector(tokenize(query), frequency -> 1.0);
    }

    static List<String> tokenize(final String text) {
        return WORD_PATTERN.matcher(StringUtils.defaultString(text)).results()
                .map(MatchResult::group)
                .flatMap(KeywordVectorizer::withParts)
                .filter(token -> token.length() >= MIN_TOKEN_CHARS)
                .map(token -> token.toLowerCase(Locale.ROOT))
                .toList();
    }

    private static Stream<String> withParts(final String word) {
        final List<String> parts = Arrays.stream(StringUtils.splitByCharacterTypeCamelCase(word))
                .filter(StringUtils::isAlphanumeric)
                .toList();

        return parts.size() > 1 ? Stream.concat(Stream.of(word), parts.stream()) : Stream.of(word);
    }

    private static KeywordVector toVector(final List<String> tokens, final LongToDoubleFunction weight) {
        final Map<Integer, Long> frequencies = tokens.stream()
                .collect(Collectors.groupingBy(token -> token.hashCode() & Integer.MAX_VALUE, TreeMap::new, Collectors.counting()));

        return KeywordVector.builder()
                .indices(List.copyOf(frequencies.keySet()))
                .values(frequencies.values().stream().map(frequency -> (float) weight.applyAsDouble(frequency)).toList())
                .build();
    }
}
