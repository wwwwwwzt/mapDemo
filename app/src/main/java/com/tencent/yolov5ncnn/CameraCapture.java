package com.tencent.yolov5ncnn;

import android.content.ActivityNotFoundException;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.hardware.Camera;
import android.os.AsyncTask;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.widget.ImageView;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

public class CameraCapture {
    private YoloV5Ncnn yoloV5Ncnn;
    private PaddleOCRNcnn paddleOCRNcnn;
    private Trafficlight trafficlight;
    private Audio audio;
    Surroundings surroundings = new Surroundings();
    private Obstacle obstacle;
    private double yAngle;
    private ImageView imageView;
    // 判断是障碍物还是红绿灯，斑马线，门店检测
    private int flag;
    // 每条语音播报都是一个线程，障碍物检测中出现“障碍物检测已开启”播报不完的情况，所以设置first_sleep,每次开启障碍物检测先睡1s
    private int first_sleep;

    public Camera getCamera() {
        return camera;
    }

    public void setCamera(Camera camera) {
        this.camera = camera;
    }

    private Camera camera = null;
    private SurfaceView surfaceView;
    private SurfaceHolder holder;
    private CustomPreviewCallback mPreviewCB = new CustomPreviewCallback();
    private int mWidth;
    private int mHeight;
    private int mFormatIndex;
    private static final String TAG = "CaptureC";

    public Bitmap getRawBitmap() {
        return rawBitmap;
    }

    public void setRawBitmap(Bitmap rawBitmap) {
        this.rawBitmap = rawBitmap;
    }

    private Bitmap rawBitmap = null;

    public void setSurfaceView(SurfaceView sfView){
        surfaceView = sfView;
        holder = sfView.getHolder();
        holder.addCallback(mPreviewCB);  //设置回调
        holder.setType(SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS);
    }
    /*
     * 打开前置摄像头
     */
    public  void OpenCarmeraThread(){
        new Thread(new Runnable() {
            @Override
            public void run() {
                if(OpenCamera()){
                    Log.i(TAG, "Start Camera Preview!");
                    StartCameraPreview();
                }
            }
        }).start();
    }
    /*
     * 停止视频捕获
     */
    public void StopCarmera(){
        if(null != camera){
            camera.setPreviewCallback(null); //！！这个必须在前，不然退出出错
            camera.stopPreview();
            camera.release();
            camera = null;
        }
    }

    /*
     * 打开前置相机:子线程中运行
     */
    private boolean OpenCamera(){
        if(camera!=null)return true;
        try{
            Camera.CameraInfo cameraInfo = new Camera.CameraInfo();
            int cameraCount = Camera.getNumberOfCameras(); // get cameras number

            for ( int camIdx = 0; camIdx < cameraCount;camIdx++ ) {
                Camera.getCameraInfo( camIdx, cameraInfo ); // get camerainfo
                if ( cameraInfo.facing ==Camera.CameraInfo.CAMERA_FACING_BACK ) { // 代表摄像头的方位，目前有定义值两个分别为CAMERA_FACING_FRONT前置和CAMERA_FACING_BACK后置
                    try {
                        camera = Camera.open( camIdx );
                        Log.i(TAG, "OpenCamera: " + camera.toString());
                    } catch (RuntimeException e) {
                        e.printStackTrace();
                    }
                }
            }
        }catch(ActivityNotFoundException e){
            e.printStackTrace();
            //return false;
        }
        return camera != null;
    }

