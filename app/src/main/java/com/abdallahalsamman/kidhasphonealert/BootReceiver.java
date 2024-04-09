package com.abdallahalsamman.kidhasphonealert;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.util.Log;
import android.widget.Toast;

public class BootReceiver extends BroadcastReceiver {
//    private boolean isSuspended = false;
    @Override
    public void onReceive(Context context, Intent intent) {
        Log.i("BOOTRECEIVER", intent.getAction());

        Intent myService = new Intent(context, BackgroundService.class);

        if (intent.getAction().equals(Intent.ACTION_BOOT_COMPLETED)) {
//            Intent myIntent = new Intent(context, FaceRecognitionAppActivity.class);
//            myIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
//            context.startActivity(myIntent);
            context.startService(myService);
//            isSuspended = false;
        }

        /*
//        if (intent.getAction().equals(Intent.ACTION_MY_PACKAGE_SUSPENDED) || intent.getAction().equals(Intent.ACTION_SCREEN_OFF)) {
        if (intent.getAction().equals(Intent.ACTION_MY_PACKAGE_SUSPENDED)) {
//            context.stopService(myService);
            isSuspended = true;
        }

//        if ((intent.getAction().equals(Intent.ACTION_MY_PACKAGE_UNSUSPENDED) || intent.getAction().equals(Intent.ACTION_USER_PRESENT)) && isSuspended) {
        if ((intent.getAction().equals(Intent.ACTION_MY_PACKAGE_UNSUSPENDED)) && isSuspended) {
            context.stopService(myService);
            context.startService(myService);
            isSuspended = false;
        }
    */
    }
}