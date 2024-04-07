package com.abdallahalsamman.kidhasphonealert;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.util.Log;
import android.widget.Toast;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        Log.i("BOOTRECEIVER", intent.getAction());

        Intent myIntent = new Intent(context, FaceRecognitionAppActivity.class);
        myIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(myIntent);

//        Toast.makeText(context, "BOOTRECEIVER", Toast.LENGTH_SHORT).show();
    }

}