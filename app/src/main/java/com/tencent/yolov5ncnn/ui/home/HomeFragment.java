package com.tencent.yolov5ncnn.ui.home;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import com.baidu.mapapi.model.LatLng;
import com.baidu.mapapi.realtimebus.RealTimeBusManager;
import com.baidu.mapsdkplatform.realtimebus.realtimebusoption.RealTimeNearbyBusOption;
import com.tencent.yolov5ncnn.BleLowEnergy;
import com.tencent.yolov5ncnn.Businfo;
import com.tencent.yolov5ncnn.Businformation;
import com.tencent.yolov5ncnn.CameraCapture;
import com.tencent.yolov5ncnn.GodeLocation;
import com.tencent.yolov5ncnn.MainActivity;
import com.tencent.yolov5ncnn.R;
import com.tencent.yolov5ncnn.Surroundings;
import com.tencent.yolov5ncnn.Obstacle;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.tencent.yolov5ncnn.profile;

import java.util.Objects;

public class HomeFragment extends Fragment {
    private HomeViewModel homeViewModel;

    View rootview=null;
    Boolean isconnect=false;
    // 障碍物检测按钮
    Button obstacledetect=null;
    // 红绿灯检测按钮
    Button redgreenlight=null;
    // 斑马线检测按钮
    Button zebracrossing = null;
    // 语音播报按钮
    Button audioreport=null;
    // 公交信息播报按钮
    Button busreport=null;
    // “周围商铺”按钮
    Button surroundings = null;
    // 拍摄图片显示区
    ImageView photoView = null;
    // 视频显示区
    //摄像头预览区
    SurfaceView sfView = null;
    CameraCapture mCameraCapt = new CameraCapture();

    ConstraintLayout lyout=null;
    BottomNavigationView navibarlyout=null;
    TextView textView=null;
    TextView twofunctext=null;
    MainActivity mainact =null;
    Businfo businfo=null;
    Surroundings sur_tool;
    Obstacle obstacle;

    BleLowEnergy ble;

    private final Handler handler = new Handler(msg -> {
        dealsignal(msg);
        return false;
    });

    Handler homefraghandler=new Handler(new Handler.Callback() {

        @Override
        public boolean handleMessage(@NonNull Message msg) {
            dealmessage(msg);
            return false;
        }
    });


