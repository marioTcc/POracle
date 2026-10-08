package com.mtcc.oracle.guardrail;

import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.guardrail.InputGuardrail;
import dev.langchain4j.guardrail.InputGuardrailRequest;
import dev.langchain4j.guardrail.InputGuardrailResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;

@Slf4j
@RequiredArgsConstructor
public class InputValidationGuardrail implements InputGuardrail {

    private static final double LENGTH_WARNING_RATIO = 0.8;

    private static final Map<Pattern, String> INJECTION_PATTERNS = new LinkedHashMap<>();
    private static final Map<Pattern, String> ENCODING_PATTERNS = new LinkedHashMap<>();

    static {
        INJECTION_PATTERNS.put(Pattern.compile("ignore\\s+(all\\s+)?previous\\s+instructions", Pattern.CASE_INSENSITIVE),
                "ignore previous instructions");
        INJECTION_PATTERNS.put(Pattern.compile("you\\s+are\\s+now\\s+\\w+", Pattern.CASE_INSENSITIVE),
                "identity override attempt");
        INJECTION_PATTERNS.put(Pattern.compile("pretend\\s+(you\\s+)?(have\\s+)?no\\s+restrictions", Pattern.CASE_INSENSITIVE),
                "restriction bypass attempt");
        INJECTION_PATTERNS.put(Pattern.compile("disregard\\s+(your\\s+)?(safety|rules|guidelines)", Pattern.CASE_INSENSITIVE),
                "safety bypass attempt");
        INJECTION_PATTERNS.put(Pattern.compile("override\\s+(your\\s+)?instructions", Pattern.CASE_INSENSITIVE),
                "instruction override attempt");
        INJECTION_PATTERNS.put(Pattern.compile("(do\\s+anything\\s+now|DAN\\s+mode)", Pattern.CASE_INSENSITIVE),
                "DAN jailbreak attempt");
        INJECTION_PATTERNS.put(Pattern.compile("system\\s*prompt\\s*[:=]", Pattern.CASE_INSENSITIVE),
                "system prompt injection");
        INJECTION_PATTERNS.put(Pattern.compile("\\[INST]|\\[/INST]|<<SYS>>|<\\|im_start\\|>", Pattern.CASE_INSENSITIVE),
                "raw prompt template injection");

        ENCODING_PATTERNS.put(Pattern.compile("[A-Za-z0-9+/]{40,}={0,2}"),
                "possible Base64 encoded payload");
        ENCODING_PATTERNS.put(Pattern.compile("1gn0r3|byp4ss|h4ck|3xpl01t|0v3rr1d3", Pattern.CASE_INSENSITIVE),
                "Leetspeak obfuscation detected");
        ENCODING_PATTERNS.put(Pattern.compile("rot13|base64_decode|atob\\(", Pattern.CASE_INSENSITIVE),
                "encoding function reference");
    }

    private final int maxInputChars;

    @Override
    public InputGuardrailResult validate(final UserMessage userMessage) {
        final String text = userMessage.singleText();

        for (final Map.Entry<Pattern, String> injection : INJECTION_PATTERNS.entrySet()) {
            if (injection.getKey().matcher(text).find()) {
                return failure("Prompt injection detected: " + injection.getValue());
            }
        }

        if (text.length() > maxInputChars) {
            return failure("Input too long: " + text.length() + " chars (max: " + maxInputChars + ")");
        }
        if (text.length() > maxInputChars * LENGTH_WARNING_RATIO) {
            log.warn("Input approaching the length limit: {}/{} chars", text.length(), maxInputChars);
        }

        ENCODING_PATTERNS.entrySet().stream()
                .filter(encoding -> encoding.getKey().matcher(text).find())
                .forEach(encoding -> log.warn("Possible encoding attack in the user input: {}", encoding.getValue()));

        return successWith(GuardrailPrompts.sandboxUserInput(text));
    }

    @Override
    public CompletableFuture<InputGuardrailResult> validateAsync(final InputGuardrailRequest request) {
        return CompletableFuture.completedFuture(validate(request));
    }
}
