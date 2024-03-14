package com.tencent.yolov5ncnn;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Handler;
import android.os.Message;
import android.widget.ImageView;

import com.tencent.yolov5ncnn.ui.surroundings.SensorEventHelper;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;

public class Surroundings {

    private String lng;
    private String lat;
    private Context mcontext;
    private SensorEventHelper mSensorHelper;
    private final int edge = 2000;
    private Handler handler;

    public SensorEventHelper getmSensorHelper() {
        return mSensorHelper;
    }

    public void getSurroundingPois(){
        float mAngle = mSensorHelper.getmAngle();
        String coordinates = cal_lat_lng(Double.valueOf(lat), Double.valueOf(lng), mAngle);
        StringBuilder sb = new StringBuilder();
        sb.append("前方").append(edge).append("米范围内：");

        // 发送poi请求
        POIRequest request = new POIRequest();
        request.setKey(profile.gaodeKey);
        request.setCoordinates(coordinates);
        request.setTypes("050301");
        request.setGetPolygonPois();
        FutureTask<String> polygon=new FutureTask<>(request.getGetPolygonPois());
        new Thread(polygon).start();
        String responseBodyString = "";
        try {
            responseBodyString = polygon.get();
        } catch (ExecutionException | InterruptedException e) {
            e.printStackTrace();
        }

        try {
            JSONObject jo = new JSONObject(responseBodyString);
            JSONArray pois=jo.getJSONArray("pois");
            if(pois.length() == 0)
                sb.append("没有店铺");
            else {
                sb.append("有").append(pois.length()).append("家店铺。");
                for (int i = 0; i < pois.length(); i++) {
                    JSONObject poi = (JSONObject) pois.get(i);
                    sb.append(poi.getString("name"));
//                            .append("距离").append(poi.getString("distance")).append("米。");
                }
            }
        } catch (JSONException e) {
            e.printStackTrace();
            sb.delete(0, sb.length());
            sb.append("查询出错");
        }finally {
            Message msg = Message.obtain();
            msg.what = 1;
            msg.obj = sb.toString();
            handler.sendMessage(msg);
        }
    }

    /**
     * 根据当前当前坐标以及朝向，计算三角形区域其余两点的坐标。
     * @param cur_lat 当前纬度
     * @param cur_lng 当前经度
     * @param mAngle 当前角度
     * @return 三角形顶点坐标组成的字符串，可直接拼接到poi_url中。
     * @author su
     */
    public String cal_lat_lng(Double cur_lat, Double cur_lng, float mAngle){
        StringBuilder coordinates = new StringBuilder();

        double pi = Math.PI;
        double r_earth = 6378000;

        // 三角形区域的角度，目前设置为前方60°.
        double alpha = pi / 3;

        double dxA = edge * Math.sin(mAngle - alpha / 2);
        double dyA = edge * Math.cos(mAngle - alpha / 2);
        double dxB = edge * Math.sin(pi - alpha / 2 - mAngle);
        double dyB = (-1) * edge * Math.cos(pi - alpha / 2 - mAngle);

        double latA  = cur_lat  + (dyA / r_earth) * (180 / pi);
        double lngA = cur_lng + (dxA / r_earth) * (180 / pi) / Math.cos(latA * pi/180);
        double latB  = cur_lat  + (dyB / r_earth) * (180 / pi);
        double lngB = cur_lng + (dxB / r_earth) * (180 / pi) / Math.cos(latB * pi/180);

        coordinates.append(cur_lng).append(",").append(cur_lat).append("|");
        coordinates.append(lngA).append(",").append(latA).append("|");
        coordinates.append(lngB).append(",").append(latB);

        return coordinates.toString();
    }

