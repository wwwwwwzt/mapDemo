// Tencent is pleased to support the open source community by making ncnn available.
//
// Copyright (C) 2020 THL A29 Limited, a Tencent company. All rights reserved.
//
// Licensed under the BSD 3-Clause License (the "License"); you may not use this file except
// in compliance with the License. You may obtain a copy of the License at
//
// https://opensource.org/licenses/BSD-3-Clause
//
// Unless required by applicable law or agreed to in writing, software distributed
// under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
// CONDITIONS OF ANY KIND, either express or implied. See the License for the
// specific language governing permissions and limitations under the License.

#include <android/asset_manager_jni.h>
#include <android/bitmap.h>
#include <android/log.h>

#include <jni.h>

#include <string>
#include <vector>
#include <opencv2/core/core.hpp>
#include <algorithm>
#include <map>

// ncnn
#include "layer.h"
#include "net.h"
#include "benchmark.h"
#include "common.h"

static ncnn::UnlockedPoolAllocator g_blob_pool_allocator;
static ncnn::PoolAllocator g_workspace_pool_allocator;
const int dstHeight = 32;
ncnn::Net dbNet;
ncnn::Net crnnNet;

static ncnn::Net yolov5;
static ncnn::Net redGreenLight;

// YoloV5Focus YOLOV5FOCUS
class YoloV5Focus : public ncnn::Layer
{
public:
    YoloV5Focus()
    {
        one_blob_only = true;
    }

    virtual int forward(const ncnn::Mat& bottom_blob, ncnn::Mat& top_blob, const ncnn::Option& opt) const
    {
        int w = bottom_blob.w;
        int h = bottom_blob.h;
        int channels = bottom_blob.c;

        int outw = w / 2;
        int outh = h / 2;
        int outc = channels * 4;

        top_blob.create(outw, outh, outc, 4u, 1, opt.blob_allocator);
        if (top_blob.empty())
            return -100;

        #pragma omp parallel for num_threads(opt.num_threads)
        for (int p = 0; p < outc; p++)
        {
            const float* ptr = bottom_blob.channel(p % channels).row((p / channels) % 2) + ((p / channels) / 2);
            float* outptr = top_blob.channel(p);

            for (int i = 0; i < outh; i++)
            {
                for (int j = 0; j < outw; j++)
                {
                    *outptr = *ptr;

                    outptr += 1;
                    ptr += 2;
                }

                ptr += w;
            }
        }

        return 0;
    }
};

DEFINE_LAYER_CREATOR(YoloV5Focus)

struct Object
{
    float x;
    float y;
    float w;
    float h;
    int label;
    float prob;
};

static inline float intersection_area(const Object& a, const Object& b)
{
    if (a.x > b.x + b.w || a.x + a.w < b.x || a.y > b.y + b.h || a.y + a.h < b.y)
    {
        // no intersection
        return 0.f;
    }

    float inter_width = std::min(a.x + a.w, b.x + b.w) - std::max(a.x, b.x);
    float inter_height = std::min(a.y + a.h, b.y + b.h) - std::max(a.y, b.y);

    return inter_width * inter_height;
}

static void qsort_descent_inplace(std::vector<Object>& faceobjects, int left, int right)
{
    int i = left;
    int j = right;
    float p = faceobjects[(left + right) / 2].prob;

    while (i <= j)
    {
        while (faceobjects[i].prob > p)
            i++;

        while (faceobjects[j].prob < p)
            j--;

        if (i <= j)
        {
            // swap
            std::swap(faceobjects[i], faceobjects[j]);

            i++;
            j--;
        }
    }

    #pragma omp parallel sections
    {
        #pragma omp section
        {
            if (left < j) qsort_descent_inplace(faceobjects, left, j);
        }
        #pragma omp section
        {
            if (i < right) qsort_descent_inplace(faceobjects, i, right);
        }
    }
}

static void qsort_descent_inplace(std::vector<Object>& faceobjects)
{
    if (faceobjects.empty())
        return;

    qsort_descent_inplace(faceobjects, 0, faceobjects.size() - 1);
}

// 输出mat
float **pretty_print(const ncnn::Mat& m)
{
    int height = m.c, width = m.h * m.w;
    float **segOut = new float *[height];
    for(int i=0; i<height; ++i)
    {
        segOut[i] = new float[width];
    }

    for (int q=0; q<m.c; q++)
    {
        const float* ptr = m.channel(q);
        int p = 0;
        for (int y=0; y<m.h; y++)
        {
            for (int x=0; x<m.w; x++)
            {
                segOut[q][p++] = ptr[x];
                printf("%f ", ptr[x]);
            }
            ptr += m.w;
            printf("\n");
        }
        printf("------------------------\n");
    }
    return segOut;
}

/*
 * 非极大值抑制（NMS）———— 对所有的类分别执行
 * (1) 获取当前目标类别下所有bbx的信息
 * (2) 将bbx按照confidence从高到低排序,并记录当前confidence最大的bbx
 * (3) 计算最大confidence对应的bbx与剩下所有的bbx的IOU,移除所有大于IOU阈值的bbx
 * (4) 对剩下的bbx，循环执行(2)和(3)直到所有的bbx均满足要求（即不能再移除bbx）
 */
static void nms_sorted_bboxes(const std::vector<Object>& faceobjects, std::vector<int>& picked, float nms_threshold)
{
    picked.clear();

    const int n = faceobjects.size();

    std::vector<float> areas(n);
    for (int i = 0; i < n; i++)
    {
        areas[i] = faceobjects[i].w * faceobjects[i].h;
    }

    for (int i = 0; i < n; i++)
    {
        const Object& a = faceobjects[i];

        int keep = 1;
        for (int j = 0; j < (int)picked.size(); j++)
        {
            const Object& b = faceobjects[picked[j]];

            // intersection over union
            float inter_area = intersection_area(a, b);
            float union_area = areas[i] + areas[picked[j]] - inter_area;
            // float IoU = inter_area / union_area
            if (inter_area / union_area > nms_threshold)
                keep = 0;
        }

        if (keep)
            picked.push_back(i);
    }
}

