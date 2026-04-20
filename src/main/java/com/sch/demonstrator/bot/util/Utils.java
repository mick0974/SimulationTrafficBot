package com.sch.demonstrator.bot.util;

public class Utils {

    public static String formatTime(long timestamp) {
        if (timestamp == 0) return "00:00:00";

        long totalSeconds = Math.max(0, timestamp);

        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;

        return String.format("%02d:%02d:%02d", hours, minutes, seconds);
    }

    public static double clamp(double sample, double min, double max) {
        return Math.max(min, Math.min(max, sample));
    }
}
