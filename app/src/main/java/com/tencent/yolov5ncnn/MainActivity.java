package com.tencent.yolov5ncnn;

import android.Manifest;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.provider.Settings;
import android.text.method.LinkMovementMethod;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;
import androidx.navigation.NavController;
import androidx.navigation.Navigation;
import androidx.navigation.fragment.NavHostFragment;
import androidx.navigation.ui.AppBarConfiguration;

import com.baidu.mapapi.SDKInitializer;
import com.tencent.yolov5ncnn.ui.home.HomeFragment;
import com.google.android.material.bottomnavigation.BottomNavigationView;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    BleLowEnergy ble;

    private  final int permissionCode = 1010;
    private FragmentManager fmanager;
    private FragmentTransaction ftransaction;
    public NavController navController = null;
    public Audio audio = null;
    public YoloV5Ncnn yoloV5Ncnn = null;
    public PaddleOCRNcnn paddleOCRNcnn = null;
    public Trafficlight trafficlight = null;
    public Obstacle obstacle = new Obstacle();
    LocationManager locationManager = null;
    public double lng=0.0;
    public double lat=0.0;
    public String address = ""; //地址
    public float bearing = 0.0f; // 方向角

    private SensorManager mSensorManager;

    String[] permissions = new String[]{Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.CAMERA,
            Manifest.permission.WRITE_EXTERNAL_STORAGE};
    AlertDialog lackPermissionDialog;

    private final Handler handler = new Handler(msg -> {
        dealsignal(msg.what);
        return false;
    });

    private final Handler locationhandler = new Handler(msg -> {
        locationchange(msg);
        return false;
    });

    @Override
    protected void onCreate(Bundle savedInstanceState) {

        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        // 设置屏幕常亮，建议加在onCreate或者onResume方法中
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        BottomNavigationView navView = findViewById(R.id.nav_view);
        // Passing each menu ID as a set of Ids because each
        // menu should be considered as top level destinations.
        AppBarConfiguration appBarConfiguration = new AppBarConfiguration.Builder(
                R.id.navigation_home, R.id.navigation_dashboard, R.id.navigation_notifications)
                .build();
        navController = Navigation.findNavController(this, R.id.nav_host_fragment);
//        NavigationUI.setupActionBarWithNavController(this, navController, appBarConfiguration);
//        NavigationUI.setupWithNavController(navView, navController);

        ActionBar actionBar = this.getSupportActionBar();
        actionBar.setTitle("盲杖应用");

        // 动态申请相应权限，弹出提示框
        checkedPermission();

        yoloV5Ncnn = new YoloV5Ncnn();
        boolean ret_init = yoloV5Ncnn.Init(getAssets());    // 调用Init方法
        if (!ret_init)
        {
            Log.e("MainActivity", "desenet Init failed");
        }

        paddleOCRNcnn = new PaddleOCRNcnn();
        boolean ret_init_paddleocr = paddleOCRNcnn.Init(getAssets());
        if (!ret_init_paddleocr)
        {
            Log.e("MainActivity", "paddleocrncnn Init failed");
        }

        trafficlight = new Trafficlight();
        boolean ret_init_redgreenlight = trafficlight.Init(getAssets());
        if (!ret_init_redgreenlight)
        {
            Log.e("MainActivity", "yolov5 Init failed");
        }

        audio = new Audio(this);
        GodeLocation godeLocation=new GodeLocation(this,locationhandler);
        godeLocation.startlocate();

        // 获取手机倾角
        mSensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        Sensor magneticSensor = mSensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD);
        Sensor accelerometerSensor = mSensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        mSensorManager.registerListener(listener, magneticSensor, SensorManager.SENSOR_DELAY_GAME);
        mSensorManager.registerListener(listener, accelerometerSensor, SensorManager.SENSOR_DELAY_GAME);

    }

