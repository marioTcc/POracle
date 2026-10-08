package com.mtcc.rag.util;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KeywordVectorizerTest {

    @Test
    void tokenize_ShouldKeepEachIdentifierWholeAndSplitIntoItsParts() {
        assertEquals(List.of("nimbusjwtdecoder", "nimbus", "jwt", "decoder", "validates", "the", "token"),
                KeywordVectorizer.tokenize("NimbusJwtDecoder validates the token"));
        assertEquals(List.of("spring_ai_version", "spring", "ai", "version", "preauthorize", "pre", "authorize"),
                KeywordVectorizer.tokenize("spring_ai_version = @PreAuthorize"));
        assertEquals(List.of(), KeywordVectorizer.tokenize("a ? - ."));
        assertEquals(List.of(), KeywordVectorizer.tokenize(null));
    }

    @Test
    void ofQuery_ShouldWeighEachDistinctTokenOnce() {
        final KeywordVector query = KeywordVectorizer.ofQuery("token Token decoder");

        assertEquals(2, query.getIndices().size());
        assertEquals(List.of(1f, 1f), query.getValues());
        assertTrue(query.getIndices().stream().allMatch(index -> index >= 0));
        assertTrue(KeywordVectorizer.ofQuery("?").isEmpty());
    }

    @Test
    void ofPassage_ShouldShareTheIndicesOfTheQueryAndWeighRepeatedTokensMore() {
        final KeywordVector passage = KeywordVectorizer.ofPassage("decoder decoder decoder token");
        final KeywordVector query = KeywordVectorizer.ofQuery("decoder token");

        assertEquals(query.getIndices(), passage.getIndices());
        assertEquals(passage.getIndices().size(), passage.getValues().size());

        final float repeated = passage.getValues().get(passage.getIndices().indexOf(indexOf("decoder")));
        final float single = passage.getValues().get(passage.getIndices().indexOf(indexOf("token")));
        assertTrue(repeated > single && single > 0, repeated + " > " + single);
        assertTrue(repeated < 3 * single, "term frequency saturates");
    }

    @Test
    void ofPassage_ShouldWeighTheSameTokenLessInALongerPassage() {
        final float inShortPassage = KeywordVectorizer.ofPassage("decoder").getValues().get(0);
        final KeywordVector longPassage = KeywordVectorizer.ofPassage("decoder " + "filler ".repeat(500));
        final float inLongPassage = longPassage.getValues().get(longPassage.getIndices().indexOf(indexOf("decoder")));

        assertTrue(inShortPassage > inLongPassage, inShortPassage + " > " + inLongPassage);
        assertFalse(longPassage.isEmpty());
    }

    private int indexOf(final String token) {
        return KeywordVectorizer.ofQuery(token).getIndices().get(0);
    }
}
