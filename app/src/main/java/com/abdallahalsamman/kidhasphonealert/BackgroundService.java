package com.abdallahalsamman.kidhasphonealert;

import android.Manifest;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.SharedPreferences;
import android.media.AudioManager;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.SystemClock;
import android.preference.PreferenceManager;
import android.provider.AlarmClock;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.app.ActivityCompat;
import android.util.Log;

import com.androidhiddencamera.CameraConfig;
import com.androidhiddencamera.CameraError;
import com.androidhiddencamera.HiddenCameraService;
import com.androidhiddencamera.HiddenCameraUtils;
import com.androidhiddencamera.config.CameraFacing;
import com.androidhiddencamera.config.CameraImageFormat;
import com.androidhiddencamera.config.CameraResolution;

import org.json.JSONException;
import org.json.JSONObject;
import org.opencv.core.Core;
import org.opencv.core.Mat;
import org.opencv.core.MatOfRect;
import org.opencv.core.Size;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;
import org.opencv.objdetect.CascadeClassifier;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;

/**
 * Created by Keval on 11-Nov-16.
 *
 * @author {@link 'https://github.com/kevalpatel2106'}
 */

public class BackgroundService extends HiddenCameraService {

    SharedPreferences prefs;
    private int samePersonInaRow = 0;
    private long lastTimeReported = 0;
    private String lastPerson;
    private Handler handler = new Handler();
    private Runnable runnable = new Runnable() {
        @Override
        public void run() {
            // wait 10 min to rescan after last time reported
            long ten_min = 10 * 60 * 1000;
            long three_min = 3 * 60 * 1000;
            if (lastTimeReported > 0 && System.currentTimeMillis() - lastTimeReported < ten_min) {
                handler.postDelayed(this, 30000L);
                return;
            }

            if (lastParentDetection > 0 && System.currentTimeMillis() - lastParentDetection < three_min) {
                handler.postDelayed(this, 30000L);
                return;
            }

            // if phone is locked, do not take picture
            android.app.KeyguardManager myKM = (android.app.KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
            if( myKM.inKeyguardRestrictedInputMode()) {
                Log.i("BackgroundService", "Phone is locked. Not capturing image.");
                handler.postDelayed(this, 10000L);
                return;
            }

//            Toast.makeText(BackgroundService.this,
//                    "Capturing image.", Toast.LENGTH_SHORT).show();

            Log.i("BackgroundService", "Capturing image.");

            try {
                takePicture();
            } catch (Exception e) {
                Log.e("BackgroundService", "Failed to take picture", e);
            }

            handler.postDelayed(this, 30000L);
        }
    };


    /*
    takePicture wrapper to mute system camera sound
     */
    public void takePicture() {
        AudioManager mgr = (AudioManager)getSystemService(Context.AUDIO_SERVICE);
        mgr.setStreamMute(AudioManager.STREAM_SYSTEM, true);
        super.takePicture();
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                mgr.setStreamMute(AudioManager.STREAM_SYSTEM, false);
            }
        }, 1000L);
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        prefs = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {

            if (HiddenCameraUtils.canOverDrawOtherApps(this)) {
                CameraConfig cameraConfig = new CameraConfig()
                        .getBuilder(this)
                        .setCameraFacing(CameraFacing.FRONT_FACING_CAMERA)
                        .setCameraResolution(CameraResolution.MEDIUM_RESOLUTION)
                        .setImageFormat(CameraImageFormat.FORMAT_JPEG)
                        .build();

                startCamera(cameraConfig);

                handler.post(runnable);
            } else {
                //Open settings to grant permission for "Draw other apps".
                HiddenCameraUtils.openDrawOverPermissionSetting(this);
            }
        } else {
            //TODO Ask your parent activity for providing runtime permission
//            Toast.makeText(this, "Camera permission not available", Toast.LENGTH_SHORT).show();
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        if (handler != null) {
            handler.removeCallbacksAndMessages(null);
        }

        stopCamera();
    }

    NativeMethods.MeasureDistTask mMeasureDistTask;
    @Override
    public void onImageCapture(@NonNull File imageFile) {
//        Toast.makeText(this,
//                        "Captured image size is : " + imageFile.length(),
//                        Toast.LENGTH_SHORT)
//                .show();

        Mat mRgba = Imgcodecs.imread(imageFile.getAbsolutePath());
        Mat mGray = new Mat();
        Imgproc.cvtColor(mRgba, mGray, Imgproc.COLOR_BGR2GRAY);;

        InputStream is = getResources().openRawResource(R.raw.haarcascade_frontalface_alt2);
        File cascadeDir = getDir("cascade", Context.MODE_PRIVATE);
        File mCascadeFile = new File(cascadeDir, "haarcascade_frontalface_alt2.xml");

        try {
            FileOutputStream os = new FileOutputStream(mCascadeFile);

            byte[] buffer = new byte[4096];
            int bytesRead;

            while ((bytesRead = is.read(buffer)) != -1) {
                os.write(buffer, 0, bytesRead);
            }

            is.close();
            os.close();
        } catch (Exception e) {
            Log.e("BackgroundService", "Failed to load cascade. Exception thrown: " + e);
        }

        Mat mRgbaTmp = mRgba;
        Mat mGrayTmp = mGray;

        int orientation = getResources().getConfiguration().orientation;
        switch (orientation) { // RGB image
            case ActivityInfo.SCREEN_ORIENTATION_PORTRAIT:
            case ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT:
                Core.flip(mRgbaTmp, mRgbaTmp, 0); // Flip along x-axis
                break;
            case ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE:
            case ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE:
                Core.flip(mRgbaTmp, mRgbaTmp, 1); // Flip along y-axis
                break;
        }
        switch (orientation) { // Grayscale image
            case ActivityInfo.SCREEN_ORIENTATION_PORTRAIT:
                Core.transpose(mGrayTmp, mGrayTmp); // Rotate image
                Core.flip(mGrayTmp, mGrayTmp, -1); // Flip along both axis
                break;
            case ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT:
                Core.transpose(mGrayTmp, mGrayTmp); // Rotate image
                break;
            case ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE:
                Core.flip(mGrayTmp, mGrayTmp, 1); // Flip along y-axis
                break;
            case ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE:
                Core.flip(mGrayTmp, mGrayTmp, 0); // Flip along x-axis
                break;
        }

        mGray = mGrayTmp;
        mRgba = mRgbaTmp;

        CascadeClassifier faceDetector = new CascadeClassifier(mCascadeFile.getAbsolutePath());

        // Detect faces in the grayscale image
        MatOfRect faceDetections = new MatOfRect();
        faceDetector.detectMultiScale(mGray, faceDetections);

        // Check if at least one face is detected
        if (faceDetections.toArray().length == 0) {
//            SaveImage(mGray);
            return;
        }

        if (mMeasureDistTask != null && mMeasureDistTask.getStatus() != AsyncTask.Status.FINISHED) {
            return;
        }

        if (mGray.total() == 0)
            return;

        // Scale image in order to decrease computation time and make the image square,
        // so it does not crash on phones with different aspect ratios for the front
        // and back camera
        Size imageSize = new Size(200, 200);
        Imgproc.resize(mGray, mGray, imageSize);
        //SaveImage(mGray);

        Mat image = mGray.reshape(0, (int) mGray.total()); // Create column vector

        // Calculate normalized Euclidean distance
        boolean useEigenfaces = prefs.getBoolean("useEigenfaces", false);
        mMeasureDistTask = new NativeMethods.MeasureDistTask(useEigenfaces, measureDistTaskCallback);
        mMeasureDistTask.execute(image);
    }

    public void SaveImage(Mat mat) {
        Mat mIntermediateMat = new Mat();

        if (mat.channels() == 1) // Grayscale image
            Imgproc.cvtColor(mat, mIntermediateMat, Imgproc.COLOR_GRAY2BGR);
        else
            Imgproc.cvtColor(mat, mIntermediateMat, Imgproc.COLOR_RGBA2BGR);

        File path = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "BackgroundService"); // Save pictures in Pictures directory
        path.mkdir(); // Create directory if needed
        String fileName = "IMG_" + new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(new Date()) + ".png";
        File file = new File(path, fileName);

        boolean bool = Imgcodecs.imwrite(file.toString(), mIntermediateMat);

        if (bool)
            Log.i("BackgroundService", "SUCCESS writing image to external storage");
        else
            Log.e("BackgroundService", "Failed writing image to external storage");
    }

    private TinyDB tinydb;
    private long lastParentDetection;
    private NativeMethods.MeasureDistTask.Callback measureDistTaskCallback = new NativeMethods.MeasureDistTask.Callback() {
        @Override
        public void onMeasureDistComplete(Bundle bundle) {
            if (bundle == null) {
                return;
            }

            tinydb = new TinyDB(getApplicationContext());
            ArrayList<String> imagesLabels = tinydb.getListString("imagesLabels");

            float faceThreshold = prefs.getFloat("faceThreshold", -1);
            float distanceThreshold = prefs.getFloat("distanceThreshold", -1);

            float minDist = bundle.getFloat(NativeMethods.MeasureDistTask.MIN_DIST_FLOAT);
            if (minDist != -1) {
                int minIndex = bundle.getInt(NativeMethods.MeasureDistTask.MIN_DIST_INDEX_INT);
                float faceDist = bundle.getFloat(NativeMethods.MeasureDistTask.DIST_FACE_FLOAT);
                if (imagesLabels.size() > minIndex) { // Just to be sure
                    Log.i("BackgroundService", "dist[" + minIndex + "]: " + minDist + ", face dist: " + faceDist + ", label: " + imagesLabels.get(minIndex));

                    String minDistString = String.format(Locale.US, "%.4f", minDist);
                    String faceDistString = String.format(Locale.US, "%.4f", faceDist);

                    if (faceDist < faceThreshold && minDist < distanceThreshold) {
                        String person = imagesLabels.get(minIndex);
                        if (person.equals(lastPerson)) {
                            samePersonInaRow++;
                        } else {
                            samePersonInaRow = 1;
                        }

                        lastPerson = person;
                        Log.i("BackgroundService", "Face detected: " + person + ". Distance: " + minDistString);

                        if (samePersonInaRow < 3) {
                            takePicture();
                            return;
                        }

                        if (person.toLowerCase().startsWith("kid")) {
                            reportKid(person);
                            lastTimeReported = System.currentTimeMillis();
                        } else {
                            lastParentDetection = System.currentTimeMillis();
                        }

                        samePersonInaRow = 0;
                    } else if (faceDist < faceThreshold) // 2. Near face space but not near a known face class
                        Log.i("BackgroundService","Unknown face. Face distance: " + faceDistString + ". Closest Distance: " + minDistString);
                    else if (minDist < distanceThreshold) // 3. Distant from face space and near a face class
                        Log.i("BackgroundService","False recognition. Face distance: " + faceDistString + ". Closest Distance: " + minDistString);
                    else // 4. Distant from face space and not near a known face class.
                        Log.i("BackgroundService","Image is not a face. Face distance: " + faceDistString + ". Closest Distance: " + minDistString);
                }
            }
        }
    };

    private void reportKid(String label) {
        Log.i("BackgroundService", "Sending POST request to server");

        // Send POST request to server
        try {
            URL url = new URL("https://firestore.googleapis.com/v1/projects/kidhasphonealert/databases/(default)/documents/alerts?key=AIzaSyAYGBPOO1kPiPceMuUa_BQzWhyV92-sNas");

            // Create a connection to the URL
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/json");

            // Create the JSON body for the POST request
            JSONObject jsonBody = new JSONObject();
            JSONObject fields = new JSONObject();
            fields.put("kid_name", createStringValue(label));
            fields.put("timestamp", createIntegerValue(System.currentTimeMillis()));
            jsonBody.put("fields", fields);

            // Write the JSON body to the connection's output stream
            OutputStream os = connection.getOutputStream();
            os.write(jsonBody.toString().getBytes("UTF-8"));
            os.close();

            // Get the response code from the server
            int responseCode = connection.getResponseCode();

            if (responseCode == HttpURLConnection.HTTP_OK) {
                // POST request was successful
                Log.i("BackgroundService", "POST request successful");
            } else {
                // POST request failed
                Log.e("BackgroundService", "POST request failed with response code: " + responseCode);
            }

            connection.disconnect();
        } catch (Exception e) {
            // Handle any exceptions that may occur during the POST request
            Log.e("BackgroundService", "Error sending POST request", e);
        }
    }

    private JSONObject createStringValue(String value) throws JSONException {
        JSONObject stringValue = new JSONObject();
        stringValue.put("stringValue", value);
        return stringValue;
    }

    private JSONObject createIntegerValue(long value) throws JSONException {
        JSONObject integerValue = new JSONObject();
        integerValue.put("integerValue", value);
        return integerValue;
    }

    @Override
    public void onCameraError(@CameraError.CameraErrorCodes int errorCode) {
        switch (errorCode) {
            case CameraError.ERROR_CAMERA_OPEN_FAILED:
                //Camera open failed. Probably because another application
                //is using the camera
//                Toast.makeText(this, R.string.error_cannot_open, Toast.LENGTH_LONG).show();
                break;
            case CameraError.ERROR_IMAGE_WRITE_FAILED:
                //Image write failed. Please check if you have provided WRITE_EXTERNAL_STORAGE permission
//                Toast.makeText(this, R.string.error_cannot_write, Toast.LENGTH_LONG).show();
                break;
            case CameraError.ERROR_CAMERA_PERMISSION_NOT_AVAILABLE:
                //camera permission is not available
                //Ask for the camera permission before initializing it.
//                Toast.makeText(this, R.string.error_cannot_get_permission, Toast.LENGTH_LONG).show();
                break;
            case CameraError.ERROR_DOES_NOT_HAVE_OVERDRAW_PERMISSION:
                //Display information dialog to the user with steps to grant "Draw over other app"
                //permission for the app.
                HiddenCameraUtils.openDrawOverPermissionSetting(this);
                break;
            case CameraError.ERROR_DOES_NOT_HAVE_FRONT_CAMERA:
//                Toast.makeText(this, R.string.error_not_having_camera, Toast.LENGTH_LONG).show();
                break;
        }

//        stopSelf();
    }

    @Override
    public void onTaskRemoved(Intent rootIntent){
        Intent intent = new Intent(getApplicationContext(), FaceRecognitionAppActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent pendingIntent = PendingIntent.getActivity(getApplicationContext(), 0, intent, PendingIntent.FLAG_ONE_SHOT);
        AlarmManager alarmManager = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
        alarmManager.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + 500, pendingIntent);

        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                minimizeApp();
            }
        }, 10000L);


        super.onTaskRemoved(rootIntent);
    }

    public void minimizeApp() {
        Intent homeIntent = new Intent(Intent.ACTION_MAIN);
        homeIntent.addCategory(Intent.CATEGORY_HOME);
        homeIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(homeIntent);
    }
}