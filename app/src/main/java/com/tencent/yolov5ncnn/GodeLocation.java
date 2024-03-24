package com.tencent.yolov5ncnn;

import android.content.Context;
import android.os.Handler;
import android.os.Message;
import android.util.Log;

import com.amap.api.location.AMapLocation;
import com.amap.api.location.AMapLocationClient;
import com.amap.api.location.AMapLocationClientOption;
import com.amap.api.location.AMapLocationListener;

public class GodeLocation {
    Context context;
    Handler handler;
    //声明AMapLocationClient类对象
    public AMapLocationClient mLocationClient = null;
    //声明定位回调监听器
    public AMapLocationListener mLocationListener=null;
    //声明AMapLocationClientOption对象
    public AMapLocationClientOption option  = null;

    public GodeLocation(Context c, Handler handler){
        this.context=c;
        this.handler=handler;
    }

    private void initalLocation(){
        mLocationListener= new AMapLocationListener() {
            @Override
            public void onLocationChanged(AMapLocation aMapLocation) {
                if (aMapLocation != null) {
                    if (aMapLocation.getErrorCode() == 0) {
                    //可在其中解析amapLocation获取相应内容。
                        Double lat=aMapLocation.getLatitude();//获取纬度
                        Double lng=aMapLocation.getLongitude();//获取经度
                        String address = aMapLocation.getAddress(); //获取地址
                        Float bearing = aMapLocation.getBearing(); // 获取方向角
                        Log.i("Amap Gode location get:","lat:"+String.valueOf(lat)+"lng"+String.valueOf(lng)+"address:"+aMapLocation.getAddress()+"direction:"+String.valueOf(aMapLocation.getBearing()));
                        Message msg=Message.obtain();
                        msg.what=2;
                        msg.obj=String.valueOf(lng)+"&"+String.valueOf(lat)+"&"+address+"&"+String.valueOf(bearing);
                        handler.sendMessage(msg);
                    }else {
                        //定位失败时，可通过ErrCode（错误码）信息来确定失败的原因，errInfo是错误信息，详见错误码表。
                        Log.e("AmapError","location Error, ErrCode:"
                                + aMapLocation.getErrorCode() + ", errInfo:"
                                + aMapLocation.getErrorInfo());
                    }
                }
            }
        };
        AMapLocationClient.updatePrivacyShow(context, true, true);
        AMapLocationClient.updatePrivacyAgree(context, true);
        try {
            mLocationClient = new AMapLocationClient(context);
        } catch (Exception e) {
            e.printStackTrace();
        }
        //设置定位回调监听
        mLocationClient.setLocationListener(mLocationListener);
        option=new AMapLocationClientOption();
        option.setSensorEnable(true); // 通过手机传感器获取方向角
        option.setLocationPurpose(AMapLocationClientOption.AMapLocationPurpose.SignIn);
        option.setLocationMode(AMapLocationClientOption.AMapLocationMode.Hight_Accuracy);
        option.setOnceLocationLatest(true);
        option.setHttpTimeOut(20000);
        if(mLocationClient != null){
            mLocationClient.setLocationOption(option);
            //设置场景模式后最好调用一次stop，再调用start以保证场景模式生效
            mLocationClient.stopLocation();
            mLocationClient.startLocation();
        }
    }

    public void startlocate(){
        initalLocation();
    }
}
