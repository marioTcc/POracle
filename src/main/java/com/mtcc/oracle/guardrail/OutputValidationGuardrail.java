package com.mtcc.oracle.guardrail;

import com.mtcc.common.security.SecretRedactor;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.guardrail.OutputGuardrail;
import dev.langchain4j.guardrail.OutputGuardrailRequest;
import dev.langchain4j.guardrail.OutputGuardrailResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;

@Slf4j
@RequiredArgsConstructor
public class OutputValidationGuardrail implements OutputGuardrail {

    private static final Map<Pattern, String> LEAKAGE_PATTERNS = new LinkedHashMap<>();
    private static final Map<Pattern, String> WARNING_PATTERNS = new LinkedHashMap<>();

    private static final Set<String> HARMFUL_KEYWORDS = Set.of(
            "hack into", "bypass security", "exploit vulnerability",
            "steal credentials", "ddos attack", "sql injection attack",
            "create malware", "build a virus", "phishing email template");

    static {
        LEAKAGE_PATTERNS.put(Pattern.compile("my\\s+system\\s+prompt\\s+(is|says|contains)", Pattern.CASE_INSENSITIVE),
                "system prompt disclosure");
        LEAKAGE_PATTERNS.put(Pattern.compile("my\\s+instructions\\s+(are|say|tell)", Pattern.CASE_INSENSITIVE),
                "instruction disclosure");

        WARNING_PATTERNS.put(Pattern.compile("(api[_\\s]?key|secret[_\\s]?key|password)\\s*[:=]\\s*\\S+", Pattern.CASE_INSENSITIVE),
                "possible credential");
        WARNING_PATTERNS.put(Pattern.compile("\\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}\\b"),
                "email address");
        WARNING_PATTERNS.put(Pattern.compile("\\b\\d{3}-\\d{2}-\\d{4}\\b"),
                "SSN pattern");
        WARNING_PATTERNS.put(Pattern.compile("\\b\\d{4}[\\s-]?\\d{4}[\\s-]?\\d{4}[\\s-]?\\d{4}\\b"),
                "credit card number");
        WARNING_PATTERNS.put(Pattern.compile("\\b[A-Z]{2}\\d{2}\\s?\\d{4}\\s?\\d{4}\\s?\\d{4}\\s?\\d{4}\\s?\\d{0,2}\\b"),
                "IBAN");
    }

    private final SecretRedactor secretRedactor;

    @Override
    public OutputGuardrailResult validate(final AiMessage responseFromLLM) {
        final String text = responseFromLLM.text();

        if (text == null || text.isBlank()) {
            return success();
        }

        for (final Map.Entry<Pattern, String> leakage : LEAKAGE_PATTERNS.entrySet()) {
            if (leakage.getKey().matcher(text).find()) {
                return failure("Output leakage detected: " + leakage.getValue());
            }
        }
        if (secretRedactor.containsSecretToken(text)) {
            return failure("Output leakage detected: secret token");
        }

        WARNING_PATTERNS.entrySet().stream()
                .filter(warning -> warning.getKey().matcher(text).find())
                .forEach(warning -> log.warn("Possible sensitive data in the answer: {}", warning.getValue()));

        final String lowerCaseText = text.toLowerCase(Locale.ROOT);
        HARMFUL_KEYWORDS.stream()
                .filter(lowerCaseText::contains)
                .forEach(keyword -> log.warn("Possible harmful content in the answer: '{}'", keyword));

        return success();
    }

    @Override
    public CompletableFuture<OutputGuardrailResult> validateAsync(final OutputGuardrailRequest request) {
        return CompletableFuture.completedFuture(validate(request));
    }
}
