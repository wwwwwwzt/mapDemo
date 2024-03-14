package com.tencent.yolov5ncnn;

import java.nio.file.FileAlreadyExistsException;

public class profile {
    // 盲杖各功能键按钮
    public static final byte BUTTON_ALARM = 0x02;
    public static final byte GYRO_ALARM = 0x10;
    public static final byte CONNECTED = 0x40;
    public static final byte UNCONNECTED = 0x41;
    public static final byte DISCONNECTED = 0x42;
    public static final int OBSTACLE = 0;
    public static final int REDGREENLIGHT = 1;
    public static final int STORE = 2;
    public static final int AUDIOREPORT = 4;
    public static final int ZEBRACROSSING = 3;
    public static final int BUS = 5;

    // 检测时的flag
    public static final int REDGREENLIGHT_FLAG = 110;
    public static final int OBSTACLE_FLAG = 111;
    public static final int ZEBRACROSSING_FLAG = 101;
    public static final int STORE_FLAG = 100;
    public static final int PUASE_FLAG = -1;

    // 语义分割及红绿灯 类别名
    public static final String ZEBRACROSSING_CLASSNAME = "斑马线";
    public static final String STEPS_CLASSNAME = "台阶";
    public static final String TRAFFICLIGHT_CLASSNAME = "traffic light";

    // 各功能提示信息
    // 朝向
    public static final String NORTH = "朝向北方";
    public static final String SOUTH = "朝向南方";
    public static final String EAST = "朝向东方";
    public static final String WEST = "朝向西方";
    public static final String NORTHEAST = "朝向东北";
    public static final String NORTHWEST = "朝向西北";
    public static final String SOUTHEAST = "朝向东南";
    public static final String SOUTHWEST = "朝向西南";
    public static final String ORIENTATION_ERROR = "查询失败";
    // 障碍物检测
    public static final String OPEN_OBSTACLE = "障碍物检测已开启";
    public static final String CLOSE_OBSTACLE = "障碍物检测已关闭";
    public static final String OBSTACLE_WARN = "注意";
    public static final String OBSTACLE_XANGLE_ERROR = "倾角过大，请调整手机角度";
    // 红绿灯检测
    public static final String RED_TRAFFICLIGHT = "红灯";
    public static final String YELLOW_TRAFFICLIGHT = "黄灯";
    public static final String GREEN_TRAFFICLIGHT = "绿灯";
    public static final String NONE_COLOR_TRAFFICLIGHT = "未工作";
    public static final String NONE_TRAFFICLIGHT = "无红绿灯";
    // 斑马线检测
    public static final String EIXST_ZEBRACROSSING = "有斑马线";
    public static final String NONE_ZEBRACROSSING = "无斑马线";

    // 高德定位SHA码
    public static final String gaodeKey="9249a891bebc50e4794097f36a06516d";
}