static inline float sigmoid(float x)
{
    return static_cast<float>(1.f / (1.f + exp(-x)));
}

static void generate_proposals(const ncnn::Mat& anchors, int stride, const ncnn::Mat& in_pad, const ncnn::Mat& feat_blob, float prob_threshold, std::vector<Object>& objects)
{
    const int num_grid = feat_blob.h;

    int num_grid_x;
    int num_grid_y;
    if (in_pad.w > in_pad.h)
    {
        num_grid_x = in_pad.w / stride;
        num_grid_y = num_grid / num_grid_x;
    }
    else
    {
        num_grid_y = in_pad.h / stride;
        num_grid_x = num_grid / num_grid_y;
    }

    const int num_class = feat_blob.w - 5;

    const int num_anchors = anchors.w / 2;

    for (int q = 0; q < num_anchors; q++)
    {
        const float anchor_w = anchors[q * 2];
        const float anchor_h = anchors[q * 2 + 1];

        const ncnn::Mat feat = feat_blob.channel(q);

        for (int i = 0; i < num_grid_y; i++)
        {
            for (int j = 0; j < num_grid_x; j++)
            {
                const float* featptr = feat.row(i * num_grid_x + j);

                // find class index with max class score
                int class_index = 0;
                float class_score = -FLT_MAX;
                for (int k = 0; k < num_class; k++)
                {
                    float score = featptr[5 + k];
                    if (score > class_score)
                    {
                        class_index = k;
                        class_score = score;
                    }
                }

                float box_score = featptr[4];

                float confidence = sigmoid(box_score) * sigmoid(class_score);

                if (confidence >= prob_threshold)
                {
                    // yolov5/models/yolo.py Detect forward
                    // y = x[i].sigmoid()
                    // y[..., 0:2] = (y[..., 0:2] * 2. - 0.5 + self.grid[i].to(x[i].device)) * self.stride[i]  # xy
                    // y[..., 2:4] = (y[..., 2:4] * 2) ** 2 * self.anchor_grid[i]  # wh

                    float dx = sigmoid(featptr[0]);
                    float dy = sigmoid(featptr[1]);
                    float dw = sigmoid(featptr[2]);
                    float dh = sigmoid(featptr[3]);

                    float pb_cx = (dx * 2.f - 0.5f + j) * stride;
                    float pb_cy = (dy * 2.f - 0.5f + i) * stride;

                    float pb_w = pow(dw * 2.f, 2) * anchor_w;
                    float pb_h = pow(dh * 2.f, 2) * anchor_h;

                    float x0 = pb_cx - pb_w * 0.5f;
                    float y0 = pb_cy - pb_h * 0.5f;
                    float x1 = pb_cx + pb_w * 0.5f;
                    float y1 = pb_cy + pb_h * 0.5f;

                    Object obj;
                    obj.x = x0;
                    obj.y = y0;
                    obj.w = x1 - x0;
                    obj.h = y1 - y0;
                    obj.label = class_index;
                    obj.prob = confidence;

                    objects.push_back(obj);
                }
            }
        }
    }
}

// paddleOCR
std::vector<std::string> keys;
char *readKeysFromAssets(AAssetManager *mgr)
{
    if (mgr == NULL) {
        return NULL;
    }
    char *buffer;

    AAsset *asset = AAssetManager_open(mgr, "paddleocr_keys.txt", AASSET_MODE_UNKNOWN);
    if (asset == NULL) {
        return NULL;
    }

    off_t bufferSize = AAsset_getLength(asset);
    buffer = (char *) malloc(bufferSize + 1);
    buffer[bufferSize] = 0;
    int numBytesRead = AAsset_read(asset, buffer, bufferSize);
    AAsset_close(asset);

    return buffer;
}


std::vector<TextBox> findRsBoxes(const cv::Mat& fMapMat, const cv::Mat& norfMapMat,
                                 const float boxScoreThresh, const float unClipRatio)
{
    float minArea = 3;
    std::vector<TextBox> rsBoxes;
    rsBoxes.clear();
    std::vector<std::vector<cv::Point>> contours;
    cv::findContours(norfMapMat, contours, cv::RETR_LIST, cv::CHAIN_APPROX_SIMPLE);
    for (int i = 0; i < contours.size(); ++i)
    {
        float minSideLen, perimeter;
        std::vector<cv::Point> minBox = getMinBoxes(contours[i], minSideLen, perimeter);
        if (minSideLen < minArea)
            continue;
        float score = boxScoreFast(fMapMat, contours[i]);
        if (score < boxScoreThresh)
            continue;
        //---use clipper start---
        std::vector<cv::Point> clipBox = unClip(minBox, perimeter, unClipRatio);
        std::vector<cv::Point> clipMinBox = getMinBoxes(clipBox, minSideLen, perimeter);
        //---use clipper end---

        if (minSideLen < minArea + 2)
            continue;

        for (int j = 0; j < clipMinBox.size(); ++j)
        {
            clipMinBox[j].x = (clipMinBox[j].x / 1.0);
            clipMinBox[j].x = (std::min)((std::max)(clipMinBox[j].x, 0), norfMapMat.cols);

            clipMinBox[j].y = (clipMinBox[j].y / 1.0);
            clipMinBox[j].y = (std::min)((std::max)(clipMinBox[j].y, 0), norfMapMat.rows);
        }

        rsBoxes.emplace_back(TextBox{ clipMinBox, score });
    }
    reverse(rsBoxes.begin(), rsBoxes.end());

    return rsBoxes;
}

