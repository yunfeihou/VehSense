package edu.buffalo.cse.roadsensing;

import android.Manifest;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.PowerManager;
import android.os.RemoteException;
import android.support.annotation.NonNull;
import android.support.design.widget.FloatingActionButton;
import android.support.design.widget.Snackbar;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.support.v4.view.MenuItemCompat;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.SearchView;
import android.support.v7.widget.Toolbar;
import android.util.Log;
import android.view.View;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class MainActivity extends AppCompatActivity {

    static final String foldername = "//trace//"; //save outputs at sdcard/trace/...

    //String filename = "a_file_name.csv";

    Messenger sensorService = null;
    boolean isSensorBound;//bound flag
    Bundle readings;//for result

    //This is stupid, but Messenger is said to be more efficient than Intent
    Messenger obdService = null;
    boolean isOBDBound;
    Bundle obdReadings;

    ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            sensorService = new Messenger(service);
            isSensorBound = true;
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            sensorService = null;
            isSensorBound = false;
        }
    };

    ServiceConnection obdConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            obdService = new Messenger(service);
            isOBDBound = true;
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            obdService = null;
            isOBDBound = false;
        }
    };

    //get reply from SensingService
    final Messenger sensorClient = new Messenger(new Handler(){
        @Override
        public void handleMessage(Message msg) {
        switch (msg.what) {
            case SensingService.SET_REPLY:
                readings = (Bundle) msg.obj;
                //Toast.makeText(getApplicationContext(), "message is " + reading.getFloat("acc_x"), Toast.LENGTH_SHORT).show();
                break;
            default:
                super.handleMessage(msg);
        }
        }
    });

    final Messenger obdClient = new Messenger(new Handler(){
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case OBDService.SET_REPLY:
                    obdReadings = (Bundle) msg.obj;
                    break;
                default:
                    super.handleMessage(msg);
            }
        }
    });

    /***
     * Request a sensor reading
     * @return string divided by ','
     */
    String requestReadings(){
        if (!isSensorBound)
            return ""; //Sensor not Bound
            //throw new AssertionError("Sensor Service not started");
        Message msg = Message.obtain(null, SensingService.GET_MSG);
        msg.replyTo = sensorClient;
        try{
            //read acc
            sensorService.send(msg);
        } catch (RemoteException e){
            e.printStackTrace();
        }
        //Bundle readings get updated, see sensorClinet
        if(readings == null)
            return "No sensor updates";
        String result = readings.getString("sensorresult");
        return result;
    }

    /**
     * Request a OBD Reading
     * @return
     */
    String requestOBDReadings(){
        if (!isOBDBound)
            return "";
            //throw new AssertionError("OBD Service not started");
        Message msg = Message.obtain(null, OBDService.GET_MSG);
        msg.replyTo = obdClient;
        try{
            obdService.send(msg);
        } catch (RemoteException e){
            e.printStackTrace();
        }
        //Bundle readings get updated, see obdClinet
        if(obdReadings == null)
            return "No OBD updates";
        String result = obdReadings.getString("result");
        return result;
    }

    /***
     * record most recent records of all sensor readings, file will be closed upon isInterrupted()
     * @return writer thread
     */
    Thread createFileWriterThread() {
        final long SENSINGRATE = 1000;//in ms
        final String fname = nameOutputFile("");
        return new Thread(new Runnable() {
            //String filename = "TestTest.csv";
            File file = new File(Environment.getExternalStorageDirectory() + foldername + fname);
            ScheduledThreadPoolExecutor threadexecutor = new ScheduledThreadPoolExecutor(1);

            @Override
            public void run() {
                try {
                    if (!file.exists()) {
                        file.getParentFile().mkdirs();
                        file.createNewFile();
                    }
                    FileWriter fw = new FileWriter(file, true);
                    final BufferedWriter bw = new BufferedWriter(fw);
                    threadexecutor.scheduleAtFixedRate(new Runnable() {
                        SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss.SSS");
                        @Override
                        public void run() {
                            try {
                                // TODO: 2015/12/3 request reading
                                String output = requestReadings();
                                String obdOutput = "";
                                if(isOBDBound)
                                    obdOutput = requestOBDReadings();
                                String time = sdf.format(Calendar.getInstance().getTime());
                                bw.write(output + ", " + obdOutput + time + "\n");
                                //bw.flush();
                            } catch (Exception e) {
                                e.printStackTrace();
                            }
                        }
                    }, 0, SENSINGRATE, TimeUnit.MILLISECONDS);

                    while (!Thread.currentThread().isInterrupted()) {
                        try {
                            Thread.currentThread().sleep(777);
                        } catch (InterruptedException e) {
                            threadexecutor.shutdown();
                            bw.close();
                        }
                    }

                } catch (Exception e) {
                    e.printStackTrace();
                }

            }
        });
    }

    /**
     * get output file name from user input, use time if empty
     * @param prefix use to specify obd/phone
     * @return filename
     */
    String nameOutputFile(String prefix) {
        //Give a file name, use time if empty
        String filename = textdisp.getText().toString();
        String t;
        if (filename != null && !filename.isEmpty()){
            t = filename.replaceAll("[|?*<\":>+\\[\\]/']", "_");
            t = t.replaceAll(" ","").trim();
        }else{
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss");
            t = sdf.format(Calendar.getInstance().getTime());
        }
        String fname = prefix.trim() + t + ".csv";
        return fname;
    }

    //references of Views on the MainActivity, because of laziness...
    TextView textdisp;

    boolean fabclicked = false;
    ArrayList<String> listitems;
    ArrayAdapter<String> listadapter;

    PowerManager powerManager;
    PowerManager.WakeLock wakeLock;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        Toolbar toolbar = (Toolbar) findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        textdisp = (TextView) findViewById(R.id.text_display);
        ListView filelist = (ListView) findViewById(R.id.listview_files);

        powerManager = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = powerManager.newWakeLock(PowerManager.FULL_WAKE_LOCK,
                "SensingWakeLock");

        //file listing
        listitems = new ArrayList<>();
        listadapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1,listitems);
        filelist.setAdapter(listadapter);
        refreshFileList();


        //delete a file
        filelist.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(AdapterView<?> parent, View view, int position, long id) {
                TextView text1 = (TextView) view;
                final int whichfile = position;
                AlertDialog.Builder adb = new AlertDialog.Builder(MainActivity.this);
                final String filename = text1.getText().toString();
                adb.setMessage("Delete " + filename + " ?");
                adb.setNegativeButton("Cancel", null);
                adb.setPositiveButton("OK", new AlertDialog.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        // remove file by name
                        listitems.remove(whichfile);
                        String fname = Environment.getExternalStorageDirectory() + foldername + filename;
                        if (new File(fname).delete())
                            listadapter.notifyDataSetChanged();
                    }
                });
                adb.show();
                return true;
            }
        });

        //Button to start&stop
        final FloatingActionButton fab = (FloatingActionButton) findViewById(R.id.fab);
        fab.setOnClickListener(new FabOnClickListener(fab));

        //Above API 23, Request Permission at Run Time
        if(ContextCompat.checkSelfPermission(MainActivity.this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED){
            ActivityCompat.requestPermissions(MainActivity.this,
                    new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.ACCESS_FINE_LOCATION},1); // 1 for write file and location
        }

        File outputdir = new File(Environment.getExternalStorageDirectory() + foldername);
        if (!outputdir.isDirectory()){
            if (!outputdir.mkdirs()){
                Log.e("MainActivity", "Directory not created");
            }
        }

    }

    /**
     * Click Listener for Floating Action Button
     */
    class FabOnClickListener implements View.OnClickListener {
        FloatingActionButton fab;

        FabOnClickListener(FloatingActionButton btn) { fab = btn; }

        Thread uiThread = null;
        @Override
        public void onClick(View view) {

            //Check run time permission for Location
            if (ActivityCompat.checkSelfPermission(MainActivity.this, Manifest.permission.ACCESS_FINE_LOCATION)
                    != PackageManager.PERMISSION_GRANTED){
                ActivityCompat.requestPermissions(MainActivity.this,
                        new String[]{Manifest.permission.ACCESS_FINE_LOCATION},2); // 2 for location
            }

            if (!fabclicked) {//start
                Snackbar.make(view, "Recording...", Snackbar.LENGTH_LONG)
                        .setAction("Action", null).show();
                //deprecated because getDrawable() need a parameter on theme after lollipop
                Drawable stopicon = getResources().getDrawable(R.drawable.ic_action_stop);
                fab.setImageDrawable(stopicon);
                fabclicked = true;

                Intent sensorservice = new Intent(getApplicationContext(), SensingService.class);
                sensorservice.putExtra("outputname", nameOutputFile("sensor-"));
                sensorservice.putExtra("foldername", foldername);
                bindService(sensorservice, connection, Context.BIND_AUTO_CREATE);

                //update UI
                uiThread = new Thread(new Runnable() {
                    @Override
                    public void run() {
                        while(!Thread.currentThread().isInterrupted()){
                            final String r = requestReadings();
                            // TODO: 2015/12/12 also starts OBD
                            //requestOBDReading();
                            runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    textdisp.setText(r);
                                }
                            });
                            try {
                                Thread.sleep(1000);
                            } catch (InterruptedException e) {
                                e.printStackTrace();
                            }

                        }
                    }
                });
                uiThread.start();

            } else {//stop
                Snackbar.make(view, "Recording has Stopped.", Snackbar.LENGTH_LONG)
                        .setAction("Action", null).show();
                Drawable starticon = getResources().getDrawable(R.drawable.ic_action_start);
                fab.setImageDrawable(starticon);
                fabclicked = false;

                unbindService(connection);
                isSensorBound = false;

                //update UI
                uiThread.interrupt();
                refreshFileList();
                textdisp.setText("");
            }
        }
    }
    /**
     * Refresh the ListView of file list
     */
    void refreshFileList() {
        listitems.clear();
        File[] files = new File(Environment.getExternalStorageDirectory() + foldername).listFiles();
        if (files == null){ //empty list
            return;
        }
        if(files.length > 0){
            for(File f : files){
                listitems.add(f.getName());
            }
        }
        listadapter.notifyDataSetChanged();
    }

    @Override
    protected void onStart() {
        super.onStart();
        // TODO: 2015/12/10 power saving
        wakeLock.acquire();

    }

    @Override
    protected void onStop() {
        super.onStop();
        wakeLock.release();
        if (isSensorBound){
            unbindService(connection);
            isSensorBound = false;
        }
        if(isOBDBound){
            unbindService(obdConnection);
            isOBDBound = false;
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_main, menu);

        final MenuItem notes = menu.findItem(R.id.action_addnote);
        //use a search view in the menu for note input
        final SearchView usernotes = (SearchView) MenuItemCompat.getActionView(notes);
        usernotes.setQueryHint(getString(R.string.action_addnote));
        //usernotes.setInputType(InputType.TYPE_NUMBER_VARIATION_NORMAL);
        usernotes.setOnQueryTextListener(new SearchView.OnQueryTextListener() {
            @Override
            public boolean onQueryTextSubmit(String query) {
                notes.collapseActionView();
                return true;
            }

            @Override
            public boolean onQueryTextChange(String newText) {
                if (!newText.isEmpty())
                    textdisp.setText(newText);
                return true;
            }
        });

        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();

        if (id == R.id.action_startobd) {
            Intent obdservice = new Intent(this, OBDService.class);
            obdservice.putExtra("outputname", nameOutputFile("obd-"));
            obdservice.putExtra("foldername", foldername);
            bindService(obdservice, obdConnection, Context.BIND_AUTO_CREATE);
            invalidateOptionsMenu();
            return true;
        }
        if (id == R.id.stop_obd){
            unbindService(obdConnection);
            isOBDBound = false;
            invalidateOptionsMenu();
            refreshFileList();
            return true;
        }

        return true;
    }


    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        super.onPrepareOptionsMenu(menu);
        //set stop obd button visible if OBD is connected
        menu.findItem(R.id.stop_obd).setVisible(isOBDBound);
        menu.findItem(R.id.action_startobd).setVisible(!isOBDBound);
        return true;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        switch(requestCode){
            case 1:{ //checked in OnCreate()
                if (grantResults.length > 0
                        && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    //create directory for the first time
                    new File(Environment.getExternalStorageDirectory() + foldername).mkdirs();
                } else {
                    Toast.makeText(MainActivity.this, "Permission denied to read your External storage", Toast.LENGTH_SHORT).show();
                }
                return;
            }
            case 2:{ //checked in Fab onClick()
                if (grantResults.length > 0
                        && grantResults[0] == PackageManager.PERMISSION_GRANTED) {

                } else {
                    Toast.makeText(MainActivity.this, "Permission denied to read your GPS", Toast.LENGTH_SHORT).show();
                }
                return;
            }
        }

    }
}
