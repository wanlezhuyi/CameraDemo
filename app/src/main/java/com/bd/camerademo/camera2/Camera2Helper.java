package com.bd.camerademo.camera2;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.util.Size;
import android.view.Surface;
import android.view.TextureView;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

public class Camera2Helper {
    private static final String TAG = "Camera2Helper";

    private Point maxPreviewSize;
    private Point minPreviewSize;

    public static final String CAMERA_ID_FRONT = "1";
    public static final String CAMERA_ID_BACK = "0";


    private String mCameraId;
    private String specificCameraId;
    private Camera2Listener camera2Listener;
    private TextureView mTextureView;
    private int rotation;
    private Point previewViewSize;
    private Point specificPreviewSize;
    private boolean isMirror;
    private Context context;
    private float mMaxZoom;

    /**
     * A {@link CameraCaptureSession } for camera preview.
     */
    private CameraCaptureSession mCaptureSession;

    /**
     * A reference to the opened {@link CameraDevice}.
     */
    private CameraDevice mCameraDevice;

    private Size mPreviewSize = new Size(3840, 2160);

    private Camera2Helper(Camera2Helper.Builder builder) {
        mTextureView = builder.previewDisplayView;
        specificCameraId = builder.specificCameraId;
        camera2Listener = builder.camera2Listener;
        rotation = builder.rotation;
        previewViewSize = builder.previewViewSize;
        specificPreviewSize = builder.previewSize;
        maxPreviewSize = builder.maxPreviewSize;
        minPreviewSize = builder.minPreviewSize;
        isMirror = builder.isMirror;
        context = builder.context;

        if (isMirror) {
            mTextureView.setScaleX(-1);
        }
    }

    public void switchCamera() {
        if (CAMERA_ID_BACK.equals(mCameraId)) {
            specificCameraId = CAMERA_ID_FRONT;
        } else if (CAMERA_ID_FRONT.equals(mCameraId)) {
            specificCameraId = CAMERA_ID_BACK;
        }
        stop();
        start();
    }

    public int getCameraMaxZoom() {
        Log.e("webber4", "max zoom is " + mMaxZoom);
        return (int) mMaxZoom;
    }

    private int getCameraOri(int rotation, String cameraId) {
        int degrees = rotation * 90;
        switch (rotation) {
            case Surface.ROTATION_0:
                degrees = 0;
                break;
            case Surface.ROTATION_90:
                degrees = 90;
                break;
            case Surface.ROTATION_180:
                degrees = 180;
                break;
            case Surface.ROTATION_270:
                degrees = 270;
                break;
            default:
                break;
        }
        int result;
        if (CAMERA_ID_FRONT.equals(cameraId)) {
            result = (mSensorOrientation + degrees) % 360;
            result = (360 - result) % 360;
        } else {
            result = (mSensorOrientation - degrees + 360) % 360;
        }
        Log.i(TAG, "getCameraOri: " + rotation + " " + result + " " + mSensorOrientation);
        return result;
    }

    private final TextureView.SurfaceTextureListener mSurfaceTextureListener
            = new TextureView.SurfaceTextureListener() {

        @Override
        public void onSurfaceTextureAvailable(SurfaceTexture texture, int width, int height) {
            Log.i(TAG, "onSurfaceTextureAvailable: ");
            Log.e("webber4", "open camera2");
            openCamera();
        }

        @Override
        public void onSurfaceTextureSizeChanged(SurfaceTexture texture, int width, int height) {
            Log.e("webber4", "onSurfaceTextureSizeChanged: ");
            configureTransform(width, height);
        }

        @Override
        public boolean onSurfaceTextureDestroyed(SurfaceTexture texture) {
            Log.e("webber4", "onSurfaceTextureDestroyed: ");
            return true;
        }

        @Override
        public void onSurfaceTextureUpdated(SurfaceTexture texture) {
            long timestamp = texture.getTimestamp();
            Log.e("webber4", "onSurfaceTextureUpdated, timestamp is:" + timestamp);
        }

    };

