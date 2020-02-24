package com.flurgle.camerakit;

import android.graphics.Rect;
import android.graphics.YuvImage;
import android.hardware.Camera;
import android.media.CamcorderProfile;
import android.media.MediaRecorder;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.View;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static com.flurgle.camerakit.CameraKit.Constants.FLASH_OFF;
import static com.flurgle.camerakit.CameraKit.Constants.FOCUS_CONTINUOUS;
import static com.flurgle.camerakit.CameraKit.Constants.FOCUS_OFF;
import static com.flurgle.camerakit.CameraKit.Constants.FOCUS_TAP;
import static com.flurgle.camerakit.CameraKit.Constants.METHOD_STANDARD;
import static com.flurgle.camerakit.CameraKit.Constants.METHOD_STILL;

@SuppressWarnings("deprecation")
public class Camera1 extends CameraImpl {

    private static final int FOCUS_AREA_SIZE_DEFAULT = 300;
    private static final int FOCUS_METERING_AREA_WEIGHT_DEFAULT = 1000;

    private int mCameraId;
    private Camera mCamera;
    private Camera.Parameters mCameraParameters;
    private Camera.CameraInfo mCameraInfo;
    private Size mPreviewSize;
    private Size mCaptureSize;
    private MediaRecorder mMediaRecorder;
    private File mVideoFile;
    private Camera.AutoFocusCallback mAutofocusCallback;

    private final Object mCameraLock = new Object();

    private int mDisplayOrientation;
    private int mDeviceOrientation;

    @Facing
    private int mFacing;

    @Flash
    private int mFlash;

    @Focus
    private int mFocus;

    @Method
    private int mMethod;

    @Zoom
    private int mZoom;

    private boolean mShowingPreview = false;

    Camera1(CameraListener callback, PreviewImpl preview) {
        super(callback, preview);
        preview.setCallback(new PreviewImpl.Callback() {
            @Override
            public void onSurfaceChanged() {
                if (mCamera != null) {
                    setupPreview();
                    adjustCameraParameters();
                    mShowingPreview = true;
                }
            }
        });

        mCameraInfo = new Camera.CameraInfo();

    }

    // CameraImpl:

    @Override
    void start() {
        setFacing(mFacing);
        openCamera();
        if (mPreview.isReady()) setupPreview();
        mCamera.startPreview();
        mShowingPreview = true;
    }

    @Override
    void stop() {
        if (mCamera != null) mCamera.stopPreview();
        releaseCamera();
        mShowingPreview = false;
    }

    @Override
    void setDisplayOrientation(int displayOrientation) {
        this.mDisplayOrientation = displayOrientation;
    }

    void setDisplayAndDeviceOrientation() {
        setDisplayAndDeviceOrientation(this.mDisplayOrientation, this.mDeviceOrientation);
    }

    @Override
    void setDisplayAndDeviceOrientation(int displayOrientation, int deviceOrientation) {
        this.mDisplayOrientation = displayOrientation;
        this.mDeviceOrientation = deviceOrientation;

        synchronized (mCameraLock) {
            if (isCameraOpened()) {
                try {
                    mCamera.setDisplayOrientation(calculateCameraRotation());
                } catch (RuntimeException e) {
                    // Camera is released. Ignore. Orientations are still valid in local member fields
                    // so next time camera starts it will have correct configuration.
                }
            }
        }
    }

    @Override
    void setFacing(@Facing int facing) {
        int internalFacing = new ConstantMapper.Facing(facing).map();
        if (internalFacing == -1) {
            return;
        }

        for (int i = 0, count = Camera.getNumberOfCameras(); i < count; i++) {
            Camera.getCameraInfo(i, mCameraInfo);
            if (mCameraInfo.facing == internalFacing) {
                mCameraId = i;
                mFacing = facing;
                break;
            }
        }

        if (mFacing == facing && isCameraOpened()) {
            stop();
            start();
        }
    }