    public View onCreateView(@NonNull LayoutInflater inflater,
                             ViewGroup container, Bundle savedInstanceState) {
        homeViewModel = new ViewModelProvider(this).get(HomeViewModel.class);

        View root = inflater.inflate(R.layout.fragment_home, container, false);
        mainact = (MainActivity) requireActivity();
        rootview=root;
        textView = root.findViewById(R.id.text_home);
        twofunctext=root.findViewById(R.id.twofunctext);
        ImageView bluetoothbutton=root.findViewById(R.id.bluetoothbutton);
        lyout=(ConstraintLayout)rootview.findViewById(R.id.frag_home_layout);
        navibarlyout=(BottomNavigationView)mainact.findViewById(R.id.nav_view);

        zebracrossing = (Button) root.findViewById(R.id.gdmap);
        redgreenlight=(Button) root.findViewById(R.id.redgreenlight);
        obstacledetect=(Button) root.findViewById(R.id.obstacledetect);

        audioreport=(Button) root.findViewById(R.id.audioreport);
        busreport=(Button) root.findViewById(R.id.busreport);
        surroundings = (Button)root.findViewById(R.id.surroundings);

        photoView = (ImageView) root.findViewById(R.id.photoView);

        sfView = (SurfaceView)root.findViewById(R.id.surfaceView);

        mainact.audio.setmCameraCapt(mCameraCapt);
        mCameraCapt.setSurfaceView(sfView);
        mCameraCapt.setImageView(photoView);
        mCameraCapt.setAudio(((MainActivity) requireActivity()).audio);

        // 初始化界面
        init();

        bluetoothbutton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if(mainact!=null){
                    if(!isconnect){
                        //a.bluetoothconnect();
                        ble = new BleLowEnergy(mainact, handler);
                        ble.scanThenConnect();
                        // 这一部分放到dealsignal(msg)里根据handler传的数据确定是否连接成功
                        /*
                        isconnect=true;
                        eyesclosetoopen();
                        textView.setText("蓝牙已连接");
                        ((MainActivity_interface)getActivity()).audio.speak("蓝牙已连接");
                        redgreenlight.setVisibility(View.VISIBLE);
                        obstacledetect.setVisibility(View.VISIBLE);
                        zebracrossing.setVisibility(View.VISIBLE);*/
                        // audioreport.setVisibility(View.VISIBLE);
                        // ble.scanThenConnect()中如果成功连接会有传递msg进入dealsignal(msg)，把isconnect赋值为true，否则就是连接不成功
                    }else{
                        // a.bluetoothdisconnect();
                        if(ble!=null){
                            ble.disconnect();
                        }
                        ble=null;
                        isconnect=false;
                        eyesopentoclose();
                        textView.setText("点击连接蓝牙");
                        mainact.audio.speak("蓝牙已断开");
                        mCameraCapt.setFlag(profile.PUASE_FLAG);
//                        redgreenlight.setVisibility(View.INVISIBLE);
//                        obstacledetect.setVisibility(View.INVISIBLE);
//                        twofunctext.setVisibility(View.INVISIBLE);
                        //audioreport.setVisibility(View.INVISIBLE);
                        setbackcolor(R.color.white);
                    }

                }
            }
        });

        zebracrossing.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                mCameraCapt.setYoloV5Ncnn(mainact.yoloV5Ncnn);
                mCameraCapt.setObstacle(mainact.obstacle);
                mCameraCapt.setFlag(profile.ZEBRACROSSING_FLAG);
                if (mCameraCapt.getCamera() == null){
                    mCameraCapt.OpenCarmeraThread();
                }
            }
        });

        redgreenlight.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                mCameraCapt.setRedGreenLight(mainact.trafficlight);
                mCameraCapt.setObstacle(mainact.obstacle);
                mCameraCapt.setFlag(profile.REDGREENLIGHT_FLAG);
                if (mCameraCapt.getCamera() == null){
                    mCameraCapt.OpenCarmeraThread();
                }
            }
        });

        obstacledetect.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if(mCameraCapt.getFlag() == profile.OBSTACLE_FLAG){
                    mCameraCapt.setFlag(profile.PUASE_FLAG);
                    mainact.audio.speak(profile.CLOSE_OBSTACLE);
                }else{
                    mainact.audio.speak(profile.OPEN_OBSTACLE);
                    mCameraCapt.setYoloV5Ncnn(mainact.yoloV5Ncnn);
                    mCameraCapt.setObstacle(mainact.obstacle);
                    mCameraCapt.setFirst_sleep(1);
                    mCameraCapt.setFlag(profile.OBSTACLE_FLAG);
                    if (mCameraCapt.getCamera() == null){
                        mCameraCapt.OpenCarmeraThread();
                    }
                }

            }
        });

        audioreport.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                mCameraCapt.setFlag(profile.PUASE_FLAG);
                Log.d("Amap Gode location gotten: ", "lat:" + String.valueOf(mainact.lat) + " lng" + String.valueOf(mainact.lng) + " address:" + mainact.address + " direction:" + String.valueOf(mainact.bearing));
                mainact.audio.setAddress(mainact.address);
                mainact.audio.speakreport();
            }
        });

        busreport.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                mainact.audio.speak("查询中，请稍候。");
                mainact.hasGotNearbyBusInfo = false;
                mCameraCapt.setFlag(profile.PUASE_FLAG);
                GodeLocation godeLocation=new GodeLocation(mainact,homefraghandler);
                godeLocation.startlocate();
                //获取poi兴趣点公交站
                double lng=mainact.lng;
                double lat=mainact.lat;

