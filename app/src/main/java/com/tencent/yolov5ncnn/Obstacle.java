package com.tencent.yolov5ncnn;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.Log;
import android.widget.ImageView;

//import java.util.ArrayList;
//import java.util.ArrayList;
import java.util.Arrays;

public class Obstacle extends Activity {

    private float xAngle;

    /**
     * 障碍物检测结果处理
     * @param yoloV5Ncnn 类
     * @param bitmap 检测图像
     * @param imageView 显示区
     * @return 角度过大提醒，有没有障碍物提醒
     */
    public String obstacleDetect(YoloV5Ncnn yoloV5Ncnn, Bitmap bitmap, ImageView imageView) {
        // bitmap的高宽
        int bitmapHeight = bitmap.getHeight();
        int bitmapWidth = bitmap.getWidth();
        // 根据手机倾角计算当前截取图像的比例
        float percentage = angle2percentage(xAngle);
        if (percentage >= 1.0f) return profile.OBSTACLE_XANGLE_ERROR;//倾角过大，报错
        // 检测结果集
        YoloV5Ncnn.Obj[] objs = yoloV5Ncnn.Detect(bitmap, false);
        Log.i("Objects", Arrays.toString(objs));
        showObjects(objs, bitmap, imageView);
        // 过滤结果
        return filterObj(objs, percentage, bitmapHeight, bitmapWidth);
        /*StringBuffer buf = new StringBuffer();
        HashSet<String> labelSet = new HashSet<>();
        // 如果label相同的话就只保留一个
        for(int i=0; i<objs.length; i++){
            String curLabel = objs[i].label;
            if(!curLabel.equals("红绿灯") && !curLabel.equals("斑马线")){
                labelSet.add(curLabel + ";");
            }

        }
        for(String s:labelSet){
            buf.append(s);
        }
        return buf.toString();*/
    }

    /**
     * 红绿灯检测结果处理
     * @param trafficlight 红绿灯类
     * @param bitmap 检测图像
     * @param imageView 显示区
     * @return 有无红绿灯，红绿灯当前颜色
     */
    public String TrafficlightDetect(Trafficlight trafficlight, Bitmap bitmap, ImageView imageView) {
        Trafficlight.Obj[] objs = trafficlight.Detect(bitmap, false);
        for (Trafficlight.Obj obj : objs) {
            if (obj.label.equals(profile.TRAFFICLIGHT_CLASSNAME)) {
                showObjectsLight(new Trafficlight.Obj[]{obj}, bitmap, imageView);
                // 按照红绿灯矩形框位置截取图像
                Bitmap trafficlightImg = Bitmap.createBitmap(bitmap, (int)obj.x, (int)obj.y, (int)obj.w, (int)obj.h);
                TrafficlightColorDetector trafficlightColorDetector = new TrafficlightColorDetector(true);
                return trafficlightColorDetector.detect(trafficlightImg);
            }
        }
        return profile.NONE_TRAFFICLIGHT;
    }