    @Override
    void setFlash(@Flash int flash) {
        if (mCameraParameters != null) {
            List<String> flashes = mCameraParameters.getSupportedFlashModes();
            String internalFlash = new ConstantMapper.Flash(flash).map();
            if (flashes != null && flashes.contains(internalFlash)) {
                mCameraParameters.setFlashMode(internalFlash);
                mFlash = flash;
            } else {
                String currentFlash = new ConstantMapper.Flash(mFlash).map();
                if (flashes == null || !flashes.contains(currentFlash)) {
                    mCameraParameters.setFlashMode(Camera.Parameters.FLASH_MODE_OFF);
                    mFlash = FLASH_OFF;
                }
            }

            mCamera.setParameters(mCameraParameters);
        } else {
            mFlash = flash;
        }
    }

    @Override
    Camera getCamera() {
        return mCamera;
    }

    @Override
    void setFocus(@Focus int focus) {
        this.mFocus = focus;
        switch (focus) {
            case FOCUS_CONTINUOUS:
                if (mCameraParameters != null) {
                    detachFocusTapListener();
                    final List<String> modes = mCameraParameters.getSupportedFocusModes();
                    if (modes.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE)) {
                        mCameraParameters.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE);
                    } else {
                        setFocus(FOCUS_OFF);
                    }
                }
                break;