    private CameraDevice.StateCallback mDeviceStateCallback = new CameraDevice.StateCallback() {

        @Override
        public void onOpened(@NonNull CameraDevice cameraDevice) {
            Log.i(TAG, "onOpened: ");
            // This method is called when the camera is opened.  We start camera preview here.
            mCameraOpenCloseLock.release();
            mCameraDevice = cameraDevice;
            createCameraPreviewSession();
            if (camera2Listener != null) {
                camera2Listener.onCameraOpened(cameraDevice, mCameraId, mPreviewSize, getCameraOri(rotation, mCameraId), isMirror);
            }
        }

        @Override
        public void onDisconnected(@NonNull CameraDevice cameraDevice) {
            Log.i(TAG, "onDisconnected: ");
            mCameraOpenCloseLock.release();
            cameraDevice.close();
            mCameraDevice = null;
            if (camera2Listener != null) {
                camera2Listener.onCameraClosed();
            }
        }

        @Override
        public void onError(@NonNull CameraDevice cameraDevice, int error) {
            Log.i(TAG, "onError: ");
            mCameraOpenCloseLock.release();
            cameraDevice.close();
            mCameraDevice = null;

            if (camera2Listener != null) {
                camera2Listener.onCameraError(new Exception("error occurred, code is " + error));
            }
        }

    };
    private CameraCaptureSession.StateCallback mCaptureStateCallback = new CameraCaptureSession.StateCallback() {

        @Override
        public void onConfigured(@NonNull CameraCaptureSession cameraCaptureSession) {
            Log.i(TAG, "onConfigured: ");
            // The camera is already closed
            if (null == mCameraDevice) {
                return;
            }

            // When the session is ready, we start displaying the preview.
            mCaptureSession = cameraCaptureSession;
            try {
                mCaptureSession.setRepeatingRequest(mPreviewRequestBuilder.build(),
                        new CameraCaptureSession.CaptureCallback() {
                        }, mBackgroundHandler);
            } catch (CameraAccessException e) {
                e.printStackTrace();
            }
        }

        @Override
        public void onConfigureFailed(
                @NonNull CameraCaptureSession cameraCaptureSession) {
            Log.i(TAG, "onConfigureFailed: ");
            if (camera2Listener != null) {
                camera2Listener.onCameraError(new Exception("configureFailed"));
            }
        }
    };
    /**
     * An additional thread for running tasks that shouldn't block the UI.
     */
    private HandlerThread mBackgroundThread;

    /**
     * A {@link Handler} for running tasks in the background.
     */
    private Handler mBackgroundHandler;

    private ImageReader mImageReader;


    /**
     * {@link CaptureRequest.Builder} for the camera preview
     */
    private CaptureRequest.Builder mPreviewRequestBuilder;


    /**
     * A {@link Semaphore} to prevent the app from exiting before closing the camera.
     */
    private Semaphore mCameraOpenCloseLock = new Semaphore(1);


    /**
     * Orientation of the camera sensor
     */
    private int mSensorOrientation;

    private Size getBestSupportedSize(List<Size> sizes) {
        Size defaultSize = sizes.get(0);
        Size[] tempSizes = sizes.toArray(new Size[0]);
        Arrays.sort(tempSizes, new Comparator<Size>() {
            @Override
            public int compare(Size o1, Size o2) {
                if (o1.getWidth() > o2.getWidth()) {
                    return -1;
                } else if (o1.getWidth() == o2.getWidth()) {
                    return o1.getHeight() > o2.getHeight() ? -1 : 1;
                } else {
                    return 1;
                }
            }
        });
        sizes = new ArrayList<>(Arrays.asList(tempSizes));
        for (int i = sizes.size() - 1; i >= 0; i--) {
            if (maxPreviewSize != null) {
                if (sizes.get(i).getWidth() > maxPreviewSize.x || sizes.get(i).getHeight() > maxPreviewSize.y) {
                    sizes.remove(i);
                    continue;
                }
            }
            if (minPreviewSize != null) {
                if (sizes.get(i).getWidth() < minPreviewSize.x || sizes.get(i).getHeight() < minPreviewSize.y) {
                    sizes.remove(i);
                }
            }
        }
        if (sizes.size() == 0) {
            String msg = "can not find suitable previewSize, now using default";
            if (camera2Listener != null) {
                Log.e(TAG, msg);
                camera2Listener.onCameraError(new Exception(msg));
            }
            return defaultSize;
        }
        Size bestSize = sizes.get(0);
        float previewViewRatio;
        if (previewViewSize != null) {
            previewViewRatio = (float) previewViewSize.x / (float) previewViewSize.y;
        } else {
            previewViewRatio = (float) bestSize.getWidth() / (float) bestSize.getHeight();
        }

        if (previewViewRatio > 1) {
            previewViewRatio = 1 / previewViewRatio;
        }

        for (Size s : sizes) {
            if (specificPreviewSize != null && specificPreviewSize.x == s.getWidth() && specificPreviewSize.y == s.getHeight()) {
                return s;
            }
            if (Math.abs((s.getHeight() / (float) s.getWidth()) - previewViewRatio) < Math.abs(bestSize.getHeight() / (float) bestSize.getWidth() - previewViewRatio)) {
                bestSize = s;
            }
        }
        return bestSize;
    }

