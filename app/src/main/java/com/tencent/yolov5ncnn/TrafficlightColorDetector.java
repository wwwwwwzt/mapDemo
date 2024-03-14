package com.tencent.yolov5ncnn;

import android.graphics.Bitmap;
import android.graphics.Color;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

public class TrafficlightColorDetector {
    // 红灯、黄灯、绿灯的最高和最低hsv数组
    private final float[][] red_hsv_1 = {{312f, 0.6941f, 0.5569f}, {358f, 1f, 1f}};
    private final float[][] red_hsv_2 = {{0f, 0.6941f, 0.5569f}, {20f, 1f, 1f}};
    private final float[][] yellow_hsv = {{22f, 0.6941f, 0.5569f}, {68f, 1f, 1f}};
    private final float[][] green_hsv = {{98f, 0.1373f, 0.5569f}, {190f, 1f, 1f}};
    // 红灯黄灯是true 否false 合并
    private boolean use_merge;
    // 滤波模板的尺寸，必须为大于1的奇数 默认取7
    private final int ksize = 7;

    public TrafficlightColorDetector(boolean use_merge) {
        this.use_merge = use_merge;
    }

    public String detect(Bitmap img) {
        float[][]hsvs = bitmapRGB2HSV(img);
        return detectFromHSV(hsvs);
    }

    private float[][] bitmapRGB2HSV(Bitmap img) {
        final int w = img.getWidth();
        final int h = img.getHeight();
        float[][] hsvs = new float[w * h][3];
        for (int x = 0; x < w; x++) {
            for (int y = 0; y < h; y++) {
                int pixel = img.getPixel(x, y);
                Color.colorToHSV(pixel, hsvs[w * y + x]);
            }
        }
        return hsvs;
    }

    private String detectFromHSV(float[][] hsvImg) {
        int[] masks = getMasks(hsvImg);
        return chooseColor(masks);
    }

    private int[] getMasks(float[][] hsvImg) {
        Integer redMask1 = getMask(hsvImg, red_hsv_1);
        Integer redMask2 = getMask(hsvImg, red_hsv_2);
        Integer redMask = countOne(redMask1, redMask2);
        Integer yellowMask = getMask(hsvImg, yellow_hsv);
        Integer greenMask = getMask(hsvImg, green_hsv);
        return new int[]{redMask, yellowMask, greenMask};
    }

    private Integer countOne(Integer a, Integer b) {
        int temp = a | b;
        int res = 0;
        while(temp != 0) {
            res++;
            temp &= temp - 1;
        }
        return res;
    }

    private Integer getMask(float[][] hsvImg, float[][] range) {
        return Arrays.stream(hsvImg).map(pixel -> {
            boolean flag = true;
            for (int i = 0; i < range[0].length; i++) {
                flag &= (pixel[i] >= range[0][i] && pixel[i] < range[1][i]);
            }
            return flag ? 1 : 0;
        }).mapToInt(x -> x).sum();
    }

    private String chooseColor(int[] masks) {
        final String[] colors = new String[]{profile.RED_TRAFFICLIGHT, profile.YELLOW_TRAFFICLIGHT, profile.GREEN_TRAFFICLIGHT};
        int max = 0, index = -1;
        for (int i = 0; i < masks.length; i++) {
            if (masks[i] > max) {
                max = masks[i];
                index = i;
            }
        }
        if (index == -1) return profile.NONE_COLOR_TRAFFICLIGHT;
        return colors[index];
    }
}