    /**
     * 斑马线检测结果处理
     * @param yoloV5Ncnn 类
     * @param bitmap 检测图像
     * @return 布尔值 是否有斑马线
     */
    public boolean zebraCrossingDetect(YoloV5Ncnn yoloV5Ncnn, Bitmap bitmap) {
        YoloV5Ncnn.Obj[] objs = yoloV5Ncnn.Detect(bitmap, false);
        for (YoloV5Ncnn.Obj obj : objs) {
            if (obj.label.equals(profile.ZEBRACROSSING_CLASSNAME)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 障碍物检测的过滤函数
     * 竖直方向过滤太远的障碍物，水平方向过滤太靠左和太靠右的障碍物
     */
    private String filterObj(YoloV5Ncnn.Obj[] objs, float percentage, int bitmapHeight, int bitmapWidth){
        // 底部过滤线
        float bottomLine = bitmapHeight * percentage;
        // 左侧过滤线
        float leftLine = (float) (bitmapWidth / 4);
        // 右侧过滤线
        float rightLine = (float) (bitmapWidth * 3 / 4);

//        ArrayList<YoloV5Ncnn.Obj> filteredObjs = new ArrayList<>();

        /*
        // 辅助线
        YoloV5Ncnn temp = new YoloV5Ncnn();
        YoloV5Ncnn.Obj bottomLineObject = temp.new Obj(10.0f,bottomLine,1000.0f,10.0f," ",0.99f);
        filteredObjs.add(bottomLineObject);
        YoloV5Ncnn.Obj leftLineObject = temp.new Obj(leftLine,0,10.0f,1000.0f," ",0.99f);
        filteredObjs.add(leftLineObject);
        YoloV5Ncnn.Obj rightLineObject = temp.new Obj(rightLine,0,10.0f,1000.0f," ",0.99f);
        filteredObjs.add(rightLineObject);
        */
        // 只要有一个障碍物就播报注意

        for (YoloV5Ncnn.Obj obj : objs) {
            Log.v("zcl",(obj.y+obj.h)+" "+bottomLine);
            Log.v("zcl",(obj.x)+" "+leftLine);
            Log.v("zcl",(obj.x+obj.w)+" "+rightLine);
            // 坐标值越大越靠下，所以大于line的都过滤掉
            if (obj.label.equals(profile.STEPS_CLASSNAME)) {
                return profile.OBSTACLE_WARN;
            }
            else if ((obj.y + obj.h) > bottomLine && obj.x <= leftLine && (obj.x + obj.w) >= rightLine) {
                //Log.v("zcl",obj.y+" "+bottomLine);
                return profile.OBSTACLE_WARN;
            }
        }
        return null;
        // return filteredObjs.toArray(new YoloV5Ncnn.Obj[0]);
    }

    // 根据手机倾角判断竖直方向过滤百分比函数
    private float angle2percentage(float angle) {
        float percentage = 1.0f;
        if (angle >= 40 && angle <= 90) {
            percentage = (float) (-0.0001944 * Math.pow(angle - 100.0, 2.0) + 0.7);
        }else if (angle >= 0 && angle < 40) {
            percentage = 0.0f;
        }
        return percentage;
    }

    private void showObjects(YoloV5Ncnn.Obj[] objects, Bitmap bitmap, ImageView imageView) {
        if (objects == null) {
            imageView.setImageBitmap(bitmap);
            return;
        }

        Bitmap rgba = bitmap.copy(Bitmap.Config.ARGB_8888, true);

        final int[] colors = new int[]{
                Color.rgb(54, 67, 244),
                Color.rgb(99, 30, 233),
                Color.rgb(176, 39, 156),
                Color.rgb(183, 58, 103),
                Color.rgb(181, 81, 63),
                Color.rgb(243, 150, 33),
                Color.rgb(244, 169, 3),
                Color.rgb(212, 188, 0),
                Color.rgb(136, 150, 0),
                Color.rgb(80, 175, 76),
                Color.rgb(74, 195, 139),
                Color.rgb(57, 220, 205),
                Color.rgb(59, 235, 255),
                Color.rgb(7, 193, 255),
                Color.rgb(0, 152, 255),
                Color.rgb(34, 87, 255),
                Color.rgb(72, 85, 121),
                Color.rgb(158, 158, 158),
                Color.rgb(139, 125, 96)
        };

        Canvas canvas = new Canvas(rgba);

        Paint paint = new Paint();
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(4);

        Paint textbgpaint = new Paint();
        textbgpaint.setColor(Color.WHITE);
        textbgpaint.setStyle(Paint.Style.FILL);

        Paint textpaint = new Paint();
        textpaint.setColor(Color.BLACK);
        textpaint.setTextSize(26);
        textpaint.setTextAlign(Paint.Align.LEFT);

        for (int i = 0; i < objects.length; i++) {
            paint.setColor(colors[i % 19]);

            canvas.drawRect(objects[i].x, objects[i].y, objects[i].x + objects[i].w, objects[i].y + objects[i].h, paint);

            // draw filled text inside image
            {
                String text = objects[i].label + " = " + String.format("%.1f", objects[i].prob * 100) + "%";

                float text_width = textpaint.measureText(text);
                float text_height = -textpaint.ascent() + textpaint.descent();

                float x = objects[i].x;
                float y = objects[i].y - text_height;
                if (y < 0)
                    y = 0;
                if (x + text_width > rgba.getWidth())
                    x = rgba.getWidth() - text_width;

                canvas.drawRect(x, y, x + text_width, y + text_height, textbgpaint);

                canvas.drawText(text, x, y - textpaint.ascent(), textpaint);
            }
        }

        imageView.setImageBitmap(rgba);
    }

    private void showObjectsLight(Trafficlight.Obj[] objects, Bitmap bitmap, ImageView imageView) {
        if (objects == null) {
            imageView.setImageBitmap(bitmap);
            return;
        }

        Bitmap rgba = bitmap.copy(Bitmap.Config.ARGB_8888, true);

        final int[] colors = new int[]{
                Color.rgb(54, 67, 244),
                Color.rgb(99, 30, 233),
                Color.rgb(176, 39, 156),
                Color.rgb(183, 58, 103),
                Color.rgb(181, 81, 63),
                Color.rgb(243, 150, 33),
                Color.rgb(244, 169, 3),
                Color.rgb(212, 188, 0),
                Color.rgb(136, 150, 0),
                Color.rgb(80, 175, 76),
                Color.rgb(74, 195, 139),
                Color.rgb(57, 220, 205),
                Color.rgb(59, 235, 255),
                Color.rgb(7, 193, 255),
                Color.rgb(0, 152, 255),
                Color.rgb(34, 87, 255),
                Color.rgb(72, 85, 121),
                Color.rgb(158, 158, 158),
                Color.rgb(139, 125, 96)
        };

        Canvas canvas = new Canvas(rgba);

        Paint paint = new Paint();
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(4);

        Paint textbgpaint = new Paint();
        textbgpaint.setColor(Color.WHITE);
        textbgpaint.setStyle(Paint.Style.FILL);

        Paint textpaint = new Paint();
        textpaint.setColor(Color.BLACK);
        textpaint.setTextSize(26);
        textpaint.setTextAlign(Paint.Align.LEFT);

        for (int i = 0; i < objects.length; i++) {
            paint.setColor(colors[i % 19]);

            canvas.drawRect(objects[i].x, objects[i].y, objects[i].x + objects[i].w, objects[i].y + objects[i].h, paint);

            // draw filled text inside image
            {
                String text = objects[i].label + " = " + String.format("%.1f", objects[i].prob * 100) + "%";

                float text_width = textpaint.measureText(text);
                float text_height = -textpaint.ascent() + textpaint.descent();

                float x = objects[i].x;
                float y = objects[i].y - text_height;
                if (y < 0)
                    y = 0;
                if (x + text_width > rgba.getWidth())
                    x = rgba.getWidth() - text_width;

                canvas.drawRect(x, y, x + text_width, y + text_height, textbgpaint);

                canvas.drawText(text, x, y - textpaint.ascent(), textpaint);
            }
        }

        imageView.setImageBitmap(rgba);
    }




    // ---------- getters and setters --------------
    public double getxAngle() {
        return xAngle;
    }

    public void setxAngle(float xAngle) {
        this.xAngle = xAngle;
    }
}