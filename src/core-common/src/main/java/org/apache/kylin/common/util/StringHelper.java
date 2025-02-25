/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.kylin.common.util;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;
import org.apache.kylin.guava30.shaded.common.base.Preconditions;
import org.apache.kylin.guava30.shaded.common.collect.Lists;

public class StringHelper {

    public static final char QUOTE = '\'';
    public static final char DOUBLE_QUOTE = '"';
    public static final char SLASH = '\\';
    public static final char BACKTICK = '`';

    private StringHelper() {
    }

    public static String join(Iterable<String> parts, String separator) {
        StringBuilder buf = new StringBuilder();
        for (String p : parts) {
            if (buf.length() > 0)
                buf.append(separator);
            buf.append(p);
        }
        return buf.toString();
    }

    public static void toUpperCaseArray(String[] source, String[] target) {
        if (source != null) {
            for (int i = 0; i < source.length; i++) {
                if (source[i] != null) {
                    target[i] = StringUtils.upperCase(source[i]);
                }
            }
        }
    }

    public static String dropSuffix(String str, String suffix) {
        if (str.endsWith(suffix))
            return str.substring(0, str.length() - suffix.length());
        else
            return str;
    }

    public static String dropFirstSuffix(String str, String suffix) {
        if (str.contains(suffix))
            return str.substring(0, str.indexOf(suffix));
        else
            return str;
    }

    public static String min(Collection<String> strs) {
        String min = null;
        for (String s : strs) {
            if (min == null || min.compareTo(s) > 0)
                min = s;
        }
        return min;
    }

    public static String max(Collection<String> strs) {
        String max = null;
        for (String s : strs) {
            if (max == null || max.compareTo(s) < 0)
                max = s;
        }
        return max;
    }

    public static String min(String s1, String s2) {
        if (s1 == null)
            return s2;
        else if (s2 == null)
            return s1;
        else
            return s1.compareTo(s2) < 0 ? s1 : s2;
    }

    public static String max(String s1, String s2) {
        if (s1 == null)
            return s2;
        else if (s2 == null)
            return s1;
        else
            return s1.compareTo(s2) > 0 ? s1 : s2;
    }

    public static String[] subArray(String[] array, int start, int endExclusive) {
        if (start < 0 || start > endExclusive || endExclusive > array.length)
            throw new IllegalArgumentException();
        String[] result = new String[endExclusive - start];
        System.arraycopy(array, start, result, 0, endExclusive - start);
        return result;
    }

    public static String[] splitAndTrim(String str, String splitBy) {
        String[] split = str.split(splitBy);
        ArrayList<String> r = new ArrayList<>(split.length);
        for (String s : split) {
            s = s.trim();
            if (!s.isEmpty())
                r.add(s);
        }
        return r.toArray(new String[0]);
    }

