package com.lauszus.facerecognitionapp;

import android.content.Intent;
import android.os.IBinder;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;

import com.androidhiddencamera.HiddenCameraService;

import java.io.File;

public class BackgroundService extends HiddenCameraService {
    public void onCreate() {

    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onImageCapture(@NonNull File imageFile) {

    }

    @Override
    public void onCameraError(int errorCode) {

    }
}
