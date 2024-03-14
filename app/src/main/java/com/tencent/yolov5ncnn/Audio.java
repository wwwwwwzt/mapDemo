package com.tencent.yolov5ncnn;

import android.content.Context;
import android.speech.tts.TextToSpeech;
import android.util.Log;

import com.tencent.yolov5ncnn.ui.surroundings.SensorEventHelper;

import java.util.Calendar;
import java.util.Locale;

public class Audio {
    // 当前地址
    private String address = null;
    // 方向角
    private float bearing = 0.0f;

    private Context mContext;

    private SensorEventHelper mSensorhelper;

    private CameraCapture mCameraCapt;
    // 自带语音对象
    private TextToSpeech textToSpeech = null;

    public Audio(Context c){
        //实例化自带语音对象
        textToSpeech = new TextToSpeech(c, status -> {
            if (status == TextToSpeech.SUCCESS) {
                // 控制音调
                textToSpeech.setPitch(1.0f);
                // 控制语速
                textToSpeech.setSpeechRate(1.0f);
                //判断是否支持下面两种语言
                int result1 = textToSpeech.setLanguage(Locale.US);
                int result2 = textToSpeech.setLanguage(Locale.SIMPLIFIED_CHINESE);
                boolean a = (result1 == TextToSpeech.LANG_MISSING_DATA || result1 == TextToSpeech.LANG_NOT_SUPPORTED);
                boolean b = (result2 == TextToSpeech.LANG_MISSING_DATA || result2 == TextToSpeech.LANG_NOT_SUPPORTED);
                Log.i("zhh_tts", "US支持否？--》" + !a + "\nzh-CN支持否》--》" + !b);
            } else {
                Log.i("audioWarning: ","语音播报初始化失败");
            }
        });
    }

    // 障碍物检测语音播报
    public void speakObstacle(boolean isObstacle){
        if(isObstacle){
            speak(profile.OBSTACLE_WARN);
        }
    }

    // 红绿灯检测语音播报
    /*public void speakRedgreenLight(boolean isExistsRedgreenLight){
        String redgreenLight = isExistsRedgreenLight?"前方有红绿灯":"前方没有红绿灯";
        speak(redgreenLight);
    }*/

    // 斑马线检测语音播报
    public void speakZebraCrossing(boolean isExistsZebraCrossing){
        String zebraCrossing = isExistsZebraCrossing? profile.EIXST_ZEBRACROSSING : profile.NONE_ZEBRACROSSING;
        speak(zebraCrossing);
    }

    // 周围门店语音播报
    public void speakStoreName(String storeOcr){
        if(storeOcr != null && storeOcr.length() != 0){
            speak("店名"+storeOcr);
        }else{
            speak("无店铺");
        }
    }

    //  时间地点朝向语音播报
    public void speakreport(){
        speak(getDateandTime()+"位于"+getAddress()+getOrientation());
    }


    // 语音播报
    public void speak(String data){
        // 设置音调，值越大声音越尖（女生），值越小则变成男声,1.0是常规
        textToSpeech.setPitch(0.9f);
        // 设置语速
        textToSpeech.setSpeechRate(3.0f);
        //输入中文，若不支持的设备则不会读出来
        textToSpeech.speak(data, TextToSpeech.QUEUE_FLUSH, null, null);
    }

    // 获取时间
    public String getDateandTime(){
        Calendar calendar = Calendar.getInstance();
        int year = calendar.get(Calendar.YEAR);
        int month = calendar.get(Calendar.MONTH)+1;
        int day = calendar.get(Calendar.DAY_OF_MONTH);
        int hour = calendar.get(Calendar.HOUR_OF_DAY);
        int minute = calendar.get(Calendar.MINUTE);
        return year+"年"+month+"月"+day+"日"+hour+"时"+minute+"分";
    }

    // 获取朝向
    public String getOrientation(){
        // 范围是[-180，0](0,180]
        float bearing = mSensorhelper.getmAngle();
        if (bearing > -22.5 && bearing <= 22.5){
            return profile.NORTH;
        }else if (bearing > 22.5 && bearing <= 67.5){
            return profile.NORTHEAST;
        }else if (bearing >  67.5 && bearing <= 112.5){
            return profile.EAST;
        }else if (bearing > 112.5 && bearing <= 157.5){
            return profile.SOUTHEAST;
        }else if ((bearing > 157.5 && bearing <= 180)||(bearing >= -157.5 && bearing < -180)){
            return profile.SOUTH;
        }else if (bearing > -157.5 && bearing <= -112.5){
            return profile.SOUTHWEST;
        }else if (bearing > -112.5 && bearing <= -67.5){
            return profile.WEST;
        }else if (bearing > -67.5 && bearing <= -22.5){
            return profile.NORTHWEST;
        }else {
            return profile.ORIENTATION_ERROR;
        }
    }

    // ---------getters and setters-----------
    public String getAddress() {
        return address;
    }

    public void setAddress(String address) {
        this.address = address;
    }

    public float getBearing() {
        return bearing;
    }

    public void setBearing(float bearing) {
        this.bearing = bearing;
    }

    public Context getmContext() {
        return mContext;
    }

    public void setmContext(Context mContext) {
        this.mContext = mContext;
    }

    public SensorEventHelper getmSensorhelper() {
        return mSensorhelper;
    }

    public void setmSensorhelper() {
        this.mSensorhelper = new SensorEventHelper(this.getmContext());
        mSensorhelper.registerSensorListener();
    }

    public CameraCapture getmCameraCapt() {
        return mCameraCapt;
    }

    public void setmCameraCapt(CameraCapture mCameraCapt) {
        this.mCameraCapt = mCameraCapt;
    }

    public TextToSpeech getTextToSpeech() {
        return textToSpeech;
    }

    public void setTextToSpeech(TextToSpeech textToSpeech) {
        this.textToSpeech = textToSpeech;
    }
}