    public static boolean equals(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    public static boolean validateNumber(String s) {
        return Pattern.compile("^(0|[1-9]\\d*)$").matcher(s).matches();
    }

    public static boolean validateBoolean(String s) {
        return "true".equals(s) || "false".equals(s);
    }

    public static String[] split(String str, String splitBy) {
        return str.split(splitBy);
    }

    public static boolean validateUrl(String s) {
        return Pattern.compile("^(http(s)?://)?[a-zA-Z0-9._-]+(:[0-9]+)?(/[a-zA-Z0-9._-]+)*/?$").matcher(s).matches();
    }

    public static boolean validateHost(String s) {
        return Pattern.compile("^(http(s)?://)?[a-zA-Z0-9._-]+(:[0-9]+)?").matcher(s).matches();
    }

    public static boolean validateDbName(String s) {
        return Pattern.compile("^[0-9a-zA-Z_-]+$").matcher(s).matches();
    }

    public static boolean validateShellArgument(String s) {
        return Pattern.compile("^[a-zA-Z0-9_./-]+$").matcher(s).matches();
    }

    public static String escapeShellArguments(String args) {
        String[] expandArgs = Arrays.stream(args.split(" ")).filter(arg -> !arg.isEmpty()).toArray(String[]::new);
        return String.join(" ", escapeShellArguments(expandArgs));
    }

    /**
     * support cases:
     * -u root
     * --user root
     * --user=root
     */
    public static String[] escapeShellArguments(String[] args) {
        Pattern keyPattern = Pattern.compile("^[a-zA-Z0-9-]+$");
        String key;
        String value;
        Iterator<String> argsIterator = Arrays.stream(args).iterator();
        List<String> newArgs = new ArrayList<>();
        while (argsIterator.hasNext()) {
            String cur = argsIterator.next();
            if (!cur.startsWith("-")) {
                throw new IllegalArgumentException("Unexpected args found: " + Arrays.toString(args));
            }
            boolean useEqual = false;
            if (cur.contains("=")) {
                useEqual = true;
                int index = cur.indexOf('=');
                key = cur.substring(0, index);
                value = cur.substring(index + 1);
            } else {
                key = cur;
                value = argsIterator.next();
            }
            if (!keyPattern.matcher(key).matches()) {
                throw new IllegalArgumentException("Unexpected args found: " + Arrays.toString(args));
            }
            // a'b'c -> a b c -> 'a'\''b'\''c'
            // ''a'b'c'' -> 'a'b'c' -> _ a b c _ -> \\''a'\\''b'\\''c'\\'
            if (value.startsWith("'") && value.endsWith("'") && value.length() >= 2) {
                value = value.substring(1, value.length() - 1);
            }
            String[] splitValues = value.split("'", -1);
            value = Arrays.stream(splitValues).map(v -> v.isEmpty() ? v : "'" + v + "'")
                    .collect(Collectors.joining("\\'"));

            if (useEqual) {
                newArgs.add(key + "=" + value);
            } else {
                newArgs.add(key);
                newArgs.add(value);
            }
        }
        return newArgs.toArray(new String[0]);
    }

    public static String backtickToDoubleQuote(String expression) {
        return convert(expression, StringHelper.BACKTICK, StringHelper.DOUBLE_QUOTE);
    }

    public static String doubleQuoteToBacktick(String expression) {
        return convert(expression, StringHelper.DOUBLE_QUOTE, StringHelper.BACKTICK);
    }

    private static String convert(String expression, char srcDelimiter, char dstDelimiter) {
        char[] chars = expression.toCharArray();
        List<Integer> quoteIndexes = new ArrayList<>();
        List<Integer> indexList = StringHelper.findDelimiterIndexes(srcDelimiter, expression, quoteIndexes);
        for (Integer integer : indexList) {
            chars[integer] = dstDelimiter;
        }

        for (Integer quoteIndex : quoteIndexes) {
            // change the escape character
            if (dstDelimiter == DOUBLE_QUOTE) {
                chars[quoteIndex - 1] = QUOTE;
            } else if (dstDelimiter == BACKTICK) {
                chars[quoteIndex - 1] = SLASH;
            }
        }
        return new String(chars);
    }

    public static String backtickQuote(String identifier) {
        String str = StringUtils.remove(identifier, StringHelper.BACKTICK);
        return StringHelper.BACKTICK + str + StringHelper.BACKTICK;
    }

    public static String doubleQuote(String identifier) {
        String str = StringUtils.remove(identifier, StringHelper.DOUBLE_QUOTE);
        return StringHelper.DOUBLE_QUOTE + str + StringHelper.DOUBLE_QUOTE;
    }

    /**
     * Search delimiters in the sql string.
     * @param key the delimiter to search
     * @param str the input string
     * @param quoteIndexes the index of all the quote characters
     * @return index list of {@code key}
     */
    public static List<Integer> findDelimiterIndexes(char key, String str, List<Integer> quoteIndexes) {
        Preconditions.checkState(key == BACKTICK || key == DOUBLE_QUOTE);
        char[] chars = str.toCharArray();
        List<Integer> indexList = Lists.newArrayList();
        List<Pair<Integer, Character>> toMatchTokens = Lists.newArrayList();
        for (int i = 0; i < chars.length; i++) {
            char ch = chars[i];
            if (toMatchTokens.isEmpty()) {
                if (ch == key || ch == QUOTE) {
                    toMatchTokens.add(new Pair<>(i, ch));
                }
                continue;
            }

            // The toMatchTokens is not empty, try to collect
            Pair<Integer, Character> exPair = toMatchTokens.get(toMatchTokens.size() - 1);
            Character ex = exPair.getSecond();
            if (ch == ex && ch == key) {
                toMatchTokens.add(new Pair<>(i, ex));
                Preconditions.checkState(toMatchTokens.size() == 2);
                indexList.add(toMatchTokens.get(0).getFirst());
                indexList.add(toMatchTokens.get(1).getFirst());
                toMatchTokens.clear();
            } else if (ch == ex && ch == QUOTE) {
                // There are two kind of method to escape the quote character in the char array.
                // One uses quote escapes {{@code ''}}, which need record the index of the second quote.
                // The other uses slash escapes {{@code \'}}, which need record the index of the quote.
                // ------
                // Given that the i-th character is not a slash-escaped quotation, we mark it 
                // as a delimiter and continue to examine the next character. If the next one
                // is also a quotation, then the i-th character serves only as an escape, 
                // and the (i+1)-th character is considered the actual quotation.
                Preconditions.checkState(toMatchTokens.size() == 1);
                boolean isDelimiter = isQuoteDelimiter(i, chars);
                if (isDelimiter && i + 1 < chars.length && chars[i + 1] == QUOTE) {
                    i++;
                    quoteIndexes.add(i);
                } else if (isDelimiter) {
                    toMatchTokens.clear();
                } else {
                    quoteIndexes.add(i);
                }
            }
        }
        Preconditions.checkState(indexList.size() % 2 == 0);
        return indexList;
    }

    /**
     * Determines if the i-th character is a quote character or delimiter.
     */
    private static boolean isQuoteDelimiter(int i, char[] chars) {
        int num = 0;
        for (int j = i - 1; j > 0; j--) {
            if (chars[j] == SLASH) {
                num++;
            } else {
                break;
            }
        }
        return num % 2 == 0;
    }

}