std::vector<TextBox> getTextBoxes(const cv::Mat & src, float boxScoreThresh, float boxThresh, float unClipRatio)
{
    int width = src.cols;
    int height = src.rows;
    int target_size = 640;
    // pad to multiple of 32
    int w = width;
    int h = height;
    float scale = 1.f;
    if (w > h)
    {
        scale = (float)target_size / w;
        w = target_size;
        h = h * scale;
    }
    else
    {
        scale = (float)target_size / h;
        h = target_size;
        w = w * scale;
    }

    ncnn::Mat input = ncnn::Mat::from_pixels_resize(src.data, ncnn::Mat::PIXEL_RGB, width, height, w, h);

    // pad to target_size rectangle
    int wpad = (w + 31) / 32 * 32 - w;
    int hpad = (h + 31) / 32 * 32 - h;
    ncnn::Mat in_pad;
    ncnn::copy_make_border(input, in_pad, hpad / 2, hpad - hpad / 2, wpad / 2, wpad - wpad / 2, ncnn::BORDER_CONSTANT, 0.f);

    const float meanValues[3] = { 0.485 * 255, 0.456 * 255, 0.406 * 255 };
    const float normValues[3] = { 1.0 / 0.229 / 255.0, 1.0 / 0.224 / 255.0, 1.0 / 0.225 / 255.0 };

    in_pad.substract_mean_normalize(meanValues, normValues);
    ncnn::Extractor extractor = dbNet.create_extractor();

    extractor.input("input0", in_pad);
    ncnn::Mat out;
    extractor.extract("out1", out);

    cv::Mat fMapMat(in_pad.h, in_pad.w, CV_32FC1, (float*)out.data);
    cv::Mat norfMapMat;
    norfMapMat = fMapMat > boxThresh;

    cv::dilate(norfMapMat, norfMapMat, cv::Mat(), cv::Point(-1, -1), 1);

    std::vector<TextBox> result = findRsBoxes(fMapMat, norfMapMat, boxScoreThresh, 2.0f);
    for(int i = 0; i < result.size(); i++)
    {
        for(int j = 0; j < result[i].boxPoint.size(); j++)
        {
            float x = (result[i].boxPoint[j].x-(wpad/2))/scale;
            float y = (result[i].boxPoint[j].y-(hpad/2))/scale;
            x = std::max(std::min(x,(float)(width-1)),0.f);
            y = std::max(std::min(y,(float)(height-1)),0.f);
            result[i].boxPoint[j].x = x;
            result[i].boxPoint[j].y = y;
        }
    }

    return result;
}

template<class ForwardIterator>
inline static size_t argmax(ForwardIterator first, ForwardIterator last) {
    return std::distance(first, std::max_element(first, last));
}

TextLine scoreToTextLine(const std::vector<float>& outputData, int h, int w)
{
    int keySize = keys.size();
    std::string strRes;
    std::vector<float> scores;
    int lastIndex = 0;
    int maxIndex;
    float maxValue;

    for (int i = 0; i < h; i++)
    {
        maxIndex = 0;
        maxValue = -1000.f;

        //std::vector<float> exps(w);
        //for (int j = 0; j < w; j++)
        //{
        //    exps[j] = outputData[i * w + j];
        //}
        //float partition = accumulate(exps.begin(), exps.end(), 0.0);//row sum
        maxIndex = int(argmax(outputData.begin()+i*w, outputData.begin()+i*w+w));
        maxValue = float(*std::max_element(outputData.begin()+i*w, outputData.begin()+i*w+w));// / partition;
        if (maxIndex > 0 && maxIndex < keySize && (!(i > 0 && maxIndex == lastIndex))) {
            scores.emplace_back(maxValue);
            strRes.append(keys[maxIndex - 1]);
        }
        lastIndex = maxIndex;
    }
    return { strRes, scores };
}

TextLine getTextLine(const cv::Mat & src)
{
    int64 start = cv::getTickCount();
    float scale = (float)dstHeight / (float)src.rows;
    int dstWidth = int((float)src.cols * scale);

    cv::Mat srcResize;
    resize(src, srcResize, cv::Size(dstWidth, dstHeight));

    ncnn::Mat input = ncnn::Mat::from_pixels(srcResize.data, ncnn::Mat::PIXEL_RGB,srcResize.cols, srcResize.rows);
    const float mean_vals[3] = { 127.5, 127.5, 127.5 };
    const float norm_vals[3] = { 1.0 / 127.5, 1.0 / 127.5, 1.0 / 127.5 };
    input.substract_mean_normalize(mean_vals, norm_vals);

    ncnn::Extractor extractor = crnnNet.create_extractor();
    //extractor.set_num_threads(2);
    extractor.input("input", input);

    ncnn::Mat out;
    extractor.extract("out", out);
    float* floatArray = (float*)out.data;
    std::vector<float> outputData(floatArray, floatArray + out.h * out.w);
    TextLine res= scoreToTextLine(outputData, out.h, out.w);
    return res;
}

std::vector<TextLine> getTextLines(std::vector<cv::Mat> & partImg) {
    int size = partImg.size();
    std::vector<TextLine> textLines(size);
    for (int i = 0; i < size; ++i)
    {
        TextLine textLine = getTextLine(partImg[i]);
        textLines[i] = textLine;
    }
    return textLines;
}

// paddleOCR
// FIXME DeleteGlobalRef is missing for objClsOCR
static jclass objClsOCR = NULL;
static jmethodID constructortorIdOCR;
static jfieldID x0IdOCR;
static jfieldID y0IdOCR;
static jfieldID x1IdOCR;
static jfieldID y1IdOCR;
static jfieldID x2IdOCR;
static jfieldID y2IdOCR;
static jfieldID x3IdOCR;
static jfieldID y3IdOCR;
static jfieldID labelIdOCR;
static jfieldID probIdOCR;

