package edu.buffalo.cse.roadsensing;

import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Intent;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.widget.Toast;

import com.github.pires.obd.commands.ObdCommand;
import com.github.pires.obd.commands.SpeedCommand;
import com.github.pires.obd.commands.engine.MassAirFlowCommand;
import com.github.pires.obd.commands.engine.RPMCommand;
import com.github.pires.obd.commands.fuel.AirFuelRatioCommand;
import com.github.pires.obd.commands.protocol.EchoOffCommand;
import com.github.pires.obd.commands.protocol.LineFeedOffCommand;
import com.github.pires.obd.commands.protocol.ObdResetCommand;
import com.github.pires.obd.commands.protocol.SelectProtocolCommand;
import com.github.pires.obd.commands.protocol.TimeoutCommand;
import com.github.pires.obd.enums.ObdProtocols;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

public class OBDService extends Service {
    //well-know SPP UUID for Bluetooth Serial Board
    static final UUID OBD_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    final BluetoothAdapter btAdapter = BluetoothAdapter.getDefaultAdapter();
    BluetoothDevice btDevice;
    BluetoothSocket socket;
    Bundle obdreadings = new Bundle();
    String result;
    Thread t;

    static final int GET_MSG = 1;
    static final int SET_REPLY = 2;

    /**
     * Start Bluetooth
     */
    public void startService() {
        //find pared device
        Set<BluetoothDevice> pariedDevices = btAdapter.getBondedDevices();
        for (BluetoothDevice device:pariedDevices){
            String name = device.getName().toUpperCase();
            if (name.contains("OBD"))
                btDevice = btAdapter.getRemoteDevice(device.getAddress());
        }
        if (btDevice == null)
            Toast.makeText(getApplicationContext(),"No Paired OBD2 Device found", Toast.LENGTH_SHORT).show();
        //start connection
        btAdapter.cancelDiscovery();
        try {
            socket = btDevice.createRfcommSocketToServiceRecord(OBD_UUID);
            socket.connect();
        } catch (IOException e) {
            e.printStackTrace();
            Toast.makeText(getApplicationContext(), "Cannot Connect Bluetooth Socket", Toast.LENGTH_SHORT).show();
            this.stopSelf();
        }
    }

    /**
     * initialize OBD
     */
    public void initOBD(){
        BlockingQueue<ObdCommand> jobsQueue = new LinkedBlockingQueue<>();
        try {
            jobsQueue.put(new ObdResetCommand());
            jobsQueue.put(new EchoOffCommand());
            //need to send twice...
            jobsQueue.put(new EchoOffCommand());
            jobsQueue.put(new LineFeedOffCommand());
            jobsQueue.put(new TimeoutCommand(62));// about 250 ms
            jobsQueue.put(new SelectProtocolCommand(ObdProtocols.AUTO));
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        //run the initial commends
        while(!jobsQueue.isEmpty()){
            ObdCommand cmd = null;
            try {
                cmd = jobsQueue.take();
                cmd.run(socket.getInputStream(),socket.getOutputStream());
            } catch (InterruptedException e) {
                e.printStackTrace();
            } catch (IOException e) {
                e.printStackTrace();
            }
            if (cmd != null){
                result = cmd.getFormattedResult();
            }
        }
    }

    /**
     *  Runnable for recording OBD, commands are set in the constructor
     */
    class OBDRecordable implements Runnable{
        ArrayList<ObdCommand> commandList;
        File file;

        /**
         * Constructor
         * @param foldername
         * @param filename
         */
        OBDRecordable(String foldername, String filename){

            commandList = new ArrayList<>();
            commandList.add(new RPMCommand());
            commandList.add(new SpeedCommand());
            commandList.add(new MassAirFlowCommand());
            commandList.add(new AirFuelRatioCommand());

            file = new File(Environment.getExternalStorageDirectory() + foldername + filename);

        }

        @Override
        public void run() {
            SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss.SSS");
            FileWriter fw = null;
            if(!file.exists())
                try {
                    file.createNewFile();
                    fw = new FileWriter(file, true);
                } catch (IOException e) {
                    e.printStackTrace();
                }
            BufferedWriter bw = new BufferedWriter(fw);
            while (!Thread.currentThread().isInterrupted()) {
                String t = "";
                for (ObdCommand cmd : commandList) {
                    try {
                        //long start = System.currentTimeMillis()%1000000;
                        cmd.run(socket.getInputStream(), socket.getOutputStream());
                        //long end = System.currentTimeMillis()%1000000;
                        //String time = sdf.format(Calendar.getInstance().getTime());
                        long time = System.currentTimeMillis()%3600000;
                        String r = cmd.getFormattedResult();
                        bw.write(time + ", " + r + "\n");
                        bw.flush();
                        t += r + ", ";
                    } catch (IOException e) {
                        e.printStackTrace();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        e.printStackTrace();
                    }
                }
                result = t;
            }
            try {
                bw.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        startService();
        initOBD();
        String out = intent.getStringExtra("outputname");
        String folder = intent.getStringExtra("foldername");
        t = new Thread(new OBDRecordable(folder,out));
        t.start();
        return messenger.getBinder();
    }


    //single thread message passing
    final Messenger messenger = new Messenger(new Handler(){
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what){
                case GET_MSG:
                    //send back
                    Messenger client = msg.replyTo;
                    obdreadings.putString("result", result);

                    Message reply = Message.obtain(null,SET_REPLY,obdreadings);
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

    @Override
    public boolean onUnbind(Intent intent) {
        t.interrupt();
        try {
            socket.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
        return super.onUnbind(intent);
    }
}