    public synchronized void start() {
        Log.e("webber4", "start");
        if (mCameraDevice != null) {
            Log.e("webber4", "mcameradevice is not null");
            return;
        }
        startBackgroundThread();

        // When the screen is turned off and turned back on, the SurfaceTexture is already
        // available, and "onSurfaceTextureAvailable" will not be called. In that case, we can open
        // a camera and start preview from here (otherwise, we wait until the surface is ready in
        // the SurfaceTextureListener).
        if (mTextureView.isAvailable()) {
            Log.e("webber4", "open camera1");
            openCamera();
        } else {
            Log.e("webber4", "setSurfaceTextureListener");
            mTextureView.setSurfaceTextureListener(mSurfaceTextureListener);
        }
    }

    public synchronized void stop() {
        if (mCameraDevice == null) {
            return;
        }
        closeCamera();
        stopBackgroundThread();
    }

    public void release() {
        stop();
        mTextureView = null;
        camera2Listener = null;
        context = null;
    }

    private void setUpCameraOutputs(CameraManager cameraManager) {
        Log.e("webber4", "setUpCameraOutputs");
        try {
            if (configCameraParams(cameraManager, specificCameraId)) {
                return;
            }
            for (String cameraId : cameraManager.getCameraIdList()) {
                if (configCameraParams(cameraManager, cameraId)) {
                    return;
                }
            }
        } catch (CameraAccessException e) {
            e.printStackTrace();
        } catch (NullPointerException e) {
            // Currently an NPE is thrown when the Camera2API is used but not supported on the
            // device this code runs.

            if (camera2Listener != null) {
                camera2Listener.onCameraError(e);
            }
        }
    }

    private boolean configCameraParams(CameraManager manager, String cameraId) throws CameraAccessException {
        Log.e("webber4", "configCameraParams ");
        CameraCharacteristics characteristics
                = manager.getCameraCharacteristics(cameraId);

        StreamConfigurationMap map = characteristics.get(
                CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        if (map == null) {
            return false;
        }
        mPreviewSize = getBestSupportedSize(new ArrayList<Size>(Arrays.asList(map.getOutputSizes(SurfaceTexture.class))));
        Log.e("webber", "preview width:" + mPreviewSize.getWidth() + "height:" + mPreviewSize.getHeight());

        mImageReader = ImageReader.newInstance(mPreviewSize.getWidth(), mPreviewSize.getHeight(),
                ImageFormat.YUV_420_888, 2);
        mImageReader.setOnImageAvailableListener(
                new OnImageAvailableListenerImpl(), mBackgroundHandler);

        mSensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
        mCameraId = cameraId;
        mMaxZoom = characteristics.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM);
        camera2Listener.SetMaxZoomLevels((int) mMaxZoom);
        Log.e("webber5", "max zoom new is " + mMaxZoom);
        return true;
    }

