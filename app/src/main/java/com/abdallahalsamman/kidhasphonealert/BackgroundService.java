package com.abdallahalsamman.kidhasphonealert;

import static android.content.Intent.FLAG_ACTIVITY_NEW_TASK;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.graphics.BitmapFactory;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.media.AudioManager;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemClock;
import android.preference.PreferenceManager;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.app.ActivityCompat;
import android.support.v4.app.NotificationCompat;
import android.support.v4.content.ContextCompat;
import android.support.v4.content.FileProvider;
import android.util.Log;
import android.widget.Toast;

import com.androidhiddencamera.CameraConfig;
import com.androidhiddencamera.CameraError;
import com.androidhiddencamera.HiddenCameraService;
import com.androidhiddencamera.HiddenCameraUtils;
import com.androidhiddencamera.config.CameraFacing;
import com.androidhiddencamera.config.CameraImageFormat;
import com.androidhiddencamera.config.CameraResolution;
import com.androidhiddencamera.config.CameraRotation;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

public class BackgroundService extends HiddenCameraService {

    private String KID_LABEL = "zainab";
    private class sensorEventListener implements SensorEventListener {

        public int mCameraRotation = 270;

        private void updateCameraRotation(int rotation) {
            mCameraRotation = rotation;
        }

        @Override
        public void onSensorChanged(SensorEvent event) {
            if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
                float x = event.values[0];
                float y = event.values[1];
                float z = event.values[2];

                if (Math.abs(x) > Math.abs(y)) {
                    // Device is in landscape mode
                    if (x > 0) {
                        // Landscape right
//                        Log.i("BackgroundService", "Landscape right");
                        updateCameraRotation(0);
                    } else {
                        // Landscape left
//                        Log.i("BackgroundService", "Landscape left");
                        updateCameraRotation(180);
                    }
                } else {
                    // Device is in portrait mode
                    if (y > 0) {
                        // Normal portrait mode
//                        Log.i("BackgroundService", "portrait mode");
                        updateCameraRotation(270);
                    } else {
                        // Upside down portrait mode
//                        Log.i("BackgroundService", "upsidedown mode");
                        updateCameraRotation(90);
                    }
                }
            }
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int i) {

        }
    }

    SharedPreferences prefs;
    private long lastTimeReported = 0;
    private String lastPerson;
    private static final String TAG = "BackgroundService";
    private Handler handler = new Handler();
    private Runnable runnable = new Runnable() {
        @Override
        public void run() {
            // wait 10 min to rescan after last time reported
//            long one_min = 1 * 60 * 1000;
            long one_min = 5 * 1000;
            if (lastTimeReported > 0 && System.currentTimeMillis() - lastTimeReported < one_min) {
                Log.i("BackgroundService", "Waiting 3 seconds to rescan after last time reported");
                handler.postDelayed(this, 3000L);
                return;
            }

            if (lastParentDetection > 0 && System.currentTimeMillis() - lastParentDetection < one_min) {
                Log.i("BackgroundService", "Waiting 3 seconds to rescan after last parent detection");
                handler.postDelayed(this, 3000L);
                return;
            }

            // get screen on/off status


            // if phone is locked, do not take picture
            android.app.KeyguardManager myKM = (android.app.KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);

            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            boolean isScreenOn = true;
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.KITKAT_WATCH) {
                isScreenOn = pm.isInteractive();
            }

            if (myKM.inKeyguardRestrictedInputMode() || !isScreenOn) {
                Log.i("BackgroundService", "Phone is locked. Not capturing image.");
                handler.postDelayed(this, 10000L);
                return;
            }

            try {
                Log.i("BackgroundService", "Capturing image.");
                takePicture();
            } catch (Exception e) {
                Log.e("BackgroundService", "Failed to take picture", e);

                stopSelf();
                Intent myService = new Intent(getApplicationContext(), BackgroundService.class);
                startService(myService);
                return;
            }

            handler.postDelayed(this, 15000L);
        }
    };

    private Toast mToast;
    private BootReceiver mReceiver;

    @Override
    public void onCreate() {
        super.onCreate();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            String channelId = createNotificationChannel("my_service", "My Background Service");
            Notification notification = new NotificationCompat.Builder(this, channelId)
                    .setContentTitle("Service RunWning")
                    .setContentText("Listening for screen ON event...")
                    .setSmallIcon(R.drawable.arrow_down_released)
                    .build();

            startForeground(1, notification);
        }

        mReceiver = new BootReceiver();
        IntentFilter filter = new IntentFilter(Intent.ACTION_USER_PRESENT);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_BOOT_COMPLETED);
        filter.addAction(Intent.ACTION_MY_PACKAGE_SUSPENDED);
        filter.addAction(Intent.ACTION_MY_PACKAGE_UNSUSPENDED);
        Log.i(TAG, "Registering receiver");
        registerReceiver(mReceiver, filter);

        prefs = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());

        while (
                ContextCompat.checkSelfPermission(getApplicationContext(), Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED ||
                        ContextCompat.checkSelfPermission(getApplicationContext(), Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED ||
                        !HiddenCameraUtils.canOverDrawOtherApps(this)
        ) {
            try {
                Log.d(TAG, "Waiting for permissions...");
                Thread.sleep(60 * 1000);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }

        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        sensorManager.registerListener(mSensorEventListener, accelerometer, SensorManager.SENSOR_DELAY_NORMAL);

        mCameraConfig = new CameraConfig()
                .getBuilder(this)
                .setCameraFacing(CameraFacing.FRONT_FACING_CAMERA)
                .setCameraResolution(CameraResolution.LOW_RESOLUTION)
                .setImageFormat(CameraImageFormat.FORMAT_JPEG)
                .build();

        startCamera(mCameraConfig);

        Log.i(TAG, "Started runnable");

        handler.post(runnable);
    }

    private void showToast(String message, int duration) {
        if (duration != Toast.LENGTH_SHORT && duration != Toast.LENGTH_LONG)
            throw new IllegalArgumentException();
        if (mToast != null && mToast.getView().isShown())
            mToast.cancel(); // Close the toast if it is already open
        mToast = Toast.makeText(this, message, duration);
        mToast.show();
    }


    private CameraConfig mCameraConfig;
    private SensorManager sensorManager;
    private Sensor accelerometer;
    private sensorEventListener mSensorEventListener = new sensorEventListener();
    private boolean isStuck;

    /*
    takePicture wrapper to mute system camera sound
     */
    public void takePicture() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            return;
        }

        AudioManager mgr = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        mgr.adjustStreamVolume(AudioManager.STREAM_SYSTEM, AudioManager.ADJUST_MUTE, 0);

        isStuck = true;

        super.takePicture();

        handler.postDelayed(() -> {
            if (isStuck) {
                Log.e(TAG, "Camera is stuck. Restarting service.");
                stopSelf();
                Intent myService = new Intent(getApplicationContext(), BackgroundService.class);
                startService(myService);
            }
        }, 5000L);

        try {
            Thread.sleep(2000);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }

        mgr.adjustStreamVolume(AudioManager.STREAM_SYSTEM, AudioManager.ADJUST_UNMUTE, 0);
    }

    private static final int PERMISSIONS_REQUEST_CODE = 0;

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private String createNotificationChannel(String channelId, String channelName) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel chan = new NotificationChannel(channelId, channelName, NotificationManager.IMPORTANCE_NONE);
            chan.setLightColor(getColor(R.color.colorAccent));
            chan.setLockscreenVisibility(Notification.VISIBILITY_PRIVATE);
            NotificationManager service = getSystemService(NotificationManager.class);
            service.createNotificationChannel(chan);
            return channelId;
        }
        return "";
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "onDestroy");
//        unregisterReceiver(mReceiver);

        if (handler != null) {
            handler.removeCallbacksAndMessages(null);
        }

        stopCamera();
    }

    private void rotateImageAndSave(File imageFile, int degrees) {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        Bitmap originalBitmap = BitmapFactory.decodeFile(imageFile.getAbsolutePath(), options);

        if (originalBitmap != null) {
            Matrix matrix = new Matrix();
            matrix.postRotate(degrees);

            Bitmap rotatedBitmap = Bitmap.createBitmap(originalBitmap, 0, 0, originalBitmap.getWidth(), originalBitmap.getHeight(), matrix, true);
            // It's important to recycle the original bitmap only after we no longer need it
            originalBitmap.recycle();

            try (FileOutputStream out = new FileOutputStream(imageFile)) {
                rotatedBitmap.compress(Bitmap.CompressFormat.JPEG, 100, out); // Compress and save the rotated bitmap
                Log.d(TAG, "Rotated image saved to: " + imageFile.getAbsolutePath());
            } catch (IOException e) {
                Log.e(TAG, "Failed to save rotated image", e);
            } finally {
                rotatedBitmap.recycle(); // Recycle the rotated bitmap to free up memory
            }
        } else {
            Log.e(TAG, "Failed to decode the image file: " + imageFile.getAbsolutePath());
        }
    }

    private int failedAttempts = 0;

    @Override
    public void onImageCapture(@NonNull File imageFile) {
        isStuck = false;
        Log.d(TAG, "Image captured saved at: " + imageFile.getAbsolutePath());

        if (mSensorEventListener.mCameraRotation == 0) {
            Log.d(TAG, "Image is already in correct orientation. No need to rotate.");
        } else {
            Log.d(TAG, "Rotating image by: " + mSensorEventListener.mCameraRotation + " degrees");
            rotateImageAndSave(imageFile, mSensorEventListener.mCameraRotation);
        }

//        Intent intent = new Intent(Intent.ACTION_VIEW);
//        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
//        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
//        intent.setDataAndType(Uri.parse(imageFile.getAbsolutePath()), "video/*");
//        startActivity(intent);

        new AsyncTask<File, Void, Void>() {
            @SuppressLint("StaticFieldLeak")
            @Override
            protected Void doInBackground(File... files) {
                try {
                    File file = files[0];
                    String url = "http://rankgenius.asamman.com:8001/";
                    String boundary = "*****" + System.currentTimeMillis() + "*****";
                    String crlf = "\r\n";
                    String twoHyphens = "--";

                    HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
                    connection.setRequestMethod("POST");
                    connection.setRequestProperty("Connection", "Keep-Alive");
                    connection.setRequestProperty("Cache-Control", "no-cache");
                    connection.setRequestProperty("Content-Type", "multipart/form-data;boundary=" + boundary);
                    connection.setDoOutput(true);

                    OutputStream outputStream = connection.getOutputStream();
                    outputStream.write((twoHyphens + boundary + crlf).getBytes());
                    outputStream.write(("Content-Disposition: form-data; name=\"image\";filename=\"" + file.getName() + "\"" + crlf).getBytes());
                    outputStream.write(("Content-Type: image/jpeg" + crlf).getBytes());
                    outputStream.write(crlf.getBytes());

                    InputStream inputStream = getApplicationContext().getContentResolver().openInputStream(Uri.fromFile(file));
                    byte[] buffer = new byte[4096];
                    int bytesRead;
                    while ((bytesRead = inputStream.read(buffer)) != -1) {
                        outputStream.write(buffer, 0, bytesRead);
                    }
                    outputStream.write(crlf.getBytes());

                    outputStream.write((twoHyphens + boundary + twoHyphens + crlf).getBytes());
                    outputStream.flush();
                    outputStream.close();

                    int responseCode = connection.getResponseCode();
                    if (responseCode == HttpURLConnection.HTTP_OK) {
                        Log.i(TAG, "Image uploaded successfully");
                        InputStream responseStream = connection.getInputStream();
                        byte[] responseBuffer = new byte[4096];
                        int responseBytesRead;
                        StringBuilder response = new StringBuilder();
                        while ((responseBytesRead = responseStream.read(responseBuffer)) != -1) {
                            response.append(new String(responseBuffer, 0, responseBytesRead));
                        }
                        Log.i(TAG, "Response: " + response.toString());

                        // parse json response
                        JSONArray jsonArray = new JSONArray(response.toString());
                        JSONObject jsonObject = jsonArray.getJSONObject(0);

                        // extract age field from jsonResponse that looks like [{"age":32,"region":{"x":56,"y":108,"w":280,"h":280,"left_eye":[139,219],"right_eye":[244,220]},"face_confidence":0.89}]
                        int age = jsonObject.getInt("age");
                        String gender = jsonObject.getString("dominant_gender");

                        if ((gender.equals("Woman") && age > 30) || (gender.equals("Man") && age > 18)) {
                            lastParentDetection = System.currentTimeMillis();
                            failedAttempts = 0;
                        } else {
                            failedAttempts = 0;
                            reportKid(KID_LABEL);
                        }
                    } else {
                        failedAttempts++;
                        Log.e(TAG, "Failed to upload image with response code: " + responseCode);

                        if (failedAttempts > 5) {
                            reportKid(KID_LABEL);
                            failedAttempts = 0;
                        }
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Failed to upload image", e);
                }
                return null;
            }
        }.execute(imageFile);
    }

    private long lastParentDetection;

    private void reportKid(String label) {
        Log.i("BackgroundService", "Reporting kid: " + label);

        // Send POST request to server
        try {
            URL url = new URL("https://firestore.googleapis.com/v1/projects/kidhasphonealert/databases/(default)/documents/alerts/"+label+"?key=AIzaSyAYGBPOO1kPiPceMuUa_BQzWhyV92-sNas");

            // Create a connection to the URL
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("PATCH");
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
                lastTimeReported = System.currentTimeMillis();
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
        isStuck = false;
        Log.i(TAG, "Camera error: " + errorCode);
        switch (errorCode) {
            case CameraError.ERROR_CAMERA_OPEN_FAILED:
                //Camera open failed. Probably because another application
                //is using the camera
                Toast.makeText(this, R.string.error_cannot_open, Toast.LENGTH_LONG).show();

                if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                    break;
                }

                try {
                    Thread.sleep(10000);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }

                startCamera(mCameraConfig);
                break;
            case CameraError.ERROR_IMAGE_WRITE_FAILED:
                //Image write failed. Please check if you have provided WRITE_EXTERNAL_STORAGE permission
                Toast.makeText(this, R.string.error_cannot_write, Toast.LENGTH_LONG).show();
                break;
            case CameraError.ERROR_CAMERA_PERMISSION_NOT_AVAILABLE:
                //camera permission is not available
                //Ask for the camera permission before initializing it.
                stopSelf();

                Intent intent = new Intent(this, FaceRecognitionAppActivity.class);
                intent.addFlags(FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);

                Toast.makeText(this, R.string.error_cannot_get_permission, Toast.LENGTH_LONG).show();
                break;
            case CameraError.ERROR_DOES_NOT_HAVE_OVERDRAW_PERMISSION:
                //Display information dialog to the user with steps to grant "Draw over other app"
                //permission for the app.
                HiddenCameraUtils.openDrawOverPermissionSetting(this);
                break;
            case CameraError.ERROR_DOES_NOT_HAVE_FRONT_CAMERA:
                Toast.makeText(this, R.string.error_not_having_camera, Toast.LENGTH_LONG).show();
                break;
        }

//        stopSelf();
    }

    @Override
    public void onTaskRemoved(Intent rootIntent){
        Intent intent = new Intent(getApplicationContext(), FaceRecognitionAppActivity.class);
        intent.addFlags(FLAG_ACTIVITY_NEW_TASK);
        PendingIntent pendingIntent = PendingIntent.getActivity(getApplicationContext(), 0, intent, PendingIntent.FLAG_ONE_SHOT | PendingIntent.FLAG_IMMUTABLE);
        AlarmManager alarmManager = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
        alarmManager.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + 500, pendingIntent);

        super.onTaskRemoved(rootIntent);
    }
}