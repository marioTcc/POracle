package com.mtcc.rag.serviceimpl;

import com.mtcc.common.entity.InformationChunk;
import com.mtcc.common.entity.InformationType;
import com.mtcc.common.entity.RetrievedChunk;
import com.mtcc.rag.config.RagProperties;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.model.scoring.ScoringModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import reactor.core.scheduler.Schedulers;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class InProcessRerankServiceImplTest {

    private static final String QUERY = "how are JWT tokens validated";

    @Mock
    private ScoringModel rerankModel;
    @Mock
    private RagProperties ragProperties;

    private InProcessRerankServiceImpl rerankService;

    private final RetrievedChunk first = retrieved("First.java", 0.9);
    private final RetrievedChunk second = retrieved("Second.java", 0.8);
    private final RetrievedChunk third = retrieved("Third.java", 0.7);

    @BeforeEach
    void setUp() {
        when(ragProperties.getRerankTopK()).thenReturn(2);
        rerankService = new InProcessRerankServiceImpl(rerankModel, ragProperties, Schedulers.immediate());
    }

    private RetrievedChunk retrieved(final String filePath, final double score) {
        return RetrievedChunk.builder()
                .chunk(InformationChunk.buildChunk("spring-security", filePath, "JAVA", InformationType.CODE,
                        "class " + filePath + " {}", "org.example", "code"))
                .score(score)
                .build();
    }

    @Test
    void rerank_ShouldKeepTheBestResultsOfTheModelWithItsScores() {
        when(rerankModel.scoreAll(anyList(), anyString())).thenReturn(Response.from(List.of(0.1, 0.7, 0.95)));

        final List<RetrievedChunk> reranked = rerankService.rerank(QUERY, List.of(first, second, third)).block();

        assertEquals(List.of("Third.java", "Second.java"),
                reranked.stream().map(result -> result.getChunk().getFilePath()).toList());
        assertEquals(List.of(0.95, 0.7), reranked.stream().map(RetrievedChunk::getScore).toList());
        verify(rerankModel).scoreAll(List.of(
                TextSegment.from(first.getChunk().toEmbeddingText()),
                TextSegment.from(second.getChunk().toEmbeddingText()),
                TextSegment.from(third.getChunk().toEmbeddingText())), QUERY);
    }

    @Test
    void rerank_ShouldNotCallTheModel_WhenThereIsNothingToRerank() {
        assertTrue(rerankService.rerank(QUERY, List.of()).block().isEmpty());

        verify(rerankModel, never()).scoreAll(anyList(), anyString());
    }

    @Test
    void rerank_ShouldFail_WhenTheModelDoesNotScoreEveryResult() {
        when(rerankModel.scoreAll(anyList(), anyString())).thenReturn(Response.from(List.of(0.1)));

        assertThrows(IllegalStateException.class, () -> rerankService.rerank(QUERY, List.of(first, second)).block());
    }

    @Test
    void noRerank_ShouldReturnTheResultsAsTheyAre() {
        assertEquals(List.of(first, second), new NoRerankServiceImpl().rerank(QUERY, List.of(first, second)).block());
    }
}