    @SuppressLint("MissingPermission")
    private void openCamera() {
        Log.e("webber4", "open camera");
        CameraManager cameraManager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
        setUpCameraOutputs(cameraManager);
        configureTransform(mTextureView.getWidth(), mTextureView.getHeight());
        try {
            if (!mCameraOpenCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS)) {
                throw new RuntimeException("Time out waiting to lock camera opening.");
            }
            cameraManager.openCamera(mCameraId, mDeviceStateCallback, mBackgroundHandler);
        } catch (CameraAccessException e) {
            if (camera2Listener != null) {
                camera2Listener.onCameraError(e);
            }
        } catch (InterruptedException e) {
            if (camera2Listener != null) {
                camera2Listener.onCameraError(e);
            }
        }
    }

    /**
     * Closes the current {@link CameraDevice}.
     */
    private void closeCamera() {
        try {
            mCameraOpenCloseLock.acquire();
            if (null != mCaptureSession) {
                mCaptureSession.close();
                mCaptureSession = null;
            }
            if (null != mCameraDevice) {
                mCameraDevice.close();
                mCameraDevice = null;
            }
            if (null != mImageReader) {
                mImageReader.close();
                mImageReader = null;
            }
            if (camera2Listener != null) {
                camera2Listener.onCameraClosed();
            }
        } catch (InterruptedException e) {
            if (camera2Listener != null) {
                camera2Listener.onCameraError(e);
            }
        } finally {
            mCameraOpenCloseLock.release();
        }
    }

    /**
     * Starts a background thread and its {@link Handler}.
     */
    private void startBackgroundThread() {
        mBackgroundThread = new HandlerThread("CameraBackground");
        mBackgroundThread.start();
        mBackgroundHandler = new Handler(mBackgroundThread.getLooper());
    }

    /**
     * Stops the background thread and its {@link Handler}.
     */
    private void stopBackgroundThread() {
        mBackgroundThread.quitSafely();
        try {
            mBackgroundThread.join();
            mBackgroundThread = null;
            mBackgroundHandler = null;
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    private void writeDefaultTxt(){
        StringBuilder writeStr = new StringBuilder();
        StringBuilder lineAllContents = new StringBuilder();
        FileWriter writer;
        String dcimPath = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM).toString();
        String dataPath = dcimPath + "/data.txt";
        File filePath = new File(dataPath);
        Log.i(TAG, "writeTxt :FIlePath = " + filePath);
        try{
            if(!filePath.exists()){
                filePath.createNewFile();
                writeStr.append("zoom:0,0,0,0,0");
                try {
                    writer = new FileWriter(filePath);
                    writer.write("");
                    writer.write(writeStr.toString());
                    writer.flush();
                    writer.close();

                } catch (IOException exception) {
                    exception.printStackTrace();
                }
            }

        }catch (IOException exception){
            exception.printStackTrace();
        }

    }

    private void inputParaTxt(String parameterMode ,String keyValue){
        StringBuilder lineAllContents = new StringBuilder();
        FileWriter writer;
        String dcimPath = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM).toString();
        String dataPath = dcimPath + "/data.txt";
        File filePath = new File(dataPath);
        if(!filePath.exists()){
            writeDefaultTxt();
            Log.i(TAG, "write default txt");
        }

        try {
            InputStreamReader inputReader = new InputStreamReader(new FileInputStream(filePath), "UTF-8");
            BufferedReader buffer = new BufferedReader(inputReader);
            String lineStr = null;
            while ((lineStr = buffer.readLine()) != null) {
                String[] sourceStrArray = lineStr.split(":");
                Log.i(TAG, " file line data: " + lineStr);
                if (sourceStrArray[0].equals(parameterMode)) {
                    lineStr = parameterMode + ":" + keyValue;
                    Log.i(TAG, "replace file line data: " + lineStr);
                }
                lineAllContents.append(lineStr + '\n');
            }
            buffer.close();
            inputReader.close();
        }catch (IOException exception){
            exception.printStackTrace();;
        }
        try{
            writer = new FileWriter(filePath);
            writer.write("");
            writer.write(lineAllContents.toString());
            writer.flush();
            writer.close();
        }catch (IOException exception){
            exception.printStackTrace();
        }
    }

    public void updatePreview(int zoomLevel) throws CameraAccessException {

        CameraManager cameraManager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
        CameraCharacteristics mCharacteristics = cameraManager.getCameraCharacteristics(mCameraId);
        Rect activeRect = mCharacteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);

        double actualZoomLevel = 0.0;
        if(zoomLevel == 2)
            actualZoomLevel = 1.2;
        else if(zoomLevel == 3)
            actualZoomLevel = 1.4;
        else
            actualZoomLevel = 1.0;

        int center_x = activeRect.bottom / 2;
        int center_y =  activeRect.right / 2;
        int new_height = (int) (activeRect.right / actualZoomLevel);
        int new_width = (int ) (activeRect.bottom / actualZoomLevel);
        Rect newRect = new Rect(center_y - new_height/2, center_x - new_width/2, center_y + new_height/2, center_x + new_width/2);


        String inputStr = "zoom";
        String value;
        value = String.valueOf(actualZoomLevel) + "," + Integer.toString(newRect.left) + ","
            + Integer.toString(newRect.top) + ","
            + Integer.toString(newRect.width())
             + "," + Integer.toString(newRect.height());

        inputParaTxt(inputStr, value);

        mPreviewRequestBuilder.set(CaptureRequest.SCALER_CROP_REGION, newRect);
        try {
            mCaptureSession.setRepeatingRequest(mPreviewRequestBuilder.build(),
                    new CameraCaptureSession.CaptureCallback() {
                    }, mBackgroundHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    /**
     * Creates a new {@link CameraCaptureSession} for camera preview.
     */
    private void createCameraPreviewSession() {
        try {
            SurfaceTexture texture = mTextureView.getSurfaceTexture();
            assert texture != null;

            // We configure the size of default buffer to be the size of camera preview we want.
            texture.setDefaultBufferSize(mPreviewSize.getWidth(), mPreviewSize.getHeight());

            // This is the output Surface we need to start preview.
            Surface surface = new Surface(texture);

            // We set up a CaptureRequest.Builder with the output Surface.
            mPreviewRequestBuilder
                    = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);

            mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE,
                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);

            mPreviewRequestBuilder.addTarget(surface);
            mPreviewRequestBuilder.addTarget(mImageReader.getSurface());

            // Here, we create a CameraCaptureSession for camera preview.
            mCameraDevice.createCaptureSession(Arrays.asList(surface, mImageReader.getSurface()),
                    mCaptureStateCallback, mBackgroundHandler
            );
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    /**
     * Configures the necessary {@link Matrix} transformation to `mTextureView`.
     * This method should be called after the camera preview size is determined in
     * setUpCameraOutputs and also the size of `mTextureView` is fixed.
     *
     * @param viewWidth  The width of `mTextureView`
     * @param viewHeight The height of `mTextureView`
     */
    private void configureTransform(int viewWidth, int viewHeight) {
        if (null == mTextureView || null == mPreviewSize) {
            return;
        }
        Log.i(TAG, "webber1 configureTransform!" );
        Matrix matrix = new Matrix();
        RectF viewRect = new RectF(0, 0, viewWidth, viewHeight);
        RectF bufferRect = new RectF(0, 0, mPreviewSize.getHeight(), mPreviewSize.getWidth());
        float centerX = viewRect.centerX();
        float centerY = viewRect.centerY();
        if (Surface.ROTATION_90 == rotation || Surface.ROTATION_270 == rotation) {
            bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY());
            matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL);
            float scale = Math.max(
                    (float) viewHeight / mPreviewSize.getHeight(),
                    (float) viewWidth / mPreviewSize.getWidth());
            matrix.postScale(scale, scale, centerX, centerY);
            matrix.postRotate((90 * (rotation - 2)) % 360, centerX, centerY);
        } else if (Surface.ROTATION_180 == rotation) {
            matrix.postRotate(180, centerX, centerY);
        }
        Log.i(TAG, "configureTransform: " + getCameraOri(rotation, mCameraId) + "  " + rotation * 90);
        mTextureView.setTransform(matrix);
    }

    public static final class Builder {

        /**
         * 预览显示的view，目前仅支持textureView
         */
        private TextureView previewDisplayView;

        /**
         * 是否镜像显示，只支持textureView
         */
        private boolean isMirror;
        /**
         * 指定的相机ID
         */
        private String specificCameraId;
        /**
         * 事件回调
         */
        private Camera2Listener camera2Listener;
        /**
         * 屏幕的长宽，在选择最佳相机比例时用到
         */
        private Point previewViewSize;
        /**
         * 传入getWindowManager().getDefaultDisplay().getRotation()的值即可
         */
        private int rotation;
        /**
         * 指定的预览宽高，若系统支持则会以这个预览宽高进行预览
         */
        private Point previewSize;
        /**
         * 最大分辨率
         */
        private Point maxPreviewSize;
        /**
         * 最小分辨率
         */
        private Point minPreviewSize;
        /**
         * 上下文，用于获取CameraManager
         */
        private Context context;

        public Builder() {
        }


        public Builder previewOn(TextureView val) {
            previewDisplayView = val;
            return this;
        }


        public Builder isMirror(boolean val) {
            isMirror = val;
            return this;
        }

        public Builder previewSize(Point val) {
            previewSize = val;
            return this;
        }

        public Builder maxPreviewSize(Point val) {
            maxPreviewSize = val;
            return this;
        }

        public Builder minPreviewSize(Point val) {
            minPreviewSize = val;
            return this;
        }

        public Builder previewViewSize(Point val) {
            previewViewSize = val;
            return this;
        }

        public Builder rotation(int val) {
            rotation = val;
            return this;
        }


        public Builder specificCameraId(String val) {
            specificCameraId = val;
            return this;
        }

        public Builder cameraListener(Camera2Listener val) {
            camera2Listener = val;
            return this;
        }

        public Builder context(Context val) {
            context = val;
            return this;
        }

        public Camera2Helper build() {
            if (previewViewSize == null) {
                Log.e(TAG, "previewViewSize is null, now use default previewSize");
            }
            if (camera2Listener == null) {
                Log.e(TAG, "camera2Listener is null, callback will not be called");
            }
            if (previewDisplayView == null) {
                throw new NullPointerException("you must preview on a textureView or a surfaceView");
            }
            if (maxPreviewSize != null && minPreviewSize != null) {
                if (maxPreviewSize.x < minPreviewSize.x || maxPreviewSize.y < minPreviewSize.y) {
                    throw new IllegalArgumentException("maxPreviewSize must greater than minPreviewSize");
                }
            }
            return new Camera2Helper(this);
        }
    }

    private class OnImageAvailableListenerImpl implements ImageReader.OnImageAvailableListener {
        private byte[] y;
        private byte[] u;
        private byte[] v;
        private ReentrantLock lock = new ReentrantLock();

        @Override
        public void onImageAvailable(ImageReader reader) {
            Image image = reader.acquireNextImage();
            //Log.e("webber8","onImageAvailable:" + image.getWidth() + image.getHeight());
            // Y:U:V == 4:2:2
            if (camera2Listener != null && image.getFormat() == ImageFormat.YUV_420_888) {
                Image.Plane[] planes = image.getPlanes();
                // 加锁确保y、u、v来源于同一个Image
                lock.lock();
                // 重复使用同一批byte数组，减少gc频率
                if (y == null) {
                    y = new byte[planes[0].getBuffer().limit() - planes[0].getBuffer().position()];
                    u = new byte[planes[1].getBuffer().limit() - planes[1].getBuffer().position()];
                    v = new byte[planes[2].getBuffer().limit() - planes[2].getBuffer().position()];
                }
                if (image.getPlanes()[0].getBuffer().remaining() == y.length) {
                    planes[0].getBuffer().get(y);
                    planes[1].getBuffer().get(u);
                    planes[2].getBuffer().get(v);
                    camera2Listener.onPreview(y, u, v, mPreviewSize, planes[0].getRowStride());
                }
                lock.unlock();
            }
            image.close();
        }
    }
}
