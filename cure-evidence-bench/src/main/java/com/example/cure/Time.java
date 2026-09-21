package com.example.cure;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/** 全部时间戳以 epoch 秒存储，展示统一使用 Asia/Tokyo（演示夹具固定日期）。 */
public final class Time {
    public static final ZoneId ZONE = ZoneId.of("Asia/Tokyo");
    private static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter MDHM = DateTimeFormatter.ofPattern("MM-dd HH:mm");

    private Time() {
    }

    public static long t(int year, int month, int day, int hour, int minute) {
        return LocalDateTime.of(year, month, day, hour, minute).atZone(ZONE).toEpochSecond();
    }

    public static String hm(long epochSec) {
        return Instant.ofEpochSecond(epochSec).atZone(ZONE).format(HM);
    }

    public static String mdhm(long epochSec) {
        return Instant.ofEpochSecond(epochSec).atZone(ZONE).format(MDHM);
    }
}
