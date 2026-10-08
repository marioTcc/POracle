package com.mtcc.common.security;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class SecretRedactor {

    public static final String REDACTED = "[REDACTED]";

    private static final String KEY = "key";
    private static final String VALUE = "value";

    private static final List<String> SENSITIVE_WORDS = List.of(
            "password", "passwd", "pwd", "secret", "token", "apikey", "accesskey", "privatekey", "credential");

    private static final Set<String> NON_SECRET_VALUES = Set.of("true", "false", "null", "none", "[redacted]");

    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{.*}|\\{\\{.*}}|<.*>|%.*%|#\\{.*}|\\*+");

    private static final List<Pattern> SECRET_TOKENS = List.of(
            Pattern.compile("-----BEGIN [A-Z ]*PRIVATE KEY-----[\\s\\S]*?-----END [A-Z ]*PRIVATE KEY-----"),
            Pattern.compile("\\bAKIA[0-9A-Z]{16}\\b"),
            Pattern.compile("\\bgh[pousr]_[A-Za-z0-9]{36,}\\b"),
            Pattern.compile("\\bgithub_pat_[A-Za-z0-9_]{60,}\\b"),
            Pattern.compile("\\bsk-[A-Za-z0-9_-]{20,}"),
            Pattern.compile("\\bgsk_[A-Za-z0-9]{20,}\\b"),
            Pattern.compile("\\bAIza[A-Za-z0-9_-]{35}"),
            Pattern.compile("\\bxox[baprs]-[A-Za-z0-9-]{10,}"),
            Pattern.compile("\\beyJ[A-Za-z0-9_-]{10,}\\.eyJ[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}"));

    private static final Pattern URL_CREDENTIALS = Pattern.compile(
            "\\b[a-zA-Z][\\w+.-]*://[^\\s/:@]+:(?<value>[^\\s/@]+)@");

    private static final List<Pattern> CONFIGURATION_ENTRIES = List.of(
            Pattern.compile("^[ \\t-]*+[\"']?(?<key>\\w[\\w.-]*+)[\"']?[ \\t]*+[:=][ \\t]*+[\"']?(?<value>[^\"'\\r\\n]*[^\"'\\r\\n \\t,])[\"']?[ \\t]*+,?[ \\t]*+$",
                    Pattern.MULTILINE),
            Pattern.compile("<(?<key>[\\w.:-]+)>(?<value>[^<]+)</"),
            Pattern.compile("\\b(?<key>[\\w.:-]+)\\s*=\\s*\"(?<value>[^\"]+)\""));

    public Redaction redact(final String text, final boolean isConfiguration) {
        final AtomicInteger count = new AtomicInteger();
        String redacted = text;

        for (final Pattern secretToken : SECRET_TOKENS) {
            redacted = secretToken.matcher(redacted).replaceAll(match -> {
                count.incrementAndGet();
                return Matcher.quoteReplacement(REDACTED);
            });
        }

        redacted = redactValues(redacted, URL_CREDENTIALS, false, count);
        if (isConfiguration) {
            for (final Pattern configurationEntry : CONFIGURATION_ENTRIES) {
                redacted = redactValues(redacted, configurationEntry, true, count);
            }
        }

        return Redaction.builder().text(redacted).count(count.get()).build();
    }

    public boolean containsSecretToken(final String text) {
        return SECRET_TOKENS.stream().anyMatch(secretToken -> secretToken.matcher(text).find());
    }

    private String redactValues(final String text, final Pattern pattern, final boolean hasKey, final AtomicInteger count) {
        final Matcher matcher = pattern.matcher(text);
        final StringBuilder redacted = new StringBuilder();
        int copiedUpTo = 0;

        while (matcher.find()) {
            if ((!hasKey || isSensitiveKey(matcher.group(KEY))) && !isPlaceholder(matcher.group(VALUE))) {
                redacted.append(text, copiedUpTo, matcher.start(VALUE)).append(REDACTED);
                copiedUpTo = matcher.end(VALUE);
                count.incrementAndGet();
            }
        }
        return redacted.append(text, copiedUpTo, text.length()).toString();
    }

    private boolean isSensitiveKey(final String key) {
        final String normalizedKey = key.toLowerCase(Locale.ROOT).replaceAll("[-_.]", "");

        return SENSITIVE_WORDS.stream().anyMatch(normalizedKey::contains);
    }

    private boolean isPlaceholder(final String value) {
        final String strippedValue = value.strip();

        return NON_SECRET_VALUES.contains(strippedValue.toLowerCase(Locale.ROOT)) || PLACEHOLDER.matcher(strippedValue).matches();
    }
}