//    设置本app字体
    @Override
    public Resources getResources() {
        Resources resources = super.getResources();
        if (resources != null) {
            android.content.res.Configuration configuration = resources.getConfiguration();
            if (configuration != null && configuration.fontScale != 0.8f) {
                configuration.fontScale = 0.8f;
                resources.updateConfiguration(configuration, resources.getDisplayMetrics());
            }
        }
        return resources;
    }
    private void checkedPermission() {
        List<String> permissionList = new ArrayList<>();
        for (String permission : permissions) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                permissionList.add(permission);
            }
        }
        if (permissionList.size()>0){
            ActivityCompat.requestPermissions(this,permissions, permissionCode);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        boolean haspermission = false;
        if (permissionCode == requestCode){
            for (int grantResult : grantResults) {
                if (grantResult == -1) {
                    haspermission = true;
                    break;
                }
            }
        }
        if (haspermission){
            permissionDialog();
        }
    }

    private void permissionDialog(){
        if (lackPermissionDialog == null){
            lackPermissionDialog = new AlertDialog.Builder(this)
                    .setTitle("权限不足")
                    .setMessage("当前缺少必要的权限，软件在使用过程中会产生崩溃。请前往设置添加相应权限。")
                    .setPositiveButton("前往设置", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialogInterface, int i) {
                            cancelPermissionDialog();
                            Uri packageUri = Uri.parse("package:"+getPackageName());
                            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,packageUri);
                            startActivity(intent);
                        }
                    })
                    .create();
        }
        lackPermissionDialog.show();
    }

    private void cancelPermissionDialog(){
        lackPermissionDialog.cancel();
    }

    public void bluetoothconnect(){
        ble = new BleLowEnergy(this, handler);
        ble.scanThenConnect();
    }
    public void bluetoothdisconnect(){
        if(ble!=null){
            ble.disconnect();
        }
        ble=null;
    }
    public BleLowEnergy getble(){
        return ble;
    }

    public void gotofragment(Fragment fr, Class f){
        fmanager = getSupportFragmentManager();
        ftransaction = fmanager.beginTransaction();
        Fragment mTargetFragment = null;
        try {
            mTargetFragment = (Fragment)f.newInstance();
        } catch (IllegalAccessException | InstantiationException e) {
            e.printStackTrace();
        }
        ftransaction.replace(R.id.configlayout, mTargetFragment);
//        ftransaction.show(mTargetFragment).hide(fr);
        ftransaction.addToBackStack(null); //将当前fragment加入到返回栈中
        ftransaction.commit();
        fmanager=null;
        ftransaction=null;
//        int num=fmanager.getBackStackEntryCount();
//        System.out.println(num);
    }
    public void dealsignal(int type){
//        获取homefragment对象，以便调用他的方法
        HomeFragment hfra=null;
        fmanager = getSupportFragmentManager();
        List<Fragment> fl=fmanager.getFragments();
        NavHostFragment navHostFragment = (NavHostFragment) fl.get(0);
        List<Fragment> childfragments = navHostFragment.getChildFragmentManager().getFragments();
        if (childfragments.size() > 0){
            for (int j = 0; j < childfragments.size(); j++) {
                Fragment fra = childfragments.get(j);
                if(fra.getClass().isAssignableFrom(HomeFragment.class)){
                    hfra=(HomeFragment)childfragments.get(j);
                }
            }
        }
//        判断信号
        switch (type){
            case profile.AUDIOREPORT:
                hfra.setbackcolor(R.color.white);
                audio.speakreport();
                break;
            case profile.OBSTACLE:
                hfra.setbackcolor(R.color.obstacle);
                audio.speak("开启障碍物检测");
                break;
            case profile.REDGREENLIGHT:
                hfra.setbackcolor(R.color.redgreenlight);
                audio.speak("开启红绿灯检测");
                break;

        }
    }

    public void locationchange(Message msg){
        if (msg.what == 2) {
            this.lng = Double.parseDouble(((String) msg.obj).split("&")[0]);
            this.lat = Double.parseDouble(((String) msg.obj).split("&")[1]);
            this.address = String.valueOf((String) msg.obj).split("&")[2];
            this.bearing = Float.parseFloat(((String) msg.obj).split("&")[3]);
        }
    }

    @Override
    protected void onDestroy() {
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        super.onDestroy();
        if(mSensorManager != null) {
            mSensorManager.unregisterListener(listener);
        }
    }

    private SensorEventListener listener = new SensorEventListener() {
        float[] accelerometerValues = new float[3];
        float[] magneticValues = new float[3];
        @Override
        public void onSensorChanged(SensorEvent sensorEvent) {
            if(sensorEvent.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
                accelerometerValues = sensorEvent.values.clone();
            }else if(sensorEvent.sensor.getType() == Sensor.TYPE_MAGNETIC_FIELD){
                magneticValues = sensorEvent.values.clone();
            }
            float[] inR = new float[9];
            float[] values = new float[3];
            SensorManager.getRotationMatrix(inR, null, accelerometerValues, magneticValues);
            float[] outR = new float[9];
            // 改变手机的坐标系，x,y轴互换
            SensorManager.remapCoordinateSystem(inR, SensorManager.AXIS_Y, SensorManager.AXIS_X, outR);
            SensorManager.getOrientation(outR, values);
            float xAngle = (float) Math.toDegrees(values[2]) + 180.0f;
            obstacle.setxAngle(xAngle);
            Log.d("MainActivity", "value[2] is " + xAngle);
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int i) {

        }
    };

    private void privacyDialog() {
        SharedPreferences sp = getSharedPreferences(CaneAuxiliaryApplication.SP_NAME, Context.MODE_PRIVATE);
        boolean ifAgree = sp.getBoolean(CaneAuxiliaryApplication.SP_KEY, false);
        if (ifAgree) {
            return;
        }
        // ‼️重要：设置用户是否同意SDK隐私协议，必须SDK初始化前按用户意愿设置
        // 隐私政策官网链接：https://lbsyun.baidu.com/index.php?title=openprivacy
        AlertDialog.Builder dialog = new AlertDialog.Builder(this);
        dialog.setTitle("温馨提示");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            View view = getLayoutInflater().inflate(R.layout.layout_privacy_dialog, null);
            TextView privacyTv = view.findViewById(R.id.privacy_tv);
            privacyTv.setMovementMethod(LinkMovementMethod.getInstance());
            dialog.setView(view);
        } else {
            dialog.setMessage(R.string.privacy_content);
        }
        dialog.setPositiveButton("同意", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                SharedPreferences.Editor editor = getSharedPreferences(CaneAuxiliaryApplication.SP_NAME, Context.MODE_PRIVATE).edit();
                editor.putBoolean(CaneAuxiliaryApplication.SP_KEY, true);
                editor.apply();
                SDKInitializer.setAgreePrivacy(MainActivity.this.getApplicationContext(), true);
                //LocationClient.setAgreePrivacy(true);
                dialog.dismiss();
            }
        });
        dialog.setNegativeButton("不同意", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                SDKInitializer.setAgreePrivacy(MainActivity.this.getApplicationContext(), false);
                //LocationClient.setAgreePrivacy(false);
                dialog.dismiss();
            }
        });
        dialog.setCancelable(false);
        dialog.show();

    }


}