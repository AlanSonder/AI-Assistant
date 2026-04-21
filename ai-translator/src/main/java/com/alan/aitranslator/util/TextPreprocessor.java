package com.alan.aitranslator.util;

import java.util.regex.Pattern;

public class TextPreprocessor {

    private static final Pattern HTML_TAG_PATTERN = Pattern.compile("<[^>]+>");

    private static final Pattern MULTIPLE_NEWLINES = Pattern.compile("\n{3,}");

    private static final Pattern MULTIPLE_SPACES = Pattern.compile("[ \t]{2,}");

    public static String preprocess(String text) {
        if (text == null) {
            return "";
        }

        text = HTML_TAG_PATTERN.matcher(text).replaceAll("");

        text = MULTIPLE_SPACES.matcher(text).replaceAll(" ");

        text = MULTIPLE_NEWLINES.matcher(text).replaceAll("\n\n");

        return text.trim();
    }
}