//                Businformation bus=new Businformation(lng,lat,homefraghandler);
//
//                new Thread(bus::reportword).start();
                RealTimeBusManager.getInstance().registerRealTimeBusListener(mainact);
                RealTimeNearbyBusOption realTimeNearbyBusOption = new RealTimeNearbyBusOption();
                // 设置城市id
                if(-1==mainact.cityID){
                    mainact.audio.speak("尚未获取所在城市，请确认已打开定位功能。稍后重试。");
                    return;
                } else {
                    realTimeNearbyBusOption.setCityID(mainact.cityID);
                }
                realTimeNearbyBusOption.setCityID(((MainActivity) requireActivity()).cityID);
                //realTimeNearbyBusOption.setCityID(257);
                // 设置当前经纬度
                realTimeNearbyBusOption.setLatLng(new LatLng(lat,lng));
                //realTimeNearbyBusOption.setLatLng(new LatLng(23.146217,113.254501));
                // 发起周边实时公交检索
                RealTimeBusManager.getInstance().realTimeNearbyBusSearch(realTimeNearbyBusOption);
                //RealTimeBusManager.getInstance().destroyRealTimeNearbyBus();
            }
        });

        surroundings.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    mCameraCapt.setPaddleOCRNcnn(mainact.paddleOCRNcnn);
                    mCameraCapt.setFlag(profile.STORE_FLAG);
                    if (mCameraCapt.getCamera() == null){
                        mCameraCapt.OpenCarmeraThread();
                    }
                }
            }

        );

        return root;
    }

    @Override
    public void onPause() {
        super.onPause();
        navibarlyout.setBackgroundColor(getResources().getColor(R.color.white));
    }

    @Override
    public void onResume() {
        super.onResume();

    }

    public void eyesclosetoopen(){
        ImageView bluetoothbutton=rootview.findViewById(R.id.bluetoothbutton);
        bluetoothbutton.setImageResource(R.drawable.openeyes);
    }
    public void eyesopentoclose(){
        ImageView bluetoothbutton=rootview.findViewById(R.id.bluetoothbutton);
        bluetoothbutton.setImageResource(R.drawable.closeeyes);
    }


    public void init(){
        twofunctext.setVisibility(View.INVISIBLE);
        //        先判断目前mainactivity里的连接状态
        if(mainact.getble()!=null){
            isconnect=true;
            eyesclosetoopen();
            textView.setText("蓝牙已连接");
        }else{
            isconnect=false;
            eyesopentoclose();
            textView.setText("点击连接蓝牙");
        }
        setbackcolor(R.color.white);
        // 初始化“语音播报”的朝向功能中用到的context和传感器监听
        mainact.audio.setmContext(getContext());
        mainact.audio.setmSensorhelper();
        // 初始化"周围商铺"功能中用到的context和传感器监听
        sur_tool = new Surroundings();
        sur_tool.setMcontext(getContext());
        sur_tool.setmSensorHelper();
    }
    public void setbackcolor(int color){
        lyout.setBackgroundColor(getResources().getColor(color));
        navibarlyout.setBackgroundColor(getResources().getColor(color));
    }

    public void invoke_GDMap(){
        Intent intent = new Intent();
        intent.setAction(Intent.ACTION_VIEW);
        intent.addCategory(Intent.CATEGORY_DEFAULT);

        //将功能Scheme以URI的方式传入data
        Uri uri = Uri.parse("androidamap://myLocation?sourceApplication=softname");
        intent.setData(uri);

        //启动该页面即可
        startActivity(intent);
    }

    private void dealmessage(Message msg){
        switch (msg.what){
            case 1:
                mainact.audio.speak((String)msg.obj);
                break;
            case 2:
                mainact.lng=Double.parseDouble(((String)msg.obj).split("&")[0]);
                mainact.lat=Double.parseDouble(((String)msg.obj).split("&")[1]);
                mainact.address = String.valueOf((String) msg.obj).split("&")[2];
                mainact.bearing = Float.parseFloat(((String) msg.obj).split("&")[3]);
                mainact.searchReverseGeoCode(mainact.lat, mainact.lng);
                break;
        }
    }

    private void dealsignal(Message msg){
        switch (msg.what){
            case profile.CONNECTED:
                eyesclosetoopen();
                textView.setText("蓝牙已连接");
                mainact.audio.speak("蓝牙已连接");
                break;
            case profile.UNCONNECTED:
                if(mCameraCapt.getCamera() != null){
                    mCameraCapt.setFlag(profile.PUASE_FLAG);
                    mCameraCapt.StopCarmera();
                }
                mainact.audio.speak("连接失败，请确认盲杖已开启或重启盲杖并再次连接");
                break;
            case profile.DISCONNECTED:
                if(mCameraCapt.getCamera() != null){
                    mCameraCapt.setFlag(profile.PUASE_FLAG);
                    mCameraCapt.StopCarmera();
                }
                isconnect=false;
                eyesopentoclose();
                textView.setText("点击连接蓝牙");
                mainact.audio.speak("蓝牙已断开");
                setbackcolor(R.color.white);
                break;
            case profile.AUDIOREPORT:
                mCameraCapt.setFlag(profile.PUASE_FLAG);
                mainact.audio.setAddress(mainact.address);
                mainact.audio.speakreport();
                break;
            case profile.OBSTACLE:
                if(mCameraCapt.getFlag() == profile.OBSTACLE_FLAG){
                    mCameraCapt.setFlag(profile.PUASE_FLAG);
                    mainact.audio.speak(profile.CLOSE_OBSTACLE);
                }else{
                    mainact.audio.speak(profile.OPEN_OBSTACLE);
                    mCameraCapt.setYoloV5Ncnn(mainact.yoloV5Ncnn);
                    mCameraCapt.setObstacle(mainact.obstacle);
                    mCameraCapt.setFirst_sleep(1);
                    mCameraCapt.setFlag(profile.OBSTACLE_FLAG);
                    if (mCameraCapt.getCamera() == null){
                        mCameraCapt.OpenCarmeraThread();
                    }
                }
                break;
            case profile.REDGREENLIGHT:
                mCameraCapt.setRedGreenLight(mainact.trafficlight);
                mCameraCapt.setObstacle(mainact.obstacle);
                mCameraCapt.setFlag(profile.REDGREENLIGHT_FLAG);
                if (mCameraCapt.getCamera() == null){
                    mCameraCapt.OpenCarmeraThread();
                }
                break;
            case profile.STORE:
                mCameraCapt.setPaddleOCRNcnn(mainact.paddleOCRNcnn);
                mCameraCapt.setFlag(profile.STORE_FLAG);
                if (mCameraCapt.getCamera() == null){
                    mCameraCapt.OpenCarmeraThread();
                }
                break;
            case profile.BUS:
                mCameraCapt.setFlag(profile.PUASE_FLAG);
                GodeLocation godeLocation=new GodeLocation(mainact, homefraghandler);
                godeLocation.startlocate();
                //获取poi兴趣点公交站
                double lng = mainact.lng;
                double lat = mainact.lat;

//                Businformation bus=new Businformation(lng, lat, homefraghandler);
//
//                new Thread(bus::reportword).start();
                RealTimeBusManager.getInstance().registerRealTimeBusListener(mainact);
                RealTimeNearbyBusOption realTimeNearbyBusOption = new RealTimeNearbyBusOption();
                // 设置城市id
                if(-1==mainact.cityID){
                    mainact.audio.speak("尚未获取所在城市，请确认已打开定位功能。稍后重试。");
                    return;
                } else {
                    realTimeNearbyBusOption.setCityID(mainact.cityID);
                }
                realTimeNearbyBusOption.setCityID(((MainActivity) requireActivity()).cityID);
                //realTimeNearbyBusOption.setCityID(257);
                // 设置当前经纬度
                realTimeNearbyBusOption.setLatLng(new LatLng(lat,lng));
                //realTimeNearbyBusOption.setLatLng(new LatLng(23.146217,113.254501));
                // 发起周边实时公交检索
                RealTimeBusManager.getInstance().realTimeNearbyBusSearch(realTimeNearbyBusOption);
                //RealTimeBusManager.getInstance().destroyRealTimeNearbyBus();
                break;
            case profile.ZEBRACROSSING:
                mCameraCapt.setYoloV5Ncnn(mainact.yoloV5Ncnn);
                mCameraCapt.setObstacle(mainact.obstacle);
                mCameraCapt.setFlag(profile.ZEBRACROSSING_FLAG);
                if (mCameraCapt.getCamera() == null){
                    mCameraCapt.OpenCarmeraThread();
                }
                break;
            default:

        }
    }
}