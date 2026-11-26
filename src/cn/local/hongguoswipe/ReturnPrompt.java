package cn.local.hongguoswipe;

import java.util.regex.Pattern;

/** Only explicit return-to-drama text, or the observed embedded-live return bar. */
public final class ReturnPrompt {
    private static final Pattern TEXT = Pattern.compile("(?:上滑|上划|向上滑动)继续(?:看|观看)(?:短剧|剧情)[^\\p{L}\\p{N}]{0,3}");
    public static boolean text(String value) {
        if (value == null) return false;
        String label = value.replaceAll("[\\s\\u200b]+", "");
        return TEXT.matcher(label).matches();
    }

    public static boolean liveBar(int width, int height, int videoLeft, int videoTop,
            int videoRight, int videoBottom, int arrowLeft, int arrowTop, int arrowRight, int arrowBottom) {
        if (width <= 0 || height <= width) return false;
        int videoWidth = videoRight - videoLeft, arrowWidth = arrowRight - arrowLeft;
        int arrowHeight = arrowBottom - arrowTop;
        double arrowX = (arrowLeft + arrowRight) / 2.0, arrowY = (arrowTop + arrowBottom) / 2.0;
        return videoLeft >= 0 && videoLeft <= width * .05 && videoTop >= 0 && videoTop <= height * .05
                && videoRight <= width && videoWidth >= width * .90
                && videoBottom >= height * .70 && videoBottom <= height * .95
                && arrowWidth >= width * .018 && arrowWidth <= width * .08
                && arrowHeight >= height * .005 && arrowHeight <= height * .04
                && arrowX >= width * .56 && arrowX <= width * .80
                && arrowTop > videoBottom && arrowY >= height * .94 && arrowBottom <= height;
    }
}
