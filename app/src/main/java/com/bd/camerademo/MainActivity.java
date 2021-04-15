package com.bd.camerademo;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import android.Manifest;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraDevice;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.Size;
import android.view.Gravity;
import android.view.TextureView;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import com.bd.camerademo.camera2.Camera2Helper;
import com.bd.camerademo.camera2.Camera2Listener;
import com.bd.camerademo.util.ImageUtil;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static android.widget.Toast.makeText;

public class MainActivity extends AppCompatActivity implements ViewTreeObserver.OnGlobalLayoutListener, Camera2Listener {
    private static final String TAG = "MainActivity";
    private static final int ACTION_REQUEST_PERMISSIONS = 1;
    private Camera2Helper camera2Helper;
    private TextureView textureView;
    // 用于显示原始预览数据
    private ImageView ivOriginFrame;
    // 用于显示和预览画面相同的图像数据
    private ImageView ivPreviewFrame;
    // 默认打开的CAMERA
    private static final String CAMERA_ID = Camera2Helper.CAMERA_ID_BACK;
    // 图像帧数据，全局变量避免反复创建，降低gc频率
    private byte[] nv21;
    // 显示的旋转角度
    private int displayOrientation;
    // 是否手动镜像预览
    private boolean isMirrorPreview;
    // 实际打开的cameraId
    private String openedCameraId;
    // 当前获取的帧数
    private int currentIndex = 0;
    // 处理的间隔帧
    private static final int PROCESS_INTERVAL = 30;
    // 线程池
    private ExecutorService imageProcessExecutor;
    // 需要的权限
    private static final String[] NEEDED_PERMISSIONS = new String[]{
            Manifest.permission.CAMERA,
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
    };
    private SeekBar mSeekBar;
    private Button mButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LOCKED);
        imageProcessExecutor = Executors.newSingleThreadExecutor();
        Log.e(TAG, "onCreate: ");
        initView();
    }

    @Override
    protected void onStart() {
        super.onStart();
//        View decorView = getWindow().getDecorView();
//        int uiOptions = View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
//                | View.SYSTEM_UI_FLAG_FULLSCREEN;
//        decorView.setSystemUiVisibility(uiOptions);
    }

    private void initView() {
        Log.e(TAG, "initView: ");
        textureView = findViewById(R.id.texture_preview);
        mButton = findViewById(R.id.button);
        mButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent galleryIntent = new Intent(Intent.ACTION_GET_CONTENT);
                galleryIntent.addCategory(Intent.CATEGORY_OPENABLE);
                galleryIntent.setType("image/*");
                startActivityForResult(galleryIntent, 0);
            }
        });
        mSeekBar = findViewById(R.id.seekBar);
        mSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if(fromUser)
                {
                    try {
                        camera2Helper.updatePreview(progress+1);
                        double actual_progress = 0.0;
                        if(progress == 1)
                            actual_progress = 1.2;
                        else if(progress == 2)
                            actual_progress = 1.4;
                        else
                            actual_progress = 1.0;

                        Toast.makeText(getApplicationContext(), "zoom in:" + actual_progress + "倍", Toast.LENGTH_SHORT).show();
                    } catch (CameraAccessException e) {
                        e.printStackTrace();
                    }
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {

            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {

            }
        });
        //textureView.getViewTreeObserver().addOnGlobalLayoutListener(this);
        if (!checkPermissions(NEEDED_PERMISSIONS)) {
            ActivityCompat.requestPermissions(this, NEEDED_PERMISSIONS, ACTION_REQUEST_PERMISSIONS);
        } else {
            initCamera();
            Log.e("webber4", "init camera end");
        }
    }
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data){
        int degree = 0;
        super.onActivityResult(requestCode, resultCode, data);
        if(resultCode == RESULT_OK){
            Uri uri = data.getData();
            try {
                ExifInterface exifInterface = new ExifInterface(String.valueOf(uri));
                int orientation = exifInterface.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
                switch (orientation){
                    case ExifInterface.ORIENTATION_ROTATE_90:
                        degree = 90;
                        break;
                    case ExifInterface.ORIENTATION_ROTATE_180:
                        degree = 180;
                        break;
                    case ExifInterface.ORIENTATION_ROTATE_270:
                        degree = 270;
                        break;
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
            Log.e("webber9", "degree is " + degree);
            ContentResolver cr = this.getContentResolver();
            try{
                Bitmap bitmap =  BitmapFactory.decodeStream(cr.openInputStream(uri));
                ImageView imageView = (ImageView)findViewById(R.id.image_view);
                imageView.setImageBitmap(rotateBitmapByDegree(bitmap, degree));
            }catch (FileNotFoundException e){
                Log.e("Exception", e.getMessage(), e);
            }
        }
    }

    private static  Bitmap rotateBitmapByDegree(Bitmap bm, int degree){
        Bitmap returnBm = null;
        Matrix matrix = new Matrix();
        matrix.postRotate(degree);
        try{
            returnBm = Bitmap.createBitmap(bm, 0 , 0, bm.getWidth(), bm.getHeight(), matrix, true);
        }catch (OutOfMemoryError e){
        }
        if(returnBm == null){
            returnBm = bm;
        }
        if(bm != returnBm){
            bm.recycle();
        }
        return returnBm;
    }

    private boolean checkPermissions(String[] neededPermissions) {
        if (neededPermissions == null || neededPermissions.length == 0) {
            return true;
        }
        boolean allGranted = true;
        for (String neededPermission : neededPermissions) {
            allGranted &= ContextCompat.checkSelfPermission(this, neededPermission) == PackageManager.PERMISSION_GRANTED;
        }
        return allGranted;
    }

    void initCamera() {
        camera2Helper = new Camera2Helper.Builder()
                .cameraListener(this)
                .maxPreviewSize(new Point(1920, 1080))
                .minPreviewSize(new Point(1280, 720))
                .specificCameraId(CAMERA_ID)
                .context(getApplicationContext())
                .previewOn(textureView)
                .previewViewSize(new Point(textureView.getWidth(), textureView.getHeight()))
                .rotation(getWindowManager().getDefaultDisplay().getRotation())
                .build();
        camera2Helper.start();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        Log.e(TAG, "onRequestPermissionsResult: ");
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == ACTION_REQUEST_PERMISSIONS) {
            boolean isAllGranted = true;
            for (int grantResult : grantResults) {
                isAllGranted &= (grantResult == PackageManager.PERMISSION_GRANTED);
            }
            if (isAllGranted) {
                initCamera();
            } else {
                makeText(this, "权限被拒绝", Toast.LENGTH_SHORT).show();
            }
        }
    }

    @Override
    public void onGlobalLayout() {
        Log.e(TAG, "onGlobalLayout: ");
        textureView.getViewTreeObserver().removeOnGlobalLayoutListener(this);
        if (!checkPermissions(NEEDED_PERMISSIONS)) {
            ActivityCompat.requestPermissions(this, NEEDED_PERMISSIONS, ACTION_REQUEST_PERMISSIONS);
        } else {
            initCamera();
        }
    }

    @Override
    protected void onPause() {
        if (camera2Helper != null) {
            camera2Helper.stop();
        }
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (camera2Helper != null) {
            camera2Helper.start();
        }
    }


    @Override
    public void onCameraOpened(CameraDevice cameraDevice, String cameraId, final Size previewSize, final int displayOrientation, boolean isMirror) {
        Log.i(TAG, "onCameraOpened:  previewSize = " + previewSize.getWidth() + "x" + previewSize.getHeight());
        this.displayOrientation = displayOrientation;
        this.isMirrorPreview = isMirror;
        this.openedCameraId = cameraId;
    }

    @Override
    public void onPreview(final byte[] y, final byte[] u, final byte[] v, final Size previewSize, final int stride) {
        if (currentIndex++ % PROCESS_INTERVAL == 0) {
            imageProcessExecutor.execute(new Runnable() {
                @Override
                public void run() {
                    if (nv21 == null) {
                        Log.e("webber","on preview if 1");
                        nv21 = new byte[stride * previewSize.getHeight() * 3 / 2];
                    }
                    // 回传数据是YUV422
                    if (y.length / u.length == 2) {
                        Log.e("webber","on preview if 2");
                        ImageUtil.yuv422ToYuv420sp(y, u, v, nv21, stride, previewSize.getHeight());
                    }
                    // 回传数据是YUV420
                    else if (y.length / u.length == 4) {
                        Log.e("webber","on preview if 4");
                        ImageUtil.yuv420ToYuv420sp(y, u, v, nv21, stride, previewSize.getHeight());
                    }
                    YuvImage yuvImage = new YuvImage(nv21, ImageFormat.NV21, stride, previewSize.getHeight(), null);
                    Log.e("webber","yuvImage"+yuvImage.getHeight() + yuvImage.getWidth());
                    // ByteArrayOutputStream的close中其实没做任何操作，可不执行
                    ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();

                    // 由于某些stride和previewWidth差距大的分辨率，[0,previewWidth)是有数据的，而[previewWidth,stride)补上的U、V均为0，因此在这种情况下运行会看到明显的绿边
//                    yuvImage.compressToJpeg(new Rect(0, 0, stride, previewSize.getHeight()), 100, byteArrayOutputStream);

                    // 由于U和V一般都有缺损，因此若使用方式，可能会有个宽度为1像素的绿边
                    yuvImage.compressToJpeg(new Rect(0, 0, previewSize.getWidth(), previewSize.getHeight()), 100, byteArrayOutputStream);

                    // 为了删除绿边，抛弃一行像素
//                    yuvImage.compressToJpeg(new Rect(0, 0, previewSize.getWidth() - 1, previewSize.getHeight()), 100, byteArrayOutputStream);

                    byte[] jpgBytes = byteArrayOutputStream.toByteArray();
                    BitmapFactory.Options options = new BitmapFactory.Options();
                    options.inSampleSize = 4;
                    // 原始预览数据生成的bitmap
                    final Bitmap originalBitmap = BitmapFactory.decodeByteArray(jpgBytes, 0, jpgBytes.length, options);
                    Matrix matrix = new Matrix();
                    // 预览相对于原数据可能有旋转
                    matrix.postRotate(Camera2Helper.CAMERA_ID_BACK.equals(openedCameraId) ? displayOrientation : -displayOrientation);

                    // 对于前置数据，镜像处理；若手动设置镜像预览，则镜像处理；若都有，则不需要镜像处理
                    if (Camera2Helper.CAMERA_ID_FRONT.equals(openedCameraId) ^ isMirrorPreview) {
                        matrix.postScale(-1, 1);
                    }
                }
            });
        }
    }

    @Override
    public void onCameraClosed() {
        Log.i(TAG, "onCameraClosed: ");
    }

    @Override
    public void onCameraError(Exception e) {
        e.printStackTrace();
    }

    @Override
    protected void onDestroy() {
        if (imageProcessExecutor != null) {
            imageProcessExecutor.shutdown();
            imageProcessExecutor = null;
        }
        if (camera2Helper != null) {
            camera2Helper.release();
        }
        super.onDestroy();
    }

    @Override
    public void SetMaxZoomLevels(int maxLevels) {
        mSeekBar = findViewById(R.id.seekBar);
        mSeekBar.setMax(2);
        int val = getParaTxt("zoom");
        mSeekBar.setProgress(val);
    }

    public int getParaTxt(String parameterMode) {
        String dcimPath = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM).toString();
        String dataPath = dcimPath + "/data.txt";
        int ret = 0;
        File filePath = new File(dataPath);
        if (!filePath.exists()) {
            return ret;
        }

        try {
            InputStreamReader inputReader = new InputStreamReader(new FileInputStream(filePath), "UTF-8");
            BufferedReader buffer = new BufferedReader(inputReader);
            String lineStr = null;
            while ((lineStr = buffer.readLine()) != null) {
                String[] sourceStrArray = lineStr.split(":");
                if (sourceStrArray[0].equals(parameterMode)) {
                    String[] keyValue = sourceStrArray[1].split(",");
                    if(keyValue[0].equals("1.2"))
                        ret = 1;
                    else if(keyValue[0].equals("1.4"))
                        ret = 2;
                }
            }
            buffer.close();
            inputReader.close();
        } catch (IOException exception) {
            exception.printStackTrace();
        }
        return ret;
    }

    public void switchCamera(View view) {
        if (camera2Helper != null) {
            camera2Helper.switchCamera();
        }
    }

}