            case FOCUS_TAP:
                if (mCameraParameters != null) {
                    attachFocusTapListener();
                    final List<String> modes = mCameraParameters.getSupportedFocusModes();
                    if (modes.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE)) {
                        mCameraParameters.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE);
                    }
                }
                break;

            case FOCUS_OFF:
                if (mCameraParameters != null) {
                    detachFocusTapListener();
                    final List<String> modes = mCameraParameters.getSupportedFocusModes();
                    if (modes.contains(Camera.Parameters.FOCUS_MODE_FIXED)) {
                        mCameraParameters.setFocusMode(Camera.Parameters.FOCUS_MODE_FIXED);
                    } else if (modes.contains(Camera.Parameters.FOCUS_MODE_INFINITY)) {
                        mCameraParameters.setFocusMode(Camera.Parameters.FOCUS_MODE_INFINITY);
                    } else {
                        mCameraParameters.setFocusMode(Camera.Parameters.FOCUS_MODE_AUTO);
                    }
                }
                break;
        }
    }

    @Override
    void setMethod(@Method int method) {
        this.mMethod = method;
    }

    @Override
    void setZoom(@Zoom int zoom) {
        this.mZoom = zoom;
    }

    @Override
    void setVideoQuality(int videoQuality) {

    }

    @Override
    void captureImage() {
        switch (mMethod) {
            case METHOD_STANDARD:
                mCamera.takePicture(null, null, null, new Camera.PictureCallback() {
                    @Override
                    public void onPictureTaken(byte[] data, Camera camera) {
                        mCameraListener.onPictureTaken(data);
                        camera.startPreview();
                    }
                });
                break;

            case METHOD_STILL:
                mCamera.setOneShotPreviewCallback(new Camera.PreviewCallback() {
                    @Override
                    public void onPreviewFrame(byte[] data, Camera camera) {
                        new Thread(new ProcessStillTask(data, camera, mCameraInfo.orientation, new ProcessStillTask.OnStillProcessedListener() {
                            @Override
                            public void onStillProcessed(final YuvImage yuv) {
                                mCameraListener.onPictureTaken(yuv);
                            }
                        })).start();
                    }
                });
                break;
        }
    }

    @Override
    void startVideo() {
        initMediaRecorder();
        prepareMediaRecorder();
        mMediaRecorder.start();
    }

    @Override
    void endVideo() {
        mMediaRecorder.stop();
        mMediaRecorder = null;
        mCameraListener.onVideoTaken(mVideoFile);
    }

    @Override
    Size getCaptureResolution() {
        if (mCaptureSize == null && mCameraParameters != null) {
            TreeSet<Size> sizes = new TreeSet<>();
            for (Camera.Size size : mCameraParameters.getSupportedPictureSizes()) {
                sizes.add(new Size(size.width, size.height));
            }

            TreeSet<AspectRatio> aspectRatios = findCommonAspectRatios(
                    mCameraParameters.getSupportedPreviewSizes(),
                    mCameraParameters.getSupportedPictureSizes()
            );
            AspectRatio targetRatio = aspectRatios.size() > 0 ? aspectRatios.last() : null;

            Iterator<Size> descendingSizes = sizes.descendingIterator();
            Size size;
            while (descendingSizes.hasNext() && mCaptureSize == null) {
                size = descendingSizes.next();
                if (targetRatio == null || targetRatio.matches(size)) {
                    mCaptureSize = size;
                    break;
                }
            }
        }

        return mCaptureSize;
    }

    @Override
    Size getPreviewResolution() {
        Size cameraPreviewResolution = getCameraPreviewResolution();
        boolean invertPreviewSizes = (mCameraInfo.orientation + mDeviceOrientation) % 180 == 90;
        if (invertPreviewSizes) {
            return new Size(cameraPreviewResolution.getHeight(), cameraPreviewResolution.getWidth());
        }
        return cameraPreviewResolution;
    }

    Size getCameraPreviewResolution() {
        if (mPreviewSize == null && mCameraParameters != null) {
            TreeSet<Size> sizes = new TreeSet<>();
            for (Camera.Size size : mCameraParameters.getSupportedPreviewSizes()) {
                sizes.add(new Size(size.width, size.height));
            }

            TreeSet<AspectRatio> aspectRatios = findCommonAspectRatios(
                    mCameraParameters.getSupportedPreviewSizes(),
                    mCameraParameters.getSupportedPictureSizes()
            );

            AspectRatio targetRatio = null;

            TreeSet<AspectRatio> videoAspectRatios = findCommonAspectRatios(
                    mCameraParameters.getSupportedPreviewSizes(),
                    mCameraParameters.getSupportedPictureSizes()
            );

            Iterator<AspectRatio> descendingIterator = aspectRatios.descendingIterator();
            while (targetRatio == null && descendingIterator.hasNext()) {
                AspectRatio ratio = descendingIterator.next();
                if (videoAspectRatios.contains(ratio)) {
                    targetRatio = ratio;
                }
            }

            if (targetRatio == null) {
                targetRatio = aspectRatios.size() > 0 ? aspectRatios.last() : null;
            }

            Iterator<Size> descendingSizes = sizes.descendingIterator();
            Size size;
            while (descendingSizes.hasNext() && mPreviewSize == null) {
                size = descendingSizes.next();
                if (targetRatio == null || targetRatio.matches(size)) {
                    mPreviewSize = size;
                    break;
                }
            }
        }

        return mPreviewSize;
    }

    @Override
    boolean isCameraOpened() {
        return mCamera != null;
    }

    // Internal:

    private void openCamera() {
        if (mCamera != null) {
            releaseCamera();
        }

        mCamera = Camera.open(mCameraId);
        mCameraParameters = mCamera.getParameters();

        adjustCameraParameters();
        setDisplayAndDeviceOrientation();
        mCameraListener.onCameraOpened();
    }

    private void setupPreview() {
        try {
            if (mPreview.getOutputClass() == SurfaceHolder.class) {
                mCamera.setPreviewDisplay(mPreview.getSurfaceHolder());
            } else {
                mCamera.setPreviewTexture(mPreview.getSurfaceTexture());
            }
        } catch (Exception e) {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        Thread.sleep(3000);
                        if (mCamera != null) {
                            if (mPreview.getOutputClass() == SurfaceHolder.class) {
                                mCamera.setPreviewDisplay(mPreview.getSurfaceHolder());
                            } else {
                                mCamera.setPreviewTexture(mPreview.getSurfaceTexture());
                            }
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            }).start();
            e.printStackTrace();
        }
    }

    private void releaseCamera() {
        if (mCamera != null) {
            mCamera.release();
            mCamera = null;
            mCameraParameters = null;
            mPreviewSize = null;
            mCaptureSize = null;
            mCameraListener.onCameraClosed();
        }
    }

    private int calculateCameraRotation() {
        if (mCameraInfo.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
            return (360 - (mCameraInfo.orientation + mDisplayOrientation) % 360) % 360;
        } else {
            return (mCameraInfo.orientation - mDisplayOrientation + 360) % 360;
        }
    }

    private void adjustCameraParameters() {
        synchronized (mCameraLock) {
            if (mShowingPreview) {
                mCamera.stopPreview();
            }

            adjustCameraParameters(0);

            if (mShowingPreview) {
                mCamera.startPreview();
            }
        }
    }

    private void adjustCameraParameters(int currentTry) {
        boolean haveToReadjust = false;
        Camera.Parameters resolutionLess = mCamera.getParameters();

        if (getPreviewResolution() != null) {
            mPreview.setTruePreviewSize(getPreviewResolution().getWidth(),
                    getPreviewResolution().getHeight());

            mCameraParameters.setPreviewSize(
                    getCameraPreviewResolution().getWidth(),
                    getCameraPreviewResolution().getHeight()
            );

            try {
                mCamera.setParameters(mCameraParameters);
                resolutionLess = mCameraParameters;
            } catch (Exception e) {
                // Some phones can't set parameters that camerakit has chosen, so fallback to defaults
                mCameraParameters = resolutionLess;
            }
        } else {
            haveToReadjust = true;
        }

        if (getCaptureResolution() != null) {
            mCameraParameters.setPictureSize(
                    getCaptureResolution().getWidth(),
                    getCaptureResolution().getHeight()
            );

            try {
                mCamera.setParameters(mCameraParameters);
                resolutionLess = mCameraParameters;
            } catch (Exception e) {
                e.printStackTrace();
                //Some phones can't set parameters that camerakit has chosen, so fallback to defaults
                mCameraParameters = resolutionLess;
            }
        } else {
            haveToReadjust = true;
        }

        int rotation = calculateCaptureRotation();
        mCameraParameters.setRotation(rotation);

        setFocus(mFocus);

        try {
            setFlash(mFlash);
        } catch (Exception e) {
            e.printStackTrace();
        }

        if (mCameraParameters.isZoomSupported()) {
            setZoom(mZoom);
        }

        mCamera.setParameters(mCameraParameters);

        if (haveToReadjust && currentTry < 100) {
            try {
                Thread.sleep(1);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }

            adjustCameraParameters(currentTry + 1);
        }
    }

    private int calculateCaptureRotation() {
        int captureRotation = 0;
        if (mCameraInfo.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
            captureRotation = (mCameraInfo.orientation + mDisplayOrientation) % 360;
        } else {  // back-facing camera
            captureRotation = (mCameraInfo.orientation - mDisplayOrientation + 360) % 360;
        }

        // Accommodate for any extra device rotation relative to fixed screen orientations
        // (e.g. activity fixed in portrait, but user took photo/video in landscape)
        if (mCameraInfo.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
            captureRotation = ((captureRotation - (mDisplayOrientation - mDeviceOrientation)) + 360) % 360;
        } else {  // back-facing camera
            captureRotation = (captureRotation + (mDisplayOrientation - mDeviceOrientation) + 360) % 360;
        }

        return captureRotation;
    }

    private TreeSet<AspectRatio> findCommonAspectRatios(List<Camera.Size> previewSizes, List<Camera.Size> captureSizes) {
        Set<AspectRatio> previewAspectRatios = new HashSet<>();
        for (Camera.Size size : previewSizes) {
            if (size.width >= CameraKit.Internal.screenHeight && size.height >= CameraKit.Internal.screenWidth) {
                previewAspectRatios.add(AspectRatio.of(size.width, size.height));
            }
        }

        Set<AspectRatio> captureAspectRatios = new HashSet<>();
        for (Camera.Size size : captureSizes) {
            captureAspectRatios.add(AspectRatio.of(size.width, size.height));
        }

        TreeSet<AspectRatio> output = new TreeSet<>();
        for (AspectRatio aspectRatio : previewAspectRatios) {
            if (captureAspectRatios.contains(aspectRatio)) {
                output.add(aspectRatio);
            }
        }

        return output;
    }

    private void initMediaRecorder() {
        mMediaRecorder = new MediaRecorder();
        mCamera.unlock();

        mMediaRecorder.setCamera(mCamera);
        mMediaRecorder.setVideoSource(MediaRecorder.VideoSource.CAMERA);
        mMediaRecorder.setAudioSource(MediaRecorder.AudioSource.CAMCORDER);
        mMediaRecorder.setProfile(CamcorderProfile.get(CamcorderProfile.QUALITY_480P));

        mVideoFile = new File(mPreview.getView().getContext().getExternalFilesDir(null), "video.mp4");
        mMediaRecorder.setOutputFile(mVideoFile.getAbsolutePath());

        mMediaRecorder.setMaxDuration(20000);
        mMediaRecorder.setMaxFileSize(5000000);
        mMediaRecorder.setOrientationHint(mCameraInfo.orientation);
    }

    private void prepareMediaRecorder() {
        try {
            mMediaRecorder.prepare();
        } catch (IllegalStateException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    void setTapToAutofocusListener(Camera.AutoFocusCallback callback) {
        if (this.mFocus != FOCUS_TAP) {
            throw new IllegalArgumentException("Please set the camera to FOCUS_TAP.");
        }

        this.mAutofocusCallback = callback;
    }

    private int getFocusAreaSize() {
        return FOCUS_AREA_SIZE_DEFAULT;
    }

    private int getFocusMeteringAreaWeight() {
        return FOCUS_METERING_AREA_WEIGHT_DEFAULT;
    }

    private void detachFocusTapListener() {
        mPreview.getView().setOnTouchListener(null);
    }

    private void attachFocusTapListener() {
        mPreview.getView().setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                if (event.getAction() == MotionEvent.ACTION_UP) {
                    if (mCamera != null) {

                        Camera.Parameters parameters = mCamera.getParameters();
                        if (parameters.getMaxNumMeteringAreas() > 0) {
                            Rect rect = calculateFocusArea(event.getX(), event.getY());

                            parameters.setFocusMode(Camera.Parameters.FOCUS_MODE_AUTO);
                            List<Camera.Area> meteringAreas = new ArrayList<>();
                            meteringAreas.add(new Camera.Area(rect, getFocusMeteringAreaWeight()));
                            parameters.setFocusAreas(meteringAreas);
                            parameters.setMeteringAreas(meteringAreas);

                            mCamera.setParameters(parameters);
                            mCamera.autoFocus(new Camera.AutoFocusCallback() {
                                @Override
                                public void onAutoFocus(boolean success, Camera camera) {
                                    camera.cancelAutoFocus();
                                    Camera.Parameters params = camera.getParameters();
                                    if (params.getFocusMode() != Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE) {
                                        params.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE);
                                        params.setFocusAreas(null);
                                        params.setMeteringAreas(null);
                                        camera.setParameters(params);
                                    }

                                    if (mAutofocusCallback != null) {
                                        mAutofocusCallback.onAutoFocus(success, camera);
                                    }
                                }
                            });
                        } else {
                            mCamera.autoFocus(new Camera.AutoFocusCallback() {
                                @Override
                                public void onAutoFocus(boolean success, Camera camera) {
                                    if (mAutofocusCallback != null) {
                                        mAutofocusCallback.onAutoFocus(success, camera);
                                    }
                                }
                            });
                        }
                    }
                }
                return true;
            }
        });
    }

    private Rect calculateFocusArea(float x, float y) {
        int centerX = clamp(Float.valueOf((x / mPreview.getView().getWidth()) * 2000 - 1000).intValue(), getFocusAreaSize());
        int centerY = clamp(Float.valueOf((y / mPreview.getView().getHeight()) * 2000 - 1000).intValue(), getFocusAreaSize());
        return new Rect(
                centerX - getFocusAreaSize() / 2,
                centerY - getFocusAreaSize() / 2,
                centerX + getFocusAreaSize() / 2,
                centerY + getFocusAreaSize() / 2
        );
    }

    private int clamp(int touchCoordinateInCameraReper, int focusAreaSize) {
        int result;
        if (Math.abs(touchCoordinateInCameraReper) + focusAreaSize / 2 > 1000) {
            if (touchCoordinateInCameraReper > 0) {
                result = 1000 - focusAreaSize / 2;
            } else {
                result = -1000 + focusAreaSize / 2;
            }
        } else {
            result = touchCoordinateInCameraReper - focusAreaSize / 2;
        }
        return result;
    }
}
