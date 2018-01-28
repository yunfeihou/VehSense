package edu.buffalo.cse.roadsensing;

import android.annotation.SuppressLint;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.util.Log;

import com.github.pires.obd.commands.SystemOfUnits;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;

/***
 * This service start a new process, as specified in manifest.xml
 */
public class SensingService extends Service {

    static final int GET_MSG = 1;
    static final int SET_REPLY = 2;

    SensorManager sensorManager;
    LocationManager locationManager;

    Bundle readings = new Bundle();
    BufferedWriter bw; //open/close in onBind()/onUnbind()
    boolean isWriterReady = false;
    String result = "";


    @Override
    public void onCreate() {
        final SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss.SSS");
        super.onCreate();
        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);

    }

    @Override
    public boolean onUnbind(Intent intent) {
        isWriterReady = false;
        try {
            bw.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
        sensorManager.unregisterListener(sensorEventListener);
        locationManager.removeUpdates(locationListener);
        return super.onUnbind(intent);
    }

    @SuppressLint("MissingPermission")
    @Override
    public IBinder onBind(Intent intent) {

        //create file
        String out = intent.getStringExtra("outputname");
        String folder = intent.getStringExtra("foldername");
        File file = new File(Environment.getExternalStorageDirectory() + folder + out);

        FileWriter fw = null;
        if(!file.exists())
            try {
                file.createNewFile();
                fw = new FileWriter(file, true);
            } catch (IOException e) {
                e.printStackTrace();
            }
        bw = new BufferedWriter(fw);
        isWriterReady = true;

        //register sensor
        Sensor accSensor, graSensor, gyrSensor, linSensor, rotSensor;
        accSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        graSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY);
        gyrSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
        linSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION);
        rotSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
        Log.i("Acc min Delay", String.valueOf(accSensor.getMinDelay()));
        // My test shows there is no need to use multi-thread when updating the reading,
        // multiple readings will be updated within 1 millisecond
        sensorManager.registerListener(sensorEventListener, accSensor, SensorManager.SENSOR_DELAY_FASTEST);
        sensorManager.registerListener(sensorEventListener, graSensor, SensorManager.SENSOR_DELAY_FASTEST);
        sensorManager.registerListener(sensorEventListener, gyrSensor, SensorManager.SENSOR_DELAY_FASTEST);
        sensorManager.registerListener(sensorEventListener, linSensor, SensorManager.SENSOR_DELAY_FASTEST);
        sensorManager.registerListener(sensorEventListener, rotSensor, SensorManager.SENSOR_DELAY_FASTEST);

        locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 0, 0, locationListener);

        return messenger.getBinder();
    }

    LocationListener locationListener = new LocationListener() {
        @Override
        public void onLocationChanged(Location location) {
            String output = "";
            if(bw != null && isWriterReady){
                try {
                    //Lat, Long, Speed, Satellite count
                    long time = System.currentTimeMillis()%3600000;
                    output = "GPS, " + time + ", " + location.getLatitude() + ", " + location.getLongitude()
                            +", " + location.getSpeed() + ", "+ location.getExtras().get("satellites")+"\n";
                    bw.write(output);
                } catch (IOException e) {
                    e.printStackTrace();
                }
                result = output;
            }
        }

        @Override
        public void onStatusChanged(String provider, int status, Bundle extras) {

        }

        @Override
        public void onProviderEnabled(String provider) {

        }

        @Override
        public void onProviderDisabled(String provider) {

        }
    };

    SensorEventListener sensorEventListener = new SensorEventListener() {
        @Override
        public void onSensorChanged(SensorEvent event) {

            //String time = sdf.format(Calendar.getInstance().getTime());
            long time = System.currentTimeMillis()%3600000;
            try {
                // TODO: 2015/12/11 format output
                String output = "";
                switch (event.sensor.getType()){
                    case Sensor.TYPE_ACCELEROMETER:
                        output = "ACC, ";
                        break;
                    case Sensor.TYPE_GRAVITY:
                        output = "GRV, ";
                        break;
//                    case Sensor.TYPE_GYROSCOPE:
//                        output = "GYR, ";
//                        break;
//                    case Sensor.TYPE_LINEAR_ACCELERATION:
//                        output = "LIN, ";
//                        break;
//                    case Sensor.TYPE_ROTATION_VECTOR:
//                        output = "ROT, ";
//                        break;
                }
                output += time + ", " + event.values[0] +", "+event.values[1]+ ", "+event.values[2]+"\n";
                if(bw != null){
                    bw.write(output);
                    // bw.flush();
                }
               // result = output;

            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) {

        }
    };

    //single thread message passing
    final Messenger messenger = new Messenger(new Handler(){
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what){
                case GET_MSG:
                    //send back acc_x
                    Messenger client = msg.replyTo;
                    readings.putString("sensorresult",result);

                    Message reply = Message.obtain(null,SET_REPLY,readings);
                    try {
                        client.send(reply);
                    } catch (RemoteException e) {
                        e.printStackTrace();
                    }
                    break;
                default:
                    super.handleMessage(msg);
            }

        }
    });

}