    private void StartCameraPreview(){
        if (camera == null)return;
        try{
            camera.stopPreview();
            camera.setPreviewDisplay(holder);
            camera.setPreviewCallback(mPreviewCB);

            Camera.Parameters parameters = camera.getParameters();//获取摄像头参数
            mWidth = parameters.getPreviewSize().width;
            mHeight=parameters.getPreviewSize().height;
            mFormatIndex=parameters.getPreviewFormat();

            if (surfaceView.getResources().getConfiguration().orientation != Configuration.ORIENTATION_LANDSCAPE) {
                parameters.set("orientation", "portrait");
                camera.setDisplayOrientation(90);
                parameters.setRotation(90);
            }

            parameters.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE);//1连续对焦

            List<String> colorEffects = parameters.getSupportedColorEffects();
            for (String currentEffect : colorEffects) {
                if (currentEffect.equals(Camera.Parameters.EFFECT_SOLARIZE)) {
                    parameters.setColorEffect(Camera.Parameters.EFFECT_SOLARIZE);
                    break;
                }
            }
            camera.setParameters(parameters);

            camera.cancelAutoFocus();// 2如果要实现连续的自动对焦，这一句必须加上
            camera.startPreview();
        }catch(IOException e){
            e.printStackTrace();
        }
    }

    private class CustomPreviewCallback implements SurfaceHolder.Callback, Camera.PreviewCallback {
        private CaptureTask mCaptureTask;
        private static final String TAG = "CaptureCB";
        /**
         * 相机实时数据的回调
         * @param data   相机获取的数据，格式是YUV
         * @param camera 相应相机的对象
         */
        @Override
        public void onPreviewFrame(byte[] data, Camera camera) {
            if (mCaptureTask != null) {
                switch (mCaptureTask.getStatus()) {
                    case RUNNING:
                        return;
                    case PENDING:
                        mCaptureTask.cancel(false);
                        break;
                }
            }
            Log.i(TAG, "onPreviewFrame: 启动了Task");
            mCaptureTask = new CaptureTask(data, camera);
            mCaptureTask.execute((Void) null);
        }

        //
        @Override
        public void surfaceCreated(SurfaceHolder holder) {

        }

        @Override
        public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {

        }

        @Override
        public void surfaceDestroyed(SurfaceHolder holder) {

        }
    }

    private class CaptureTask extends AsyncTask {
        private byte[] mData;
        Camera mCamera;
        private static final String TAG = "CaptureT";

        //构造函数
        CaptureTask(byte[] data , Camera camera)
        {
            this.mData = data;
            this.mCamera = camera;
        }

        @Override
        protected Object doInBackground(Object[] params) {
            Camera.Parameters parameters = mCamera.getParameters();
            //parameters.setPreviewFrameRate(1);
            int imageFormat = parameters.getPreviewFormat();
            int w = parameters.getPreviewSize().width;
            int h = parameters.getPreviewSize().height;

            Rect rect = new Rect(0, 0, w, h);
            YuvImage yuvImg = new YuvImage(mData, imageFormat, w, h, null);
            try {
                ByteArrayOutputStream outputstream = new ByteArrayOutputStream();
                yuvImg.compressToJpeg(rect, 100, outputstream);
                rawBitmap = adjustPhotoRotation(BitmapFactory.decodeByteArray(outputstream.toByteArray(), 0, outputstream.size()),90);
                assert rawBitmap != null;
                Log.i(TAG, "onPreviewFrame: rawbitmap(" + rawBitmap.getWidth() + "," + rawBitmap.getHeight() + ")");

                if(flag == profile.OBSTACLE_FLAG){
                    if(first_sleep == 1){
                        Thread.sleep(1500);
                        first_sleep++;
                    }
                    imageView.setImageBitmap(rawBitmap);
                    String obstacleMessage = obstacle.obstacleDetect(yoloV5Ncnn, rawBitmap, imageView);
                    audio.speak(obstacleMessage);
                    if (obstacleMessage.equals(profile.OBSTACLE_XANGLE_ERROR)){
                        flag = profile.PUASE_FLAG;
                    }
                    if(flag == profile.OBSTACLE_FLAG){
                        Thread.sleep(1000*2);
                    }
                }else if(flag == profile.REDGREENLIGHT_FLAG){
                    imageView.setImageBitmap(rawBitmap);
                    String trafficlightStr = obstacle.TrafficlightDetect(trafficlight, rawBitmap, imageView);
                    audio.speak(trafficlightStr);
                    flag = profile.PUASE_FLAG;
                }else if(flag == profile.ZEBRACROSSING_FLAG){
                    imageView.setImageBitmap(rawBitmap);
                    boolean isZebra = obstacle.zebraCrossingDetect(yoloV5Ncnn, rawBitmap);
                    audio.speakZebraCrossing(isZebra);
                    flag = profile.PUASE_FLAG;
                }else if(flag == profile.STORE_FLAG){
                    imageView.setImageBitmap(rawBitmap);
                    String storeNames = surroundings.storeOCR(paddleOCRNcnn, rawBitmap, imageView);
                    audio.speakStoreName(storeNames);
                    flag = profile.PUASE_FLAG;
                }else {
                    flag = profile.PUASE_FLAG;
                }
            }
            catch (Exception e)
            {
                Log.e(TAG, "onPreviewFrame: 获取相机实时数据失败" + e.getLocalizedMessage());
            }
            return null;
        }
    }

    // 图片旋转任意角度
    private Bitmap adjustPhotoRotation(Bitmap bm, final int orientationDegree) {

        Matrix m = new Matrix();
        m.setRotate(orientationDegree, (float) bm.getWidth() / 2, (float) bm.getHeight() / 2);

        try {
            Bitmap bm1 = Bitmap.createBitmap(bm, 0, 0, bm.getWidth(), bm.getHeight(), m, true);

            return bm1;

        } catch (OutOfMemoryError ex) {
        }
        return null;

    }

    // getter and setter
    public YoloV5Ncnn getYoloV5Ncnn() {
        return yoloV5Ncnn;
    }

    public void setYoloV5Ncnn(YoloV5Ncnn yoloV5Ncnn) {
        this.yoloV5Ncnn = yoloV5Ncnn;
    }

    public PaddleOCRNcnn getPaddleOCRNcnn() {
        return paddleOCRNcnn;
    }

    public void setPaddleOCRNcnn(PaddleOCRNcnn paddleOCRNcnn) {
        this.paddleOCRNcnn = paddleOCRNcnn;
    }

    public Obstacle getObstacle() {
        return obstacle;
    }

    public void setObstacle(Obstacle obstacle) {
        this.obstacle = obstacle;
    }

    public int getFlag() {
        return flag;
    }

    public void setFlag(int flag) {
        this.flag = flag;
    }

    public ImageView getImageView() {
        return imageView;
    }

    public void setImageView(ImageView imageView) {
        this.imageView = imageView;
    }

    public Audio getAudio() {
        return audio;
    }

    public void setAudio(Audio audio) {
        this.audio = audio;
    }

    public Trafficlight getRedGreenLight() {
        return trafficlight;
    }

    public void setRedGreenLight(Trafficlight trafficlight) {
        this.trafficlight = trafficlight;
    }

    public double getyAngle() {
        return yAngle;
    }

    public void setyAngle(double yAngle) {
        this.yAngle = yAngle;
    }

    public void setFirst_sleep(int first_sleep) {
        this.first_sleep = first_sleep;
    }
}