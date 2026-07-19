package com.lx.tv.music;

import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LRC歌词解析器
 * 解析LRC格式歌词，支持翻译歌词(tlyric)
 * 支持多种时间标签格式：
 *   [mm:ss.xx]
 *   [mm:ss.xxx]
 *   [mm:ss]
 *   多时间标签合并: [00:01.00][00:05.00]歌词内容
 */
public class LyricParser {
    private static final String TAG = "LyricParser";

    /** 匹配形如 [mm:ss.xx] 或 [mm:ss.xxx] 或 [mm:ss] 的时间标签 */
    private static final Pattern TIME_PATTERN =
            Pattern.compile("\\[(\\d{1,2}):(\\d{1,2})(?:[.:](\\d{1,3}))?\\]");

    /** 匹配ID标签如 [ti:xxx] [ar:xxx] [al:xxx] [by:xxx] [offset:xxx] */
    private static final Pattern ID_TAG_PATTERN =
            Pattern.compile("^\\[(ti|ar|al|by|offset|re|ve):(.*)\\]$", Pattern.CASE_INSENSITIVE);

    /** LyricLine对象，表示一行歌词 */
    public static class LyricLine {
        /** 时间，单位毫秒 */
        public long time;
        /** 歌词内容 */
        public String content;
        /** 翻译内容 */
        public String translation;

        public LyricLine(long time, String content) {
            this.time = time;
            this.content = content;
            this.translation = null;
        }

        public LyricLine(long time, String content, String translation) {
            this.time = time;
            this.content = content;
            this.translation = translation;
        }

        @Override
        public String toString() {
            return "LyricLine{" + "time=" + time + ", content='" + content + '\'' +
                    (translation != null ? ", translation='" + translation + '\'' : "") + '}';
        }
    }

    /**
     * 解析LRC格式歌词
     * @param lrcContent LRC原文
     * @return 按时间排序的歌词行列表，永远不为null（空内容返回空列表）
     */
    public static List<LyricLine> parseLyric(String lrcContent) {
        List<LyricLine> result = new ArrayList<>();
        if (lrcContent == null || lrcContent.trim().isEmpty()) {
            return result;
        }

        String[] lines = lrcContent.split("\n");
        for (String rawLine : lines) {
            if (rawLine == null) continue;
            String line = rawLine.trim();
            if (line.isEmpty()) continue;

            // 跳过ID标签行
            if (ID_TAG_PATTERN.matcher(line).matches()) {
                continue;
            }

            // 提取所有时间标签
            Matcher matcher = TIME_PATTERN.matcher(line);
            List<Long> times = new ArrayList<>();
            int lastMatchEnd = 0;
            while (matcher.find()) {
                long timeMs = parseTimeToMs(matcher.group(1), matcher.group(2), matcher.group(3));
                times.add(timeMs);
                lastMatchEnd = matcher.end();
            }
            if (times.isEmpty()) {
                // 没有时间标签的行，跳过
                continue;
            }

            // 时间标签之后的内容即为歌词
            String content = line.substring(lastMatchEnd).trim();
            for (Long time : times) {
                result.add(new LyricLine(time, content));
            }
        }

        // 按时间升序排序
        Collections.sort(result, (a, b) -> Long.compare(a.time, b.time));

        // 去除时间相同的重复行（保留第一个）
        List<LyricLine> deduped = new ArrayList<>();
        long lastTime = -1;
        for (LyricLine ll : result) {
            if (ll.time != lastTime || deduped.isEmpty()) {
                deduped.add(ll);
                lastTime = ll.time;
            }
        }
        return deduped;
    }

    /**
     * 合并主歌词和翻译歌词
     * @param lrcContent 主歌词
     * @param tlyricContent 翻译歌词
     * @return 带翻译的歌词行列表
     */
    public static List<LyricLine> parseLyricWithTranslation(String lrcContent, String tlyricContent) {
        List<LyricLine> mainLyrics = parseLyric(lrcContent);
        if (tlyricContent == null || tlyricContent.trim().isEmpty()) {
            return mainLyrics;
        }
        List<LyricLine> translatedLyrics = parseLyric(tlyricContent);
        if (translatedLyrics.isEmpty()) {
            return mainLyrics;
        }

        // 建立时间->翻译的映射
        java.util.Map<Long, String> translationMap = new java.util.HashMap<>();
        for (LyricLine ll : translatedLyrics) {
            translationMap.put(ll.time, ll.content);
        }

        // 合并翻译
        for (LyricLine main : mainLyrics) {
            String t = translationMap.get(main.time);
            if (t != null && !t.isEmpty()) {
                main.translation = t;
            }
        }
        return mainLyrics;
    }

    /**
     * 根据当前播放时间获取应显示的歌词行索引
     * @param lyrics 歌词列表
     * @param currentTime 当前时间（毫秒）
     * @return 当前应显示的歌词行索引，如果时间小于第一行返回-1，列表为空返回-1
     */
    public static int getCurrentLine(List<LyricLine> lyrics, long currentTime) {
        if (lyrics == null || lyrics.isEmpty()) {
            return -1;
        }
        // 时间小于第一行
        if (currentTime < lyrics.get(0).time) {
            return -1;
        }
        // 二分查找最后一个时间<=currentTime的行
        int left = 0;
        int right = lyrics.size() - 1;
        int result = 0;
        while (left <= right) {
            int mid = (left + right) / 2;
            if (lyrics.get(mid).time <= currentTime) {
                result = mid;
                left = mid + 1;
            } else {
                right = mid - 1;
            }
        }
        return result;
    }

    /**
     * 将时间分量转换为毫秒
     * @param minStr 分钟
     * @param secStr 秒
     * @param msStr 毫秒部分（可能是1-3位）
     */
    private static long parseTimeToMs(String minStr, String secStr, String msStr) {
        try {
            long min = Long.parseLong(minStr);
            long sec = Long.parseLong(secStr);
            long ms = 0;
            if (msStr != null && !msStr.isEmpty()) {
                // 规范化到3位毫秒
                String normalized;
                if (msStr.length() == 1) {
                    normalized = msStr + "00";
                } else if (msStr.length() == 2) {
                    normalized = msStr + "0";
                } else {
                    normalized = msStr.substring(0, 3);
                }
                ms = Long.parseLong(normalized);
            }
            return min * 60_000 + sec * 1000 + ms;
        } catch (NumberFormatException e) {
            Log.w(TAG, "Failed to parse time: " + minStr + ":" + secStr + "." + msStr);
            return 0;
        }
    }
}