extern "C" {

// FIXME DeleteGlobalRef is missing for objClsOCR
static jclass objCls = NULL;
static jclass objRedGreenLight = NULL;
static jmethodID constructortorId;
static jmethodID constructortorIdRedGreenLight;
static jfieldID xId;
static jfieldID yId;
static jfieldID wId;
static jfieldID hId;
static jfieldID labelId;
static jfieldID probId;
static jfieldID xIdRedGreenLight;
static jfieldID yIdRedGreenLight;
static jfieldID wIdRedGreenLight;
static jfieldID hIdRedGreenLight;
static jfieldID labelIdRedGreenLight;
static jfieldID probIdRedGreenLight;



JNIEXPORT jint JNI_OnLoad(JavaVM* vm, void* reserved)
{
    __android_log_print(ANDROID_LOG_DEBUG, "YoloV5Ncnn", "JNI_OnLoad");

    ncnn::create_gpu_instance();

    return JNI_VERSION_1_4;
}

JNIEXPORT void JNI_OnUnload(JavaVM* vm, void* reserved)
{
    __android_log_print(ANDROID_LOG_DEBUG, "YoloV5Ncnn", "JNI_OnUnload");

    ncnn::destroy_gpu_instance();
}

/**
 * @brief 读取YoloV5模型
 * @param env JNI接口指针
 * @param assetManager 负责二进制文件（网络模型）数据读取
 * @return YoloV5模型是否加载成功
 */
// public native boolean Init(AssetManager mgr);
JNIEXPORT jboolean JNICALL Java_com_tencent_yolov5ncnn_YoloV5Ncnn_Init(JNIEnv* env, jobject thiz, jobject assetManager)
{
    ncnn::Option opt;
    opt.lightmode = true;
    opt.num_threads = 4;
    opt.blob_allocator = &g_blob_pool_allocator;
    opt.workspace_allocator = &g_workspace_pool_allocator;
    opt.use_packing_layout = true;

    // use vulkan compute
    if (ncnn::get_gpu_count() != 0)
        opt.use_vulkan_compute = true;

    AAssetManager* mgr = AAssetManager_fromJava(env, assetManager);

    yolov5.opt = opt;

    yolov5.register_custom_layer("YoloV5Focus", YoloV5Focus_layer_creator);

    // init param
    {
        //int ret = yolov5.load_param(mgr, "yolov5s-opt.param");
        int ret = yolov5.load_param(mgr, "best-sim-opt-fp16.param");
//        int ret = yolov5.load_param(mgr, "best_focus_opt.param");
        if (ret != 0)
        {
            __android_log_print(ANDROID_LOG_DEBUG, "YoloV5Ncnn", "load_param failed");
            return JNI_FALSE;
        }
    }

    // init bin
    {
        // int ret = yolov5.load_model(mgr, "yolov5s-opt.bin");
        int ret = yolov5.load_model(mgr, "best-sim-opt-fp16.bin");
//        int ret = yolov5.load_model(mgr, "best_focus_opt.bin");
        if (ret != 0)
        {
            __android_log_print(ANDROID_LOG_DEBUG, "YoloV5Ncnn", "load_model failed");
            return JNI_FALSE;
        }
    }

    // init jni glue
    jclass localObjCls = env->FindClass("com/tencent/yolov5ncnn/YoloV5Ncnn$Obj");
    objCls = reinterpret_cast<jclass>(env->NewGlobalRef(localObjCls));

    constructortorId = env->GetMethodID(objCls, "<init>", "(Lcom/tencent/yolov5ncnn/YoloV5Ncnn;)V");

    xId = env->GetFieldID(objCls, "x", "F");
    yId = env->GetFieldID(objCls, "y", "F");
    wId = env->GetFieldID(objCls, "w", "F");
    hId = env->GetFieldID(objCls, "h", "F");
    labelId = env->GetFieldID(objCls, "label", "Ljava/lang/String;");
    probId = env->GetFieldID(objCls, "prob", "F");

    return JNI_TRUE;
}

/**
 * @brief YoloV5模型检测目标
 * @param bitmap Bitmap对象的引用，选取的图像
 * @param use_gpu 是否使用GPU
 * @return 检测结果的数组
 */
// public native Obj[] Detect(Bitmap bitmap, boolean use_gpu);
JNIEXPORT jobjectArray JNICALL Java_com_tencent_yolov5ncnn_YoloV5Ncnn_Detect(JNIEnv* env, jobject thiz, jobject bitmap, jboolean use_gpu)
{
    if (use_gpu == JNI_TRUE && ncnn::get_gpu_count() == 0)
    {
        return NULL;
        //return env->NewStringUTF("no vulkan capable gpu");
    }

    double start_time = ncnn::get_current_time();

    AndroidBitmapInfo info;
    AndroidBitmap_getInfo(env, bitmap, &info);
    const int width = info.width;
    const int height = info.height;
    if (info.format != ANDROID_BITMAP_FORMAT_RGBA_8888)
        return NULL;

    // ncnn from bitmap
    const int target_size = 640;

    // letterbox pad to multiple of 32
    int w = width;
    int h = height;
    float scale = 1.f;
    if (w > h)
    {
        scale = (float)target_size / w;
        w = target_size;
        h = h * scale;
    }
    else
    {
        scale = (float)target_size / h;
        h = target_size;
        w = w * scale;
    }

    ncnn::Mat in = ncnn::Mat::from_android_bitmap_resize(env, bitmap, ncnn::Mat::PIXEL_RGB, w, h);

    // pad to target_size rectangle
    // yolov5/utils/datasets.py letterbox
    int wpad = (w + 31) / 32 * 32 - w;
    int hpad = (h + 31) / 32 * 32 - h;
    ncnn::Mat in_pad;
    ncnn::copy_make_border(in, in_pad, hpad / 2, hpad - hpad / 2, wpad / 2, wpad - wpad / 2, ncnn::BORDER_CONSTANT, 114.f);

    // yolov5
    std::vector<Object> objects;
    {
        const float prob_threshold = 0.25f;
        const float nms_threshold = 0.45f;
        // 语义分割过滤太小像素块阈值
        const float seg_threshold = 0.02f;

        const float norm_vals[3] = {1 / 255.f, 1 / 255.f, 1 / 255.f};
        in_pad.substract_mean_normalize(0, norm_vals);

        ncnn::Extractor ex = yolov5.create_extractor();

        ex.set_vulkan_compute(use_gpu);

        ex.input("images", in_pad);

        std::vector<Object> proposals;

        // anchor setting from yolov5/models/yolov5s.yaml

        // stride 8
        {
            ncnn::Mat out;
            ex.extract("output", out);

            ncnn::Mat anchors(6);
            anchors[0] = 10.f;
            anchors[1] = 13.f;
            anchors[2] = 16.f;
            anchors[3] = 30.f;
            anchors[4] = 33.f;
            anchors[5] = 23.f;

            std::vector<Object> objects8;
            generate_proposals(anchors, 8, in_pad, out, prob_threshold, objects8);

            proposals.insert(proposals.end(), objects8.begin(), objects8.end());
        }

        // stride 16
        {
            ncnn::Mat out;
            ex.extract("735", out);

            ncnn::Mat anchors(6);
            anchors[0] = 30.f;
            anchors[1] = 61.f;
            anchors[2] = 62.f;
            anchors[3] = 45.f;
            anchors[4] = 59.f;
            anchors[5] = 119.f;

            std::vector<Object> objects16;
            generate_proposals(anchors, 16, in_pad, out, prob_threshold, objects16);

            proposals.insert(proposals.end(), objects16.begin(), objects16.end());
        }

        // stride 32
        {
            ncnn::Mat out;
            ex.extract("749",out);

            ncnn::Mat anchors(6);
            anchors[0] = 116.f;
            anchors[1] = 90.f;
            anchors[2] = 156.f;
            anchors[3] = 198.f;
            anchors[4] = 373.f;
            anchors[5] = 326.f;

            std::vector<Object> objects32;
            generate_proposals(anchors, 32, in_pad, out, prob_threshold, objects32);

            proposals.insert(proposals.end(), objects32.begin(), objects32.end());
        }

        // sort all proposals by score from highest to lowest
        qsort_descent_inplace(proposals);

        // apply nms with nms_threshold
        std::vector<int> picked;
        nms_sorted_bboxes(proposals, picked, nms_threshold);

        int count = picked.size();

        objects.resize(count);
        for (int i = 0; i < count; i++)
        {
            objects[i] = proposals[picked[i]];

            // adjust offset to original unpadded
            float x0 = (objects[i].x - (wpad / 2)) / scale;
            float y0 = (objects[i].y - (hpad / 2)) / scale;
            float x1 = (objects[i].x + objects[i].w - (wpad / 2)) / scale;
            float y1 = (objects[i].y + objects[i].h - (hpad / 2)) / scale;

            // clip
            x0 = std::max(std::min(x0, (float)(width - 1)), 0.f);
            y0 = std::max(std::min(y0, (float)(height - 1)), 0.f);
            x1 = std::max(std::min(x1, (float)(width - 1)), 0.f);
            y1 = std::max(std::min(y1, (float)(height - 1)), 0.f);

            objects[i].x = x0;
            objects[i].y = y0;
            objects[i].w = x1 - x0;
            objects[i].h = y1 - y0;
        }

        // segmentation
        {
            ncnn::Mat out;
            ex.extract("output_se", out);
            std::map<int, int> out_channel;
            for(int i = 0; i < out.c; i++){
                out_channel[i] = 0;
            }
            // 每一格的最大值和对应的层数
            for (int y = 0; y < out.h; y++) {
                for (int x = 0; x < out.w; x++) {
                    std::vector<float> p_vector(out.c);
                    for (int ch = 0; ch < out.c; ch++) {
                        // Map
                        p_vector[ch] = out.channel(ch)[x + y * out.w];
                    }
                    // max
                    size_t m = std::distance(p_vector.begin(), std::max_element(p_vector.begin(), p_vector.end()));
                    out_channel[m]++;
                }
            }
            for (int j = 1; j < out_channel.size(); j++){
                float areaProportion = float(out_channel[j])/float(out.w*out.h);
                if(out_channel[j] > 0){
                    if(j < 3 && areaProportion < seg_threshold){
                        continue;
                    }
                    int l = objects.size();
                    objects.resize(l + 1);
                    objects[l].x = 1.7f;
                    objects[l].y = 1.7;
                    objects[l].w = 1.7f;
                    objects[l].h = 1.7f;
                    objects[l].label = 6 + j;
                    objects[l].prob = 0.17f;
                }
            }
        }
    }

    static const char* class_names[] = {"路障", "杆", "树", "汽车", "电动车", "自行车", "红绿灯", "台阶", "马路牙子", "斑马线"};

    jobjectArray jObjArray = env->NewObjectArray(objects.size(), objCls, NULL);

    for (size_t i=0; i<objects.size(); i++)
    {
        jobject jObj = env->NewObject(objCls, constructortorId, thiz);

        env->SetFloatField(jObj, xId, objects[i].x);
        env->SetFloatField(jObj, yId, objects[i].y);
        env->SetFloatField(jObj, wId, objects[i].w);
        env->SetFloatField(jObj, hId, objects[i].h);
        env->SetObjectField(jObj, labelId, env->NewStringUTF(class_names[objects[i].label]));
        env->SetFloatField(jObj, probId, objects[i].prob);

        env->SetObjectArrayElement(jObjArray, i, jObj);
    }

    double elasped = ncnn::get_current_time() - start_time;
    __android_log_print(ANDROID_LOG_DEBUG, "YoloV5Ncnn", "%.2fms   detect", elasped);

    return jObjArray;
}

// public native boolean Init(AssetManager mgr);
JNIEXPORT jboolean JNICALL Java_com_tencent_yolov5ncnn_PaddleOCRNcnn_Init(JNIEnv* env, jobject thiz, jobject assetManager)
{
    ncnn::Option opt;
    opt.lightmode = true;
    opt.num_threads = 4;
    opt.blob_allocator = &g_blob_pool_allocator;
    opt.workspace_allocator = &g_workspace_pool_allocator;
    opt.use_packing_layout = true;

    // use vulkan compute
    if (ncnn::get_gpu_count() != 0)
        opt.use_vulkan_compute = true;

    AAssetManager* mgr = AAssetManager_fromJava(env, assetManager);

    dbNet.opt = opt;
    crnnNet.opt = opt;

    // init param
    {
        int ret = dbNet.load_param(mgr, "pdocrv2.0_det-op.param");
        if (ret != 0)
        {
            __android_log_print(ANDROID_LOG_WARN, "PaddleocrNcnn", "load_dbNet_param failed");
            return JNI_FALSE;
        }

        ret = crnnNet.load_param(mgr, "pdocrv2.0_rec-op.param");

        if (ret != 0)
        {
            __android_log_print(ANDROID_LOG_WARN, "PaddleocrNcnn", "load_crnnNet_param failed");
            return JNI_FALSE;
        }
    }

    // init bin
    {
        int ret = dbNet.load_model(mgr, "pdocrv2.0_det-op.bin");
        if (ret != 0)
        {
            __android_log_print(ANDROID_LOG_WARN, "PaddleocrNcnn", "load_dbNet_model failed");
            return JNI_FALSE;
        }

        ret = crnnNet.load_model(mgr, "pdocrv2.0_rec-op.bin");

        if (ret != 0)
        {
            __android_log_print(ANDROID_LOG_WARN, "PaddleocrNcnn", "load_crnnNet_model failed");
            return JNI_FALSE;
        }
    }

    //load keys
    char *buffer = readKeysFromAssets(mgr);
    if (buffer != NULL) {
        std::istringstream inStr(buffer);
        std::string line;
        int size = 0;
        while (getline(inStr, line)) {
            keys.emplace_back(line);
            size++;
        }
        free(buffer);
    } else {
        return false;
    }


    // init jni glue
    jclass localObjCls = env->FindClass("com/tencent/yolov5ncnn/PaddleOCRNcnn$Obj");
    objClsOCR = reinterpret_cast<jclass>(env->NewGlobalRef(localObjCls));

    constructortorIdOCR = env->GetMethodID(objClsOCR, "<init>", "(Lcom/tencent/yolov5ncnn/PaddleOCRNcnn;)V");

    x0IdOCR = env->GetFieldID(objClsOCR, "x0", "F");
    y0IdOCR = env->GetFieldID(objClsOCR, "y0", "F");
    x1IdOCR = env->GetFieldID(objClsOCR, "x1", "F");
    y1IdOCR = env->GetFieldID(objClsOCR, "y1", "F");
    x2IdOCR = env->GetFieldID(objClsOCR, "x2", "F");
    y2IdOCR = env->GetFieldID(objClsOCR, "y2", "F");
    x3IdOCR = env->GetFieldID(objClsOCR, "x3", "F");
    y3IdOCR = env->GetFieldID(objClsOCR, "y3", "F");
    labelIdOCR = env->GetFieldID(objClsOCR, "label", "Ljava/lang/String;");
    probIdOCR = env->GetFieldID(objClsOCR, "prob", "F");

    return JNI_TRUE;
}

// public native Obj[] Detect(Bitmap bitmap, boolean use_gpu);
JNIEXPORT jobjectArray JNICALL Java_com_tencent_yolov5ncnn_PaddleOCRNcnn_Detect(JNIEnv* env, jobject thiz, jobject bitmap, jboolean use_gpu)
{
    if (use_gpu == JNI_TRUE && ncnn::get_gpu_count() == 0)
    {
        return NULL;
        //return env->NewStringUTF("no vulkan capable gpu");
    }

    AndroidBitmapInfo info;
    AndroidBitmap_getInfo(env, bitmap, &info);
    const int width = info.width;
    const int height = info.height;
    if (info.format != ANDROID_BITMAP_FORMAT_RGBA_8888)
        return NULL;

    ncnn::Mat in = ncnn::Mat::from_android_bitmap(env, bitmap, ncnn::Mat::PIXEL_RGB);

    cv::Mat rgb = cv::Mat::zeros(in.h,in.w,CV_8UC3);
    in.to_pixels(rgb.data, ncnn::Mat::PIXEL_RGB);

    std::vector<TextBox> objects;
    objects = getTextBoxes(rgb, 0.4, 0.3, 2.0);

    std::vector<cv::Mat> partImages = getPartImages(rgb, objects);
    std::vector<TextLine> textLines = getTextLines(partImages);

    if(textLines.size() > 0)
    {
        for(int i = 0; i < textLines.size(); i++)
            objects[i].text = textLines[i].text;
    }
    // objects to Obj[]
    jobjectArray jObjArray = env->NewObjectArray(objects.size(), objClsOCR, NULL);

    for (size_t i=0; i<objects.size(); i++)
    {
        jobject jObj = env->NewObject(objClsOCR, constructortorIdOCR, thiz);

        float x0 = objects[i].boxPoint[0].x;
        float y0 = objects[i].boxPoint[0].y;
        float x1 = objects[i].boxPoint[1].x;
        float y1 = objects[i].boxPoint[1].y;
        float x2 = objects[i].boxPoint[2].x;
        float y2 = objects[i].boxPoint[2].y;
        float x3 = objects[i].boxPoint[3].x;
        float y3 = objects[i].boxPoint[3].y;

        env->SetFloatField(jObj, x0IdOCR, x0);
        env->SetFloatField(jObj, y0IdOCR, y0);
        env->SetFloatField(jObj, x1IdOCR, x1);
        env->SetFloatField(jObj, y1IdOCR, y1);
        env->SetFloatField(jObj, x2IdOCR, x2);
        env->SetFloatField(jObj, y2IdOCR, y2);
        env->SetFloatField(jObj, x3IdOCR, x3);
        env->SetFloatField(jObj, y3IdOCR, y3);
        env->SetObjectField(jObj, labelIdOCR, env->NewStringUTF(objects[i].text.c_str()));
        env->SetFloatField(jObj, probIdOCR, objects[i].score);

        env->SetObjectArrayElement(jObjArray, i, jObj);
    }

    return jObjArray;
}

JNIEXPORT jboolean JNICALL Java_com_tencent_yolov5ncnn_Trafficlight_Init(JNIEnv *env, jobject thiz, jobject assetManager) {
    // TODO: implement Init()
    ncnn::Option opt;
    opt.lightmode = true;
    opt.num_threads = 4;
    opt.blob_allocator = &g_blob_pool_allocator;
    opt.workspace_allocator = &g_workspace_pool_allocator;
    opt.use_packing_layout = true;

    // use vulkan compute
    if (ncnn::get_gpu_count() != 0)
        opt.use_vulkan_compute = true;

    AAssetManager* mgr = AAssetManager_fromJava(env, assetManager);

    redGreenLight.opt = opt;

    redGreenLight.register_custom_layer("YoloV5Focus", YoloV5Focus_layer_creator);

    // init param
    {
        //int ret = yolov5.load_param(mgr, "yolov5s-opt.param");
        int ret = redGreenLight.load_param(mgr, "yolov5s-opt.param");
//        int ret = yolov5.load_param(mgr, "best_focus_opt.param");
        if (ret != 0)
        {
            __android_log_print(ANDROID_LOG_DEBUG, "YoloV5Ncnn", "load_param failed");
            return JNI_FALSE;
        }
    }

    // init bin
    {
        // int ret = yolov5.load_model(mgr, "yolov5s-opt.bin");
        int ret = redGreenLight.load_model(mgr, "yolov5s-opt.bin");
//        int ret = yolov5.load_model(mgr, "best_focus_opt.bin");
        if (ret != 0)
        {
            __android_log_print(ANDROID_LOG_DEBUG, "YoloV5Ncnn", "load_model failed");
            return JNI_FALSE;
        }
    }

    // init jni glue
    jclass localObjCls = env->FindClass("com/tencent/yolov5ncnn/Trafficlight$Obj");
    objRedGreenLight = reinterpret_cast<jclass>(env->NewGlobalRef(localObjCls));

    constructortorIdRedGreenLight = env->GetMethodID(objRedGreenLight, "<init>", "(Lcom/tencent/yolov5ncnn/Trafficlight;)V");

    xIdRedGreenLight = env->GetFieldID(objRedGreenLight, "x", "F");
    yIdRedGreenLight = env->GetFieldID(objRedGreenLight, "y", "F");
    wIdRedGreenLight = env->GetFieldID(objRedGreenLight, "w", "F");
    hIdRedGreenLight = env->GetFieldID(objRedGreenLight, "h", "F");
    labelIdRedGreenLight = env->GetFieldID(objRedGreenLight, "label", "Ljava/lang/String;");
    probIdRedGreenLight = env->GetFieldID(objRedGreenLight, "prob", "F");

    return JNI_TRUE;
}
JNIEXPORT jobjectArray JNICALL Java_com_tencent_yolov5ncnn_Trafficlight_Detect(JNIEnv *env, jobject thiz, jobject bitmap, jboolean use_gpu) {
    // TODO: implement Detect()
    if (use_gpu == JNI_TRUE && ncnn::get_gpu_count() == 0)
    {
        return NULL;
        //return env->NewStringUTF("no vulkan capable gpu");
    }

    double start_time = ncnn::get_current_time();

    AndroidBitmapInfo info;
    AndroidBitmap_getInfo(env, bitmap, &info);
    const int width = info.width;
    const int height = info.height;
    if (info.format != ANDROID_BITMAP_FORMAT_RGBA_8888)
        return NULL;

    // ncnn from bitmap
    const int target_size = 640;

    // letterbox pad to multiple of 32
    int w = width;
    int h = height;
    float scale = 1.f;
    if (w > h)
    {
        scale = (float)target_size / w;
        w = target_size;
        h = h * scale;
    }
    else
    {
        scale = (float)target_size / h;
        h = target_size;
        w = w * scale;
    }

    ncnn::Mat in = ncnn::Mat::from_android_bitmap_resize(env, bitmap, ncnn::Mat::PIXEL_RGB, w, h);

    // pad to target_size rectangle
    // yolov5/utils/datasets.py letterbox
    int wpad = (w + 31) / 32 * 32 - w;
    int hpad = (h + 31) / 32 * 32 - h;
    ncnn::Mat in_pad;
    ncnn::copy_make_border(in, in_pad, hpad / 2, hpad - hpad / 2, wpad / 2, wpad - wpad / 2, ncnn::BORDER_CONSTANT, 114.f);

    // yolov5
    std::vector<Object> objects;
    {
        // todo 调整置信度阈值
        const float prob_threshold = 0.25f;
        const float nms_threshold = 0.45f;

        const float norm_vals[3] = {1 / 255.f, 1 / 255.f, 1 / 255.f};
        in_pad.substract_mean_normalize(0, norm_vals);

        ncnn::Extractor ex = redGreenLight.create_extractor();

        ex.set_vulkan_compute(use_gpu);

        ex.input("images", in_pad);

        std::vector<Object> proposals;

        // anchor setting from yolov5/models/yolov5s.yaml

        // stride 8
        {
            ncnn::Mat out;
            ex.extract("output", out);

            ncnn::Mat anchors(6);
            anchors[0] = 10.f;
            anchors[1] = 13.f;
            anchors[2] = 16.f;
            anchors[3] = 30.f;
            anchors[4] = 33.f;
            anchors[5] = 23.f;

            std::vector<Object> objects8;
            generate_proposals(anchors, 8, in_pad, out, prob_threshold, objects8);

            proposals.insert(proposals.end(), objects8.begin(), objects8.end());
        }

        // stride 16
        {
            ncnn::Mat out;
            ex.extract("771", out);

            ncnn::Mat anchors(6);
            anchors[0] = 30.f;
            anchors[1] = 61.f;
            anchors[2] = 62.f;
            anchors[3] = 45.f;
            anchors[4] = 59.f;
            anchors[5] = 119.f;

            std::vector<Object> objects16;
            generate_proposals(anchors, 16, in_pad, out, prob_threshold, objects16);

            proposals.insert(proposals.end(), objects16.begin(), objects16.end());
        }

        // stride 32
        {
            ncnn::Mat out;
            ex.extract("791",out);

            ncnn::Mat anchors(6);
            anchors[0] = 116.f;
            anchors[1] = 90.f;
            anchors[2] = 156.f;
            anchors[3] = 198.f;
            anchors[4] = 373.f;
            anchors[5] = 326.f;

            std::vector<Object> objects32;
            generate_proposals(anchors, 32, in_pad, out, prob_threshold, objects32);

            proposals.insert(proposals.end(), objects32.begin(), objects32.end());
        }

        // sort all proposals by score from highest to lowest
        qsort_descent_inplace(proposals);

        // apply nms with nms_threshold
        std::vector<int> picked;
        nms_sorted_bboxes(proposals, picked, nms_threshold);

        int count = picked.size();

        objects.resize(count);
        for (int i = 0; i < count; i++)
        {
            objects[i] = proposals[picked[i]];

            // adjust offset to original unpadded
            float x0 = (objects[i].x - (wpad / 2)) / scale;
            float y0 = (objects[i].y - (hpad / 2)) / scale;
            float x1 = (objects[i].x + objects[i].w - (wpad / 2)) / scale;
            float y1 = (objects[i].y + objects[i].h - (hpad / 2)) / scale;

            // clip
            x0 = std::max(std::min(x0, (float)(width - 1)), 0.f);
            y0 = std::max(std::min(y0, (float)(height - 1)), 0.f);
            x1 = std::max(std::min(x1, (float)(width - 1)), 0.f);
            y1 = std::max(std::min(y1, (float)(height - 1)), 0.f);

            objects[i].x = x0;
            objects[i].y = y0;
            objects[i].w = x1 - x0;
            objects[i].h = y1 - y0;
        }
    }

    //     objects to Obj[]
    static const char* class_names[] = {
            "person", "bicycle", "car", "motorcycle", "airplane", "bus", "train", "truck", "boat", "traffic light",
            "fire hydrant", "stop sign", "parking meter", "bench", "bird", "cat", "dog", "horse", "sheep", "cow",
            "elephant", "bear", "zebra", "giraffe", "backpack", "umbrella", "handbag", "tie", "suitcase", "frisbee",
            "skis", "snowboard", "sports ball", "kite", "baseball bat", "baseball glove", "skateboard", "surfboard",
            "tennis racket", "bottle", "wine glass", "cup", "fork", "knife", "spoon", "bowl", "banana", "apple",
            "sandwich", "orange", "broccoli", "carrot", "hot dog", "pizza", "donut", "cake", "chair", "couch",
            "potted plant", "bed", "dining table", "toilet", "tv", "laptop", "mouse", "remote", "keyboard", "cell phone",
            "microwave", "oven", "toaster", "sink", "refrigerator", "book", "clock", "vase", "scissors", "teddy bear",
            "hair drier", "toothbrush"
    };

    jobjectArray jObjArray = env->NewObjectArray(objects.size(), objRedGreenLight, NULL);

    for (size_t i=0; i<objects.size(); i++)
    {
        jobject jObj = env->NewObject(objRedGreenLight, constructortorIdRedGreenLight, thiz);

        env->SetFloatField(jObj, xIdRedGreenLight, objects[i].x);
        env->SetFloatField(jObj, yIdRedGreenLight, objects[i].y);
        env->SetFloatField(jObj, wIdRedGreenLight, objects[i].w);
        env->SetFloatField(jObj, hIdRedGreenLight, objects[i].h);
        env->SetObjectField(jObj, labelIdRedGreenLight, env->NewStringUTF(class_names[objects[i].label]));
        env->SetFloatField(jObj, probIdRedGreenLight, objects[i].prob);

        env->SetObjectArrayElement(jObjArray, i, jObj);
    }

    double elasped = ncnn::get_current_time() - start_time;
    __android_log_print(ANDROID_LOG_DEBUG, "YoloV5Ncnn", "%.2fms   detect", elasped);

    return jObjArray;
}

}