    /**
     * 调用PaddleOCRNcnn获取相应的识别文字
     * @param paddleOCRNcnn PaddleOCRNcnn类
     * @param bitmap 待检测图片
     * @param imageView 图片展示区
     * @return 三个最大的文字框  没有显示未识别到店铺
     */
    // 没有的情况 长度<=3的情况  长度>3进行排序
    public String storeOCR(PaddleOCRNcnn paddleOCRNcnn, Bitmap bitmap, ImageView imageView){
        PaddleOCRNcnn.Obj[] objs = paddleOCRNcnn.Detect(bitmap, false);
        if (objs.length <= 0) return null;
        // 计算每个框的面积  |x0 - x1|*|y1 - y2|  排序只选三个
        // 想选几个把max_objs的长度改为几就可以
        PaddleOCRNcnn.Obj[] max_objs = new PaddleOCRNcnn.Obj[3];
        if (objs.length <= max_objs.length){
            showObjects(objs, imageView, bitmap);
            return concatLabel(objs);
        }
        selectionNumMax(objs, max_objs, max_objs.length);
        showObjects(max_objs, imageView, bitmap);
        return concatLabel(max_objs);
    }
    // 显示标签
    private void showObjects(PaddleOCRNcnn.Obj[] objects, ImageView imageView, Bitmap bitmap)
    {
        if (objects == null)
        {
            imageView.setImageBitmap(bitmap);
            return;
        }

        // draw objects on bitmap
        Bitmap rgba = bitmap.copy(Bitmap.Config.ARGB_8888, true);

        final int[] colors = new int[] {
                Color.rgb( 54,  67, 244),
                Color.rgb( 99,  30, 233),
                Color.rgb(176,  39, 156),
                Color.rgb(183,  58, 103),
                Color.rgb(181,  81,  63),
                Color.rgb(243, 150,  33),
                Color.rgb(244, 169,   3),
                Color.rgb(212, 188,   0),
                Color.rgb(136, 150,   0),
                Color.rgb( 80, 175,  76),
                Color.rgb( 74, 195, 139),
                Color.rgb( 57, 220, 205),
                Color.rgb( 59, 235, 255),
                Color.rgb(  7, 193, 255),
                Color.rgb(  0, 152, 255),
                Color.rgb( 34,  87, 255),
                Color.rgb( 72,  85, 121),
                Color.rgb(158, 158, 158),
                Color.rgb(139, 125,  96)
        };

        Canvas canvas = new Canvas(rgba);

        Paint paint = new Paint();
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(2);
        Paint textbgpaint = new Paint();
        textbgpaint.setColor(Color.WHITE);
        textbgpaint.setStyle(Paint.Style.FILL);

        Paint textpaint = new Paint();
        textpaint.setColor(Color.BLACK);
        textpaint.setTextSize(56);
        textpaint.setTextAlign(Paint.Align.LEFT);

        for (int i = 0; i < objects.length; i++)
        {
            paint.setColor(colors[i % 19]);

            //canvas.drawRect(objects[i].x, objects[i].y, objects[i].x + objects[i].w, objects[i].y + objects[i].h, paint);
            canvas.drawLine(objects[i].x0,objects[i].y0,objects[i].x1,objects[i].y1,paint);
            canvas.drawLine(objects[i].x1,objects[i].y1,objects[i].x2,objects[i].y2,paint);
            canvas.drawLine(objects[i].x2,objects[i].y2,objects[i].x3,objects[i].y3,paint);
            canvas.drawLine(objects[i].x3,objects[i].y3,objects[i].x0,objects[i].y0,paint);
            // draw filled text inside image
            {
                String text = objects[i].label;// + " = " + String.format("%.1f", objects[i].prob * 100) + "%";

                float text_width = textpaint.measureText(text);
                float text_height = - textpaint.ascent() + textpaint.descent();

                float x = objects[i].x0;
                float y = objects[i].y0 - text_height;
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

    // 输出最后结果
    private String concatLabel(PaddleOCRNcnn.Obj[] objects){
        // 叠加string
        StringBuilder buf = new StringBuilder();
        for (PaddleOCRNcnn.Obj object : objects) {
            buf.append(object.label);
            buf.append(";");
        }
        return buf.toString();
    }

    public void selectionNumMax(PaddleOCRNcnn.Obj[] objs, PaddleOCRNcnn.Obj[] maxObjs, int num){
        for(int i=0; i<num; i++){
            int maxIndex = i;
            for(int j=i; j<objs.length; j++){
                if(objs[j].area > objs[maxIndex].area){
                   maxIndex = j;
                }
            }
            PaddleOCRNcnn.Obj temp = objs[maxIndex];
            objs[maxIndex] = objs[i];
            objs[i] = temp;
        }
        if (num >= 0) {
            System.arraycopy(objs, 0, maxObjs, 0, num);
        }
    }

    /*----- 以下为getter和setter -----*/
    public void setmSensorHelper() {
        this.mSensorHelper = new SensorEventHelper(this.getMcontext());
        mSensorHelper.registerSensorListener();
    }

    public Context getMcontext() {
        return mcontext;
    }

    public void setMcontext(Context mcontext) {
        this.mcontext = mcontext;
    }

    public String getLng() {
        return lng;
    }

    public void setLng(String lng) {
        this.lng = lng;
    }

    public String getLat() {
        return lat;
    }

    public void setLat(String lat) {
        this.lat = lat;
    }

    public Handler getHandler() {
        return handler;
    }

    public void setHandler(Handler handler) {
        this.handler = handler;
    }
}
