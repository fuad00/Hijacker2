package com.hijacker;

/*
    Copyright (C) 2019  Christos Kyriakopoulos

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with this program.  If not, see <http://www.gnu.org/licenses/>
 */

import com.google.android.material.snackbar.Snackbar;
import android.util.Log;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;

import static com.hijacker.AP.getAPByMac;
import static com.hijacker.MainActivity.BAND_2;
import static com.hijacker.MainActivity.BAND_5;
import static com.hijacker.MainActivity.BAND_BOTH;
import static com.hijacker.MainActivity.MAX_READLINE_SIZE;
import static com.hijacker.MainActivity.airodump_dir;
import static com.hijacker.MainActivity.always_cap;
import static com.hijacker.MainActivity.band;
import static com.hijacker.MainActivity.busybox;
import static com.hijacker.MainActivity.cap_path;
import static com.hijacker.MainActivity.debug;
import static com.hijacker.MainActivity.delete_extra;
import static com.hijacker.MainActivity.enable_monMode;
import static com.hijacker.MainActivity.enable_on_airodump;
import static com.hijacker.MainActivity.execRoot;
import static com.hijacker.MainActivity.getLastLine;
import static com.hijacker.MainActivity.iface;
import static com.hijacker.MainActivity.last_action;
import static com.hijacker.MainActivity.last_airodump;
import static com.hijacker.MainActivity.notification;
import static com.hijacker.MainActivity.prefix;
import static com.hijacker.MainActivity.refreshState;
import static com.hijacker.MainActivity.rootView;
import static com.hijacker.MainActivity.runInHandler;
import static com.hijacker.MainActivity.menu;
import static com.hijacker.MainActivity.stopWPA;
import static com.hijacker.ST.getSTByMac;
import static com.hijacker.Shell.getFreeShell;
import static com.hijacker.Shell.runOne;

class Airodump{
    private static int channel = 0;
    private static boolean forWPA = false, forWEP = false, running = false;
    // True while monitor mode is being enabled but airodump hasn't launched yet. During
    // this window running==true (UI shows "stop") but there is legitimately no airodump
    // process, so the watchdog must not treat it as a crash.
    private static volatile boolean starting = false;
    private static String mac = null;
    private static String capFile = null;
    final static Runnable cap_runnable = new Runnable(){
        @Override
        public void run(){
            /*
                This runnable will find the .cap file that is created by airodump and save it in capFile
                This gets called only if airodump is actually writing to a file
            */
            Shell shell = getFreeShell();
            try{
                Thread.sleep(1000);

                String file_prefix;
                if(forWPA) file_prefix = "/handshake";
                else if(forWEP) file_prefix = "/wep_ivs";
                else if(always_cap) file_prefix = "/cap";
                else throw new IllegalStateException("Airodump is not supposed to be writing to a file");

                shell.run("ls " + cap_path + file_prefix + "*.cap; echo ENDOFLS");
                capFile = getLastLine(shell.getShell_out(), "ENDOFLS");

                if(capFile.equals("ENDOFLS")){
                    capFile = null;
                }else{
                    Snackbar.make(rootView, "Saving handshake to " + capFile, Snackbar.LENGTH_LONG).show();
                }
            }catch(InterruptedException ignored){
            }finally{
                shell.done();
            }
        }
    };
    static Thread cap_thread = new Thread(cap_runnable);

    static void reset(){
        stop();
        channel = 0;
        forWPA = false;
        forWEP = false;
        mac = null;
        capFile = null;
    }
    static void setChannel(int ch){
        if(isRunning()){
            Log.e("HIJACKER/Airodump", "Can't change settings while airodump is running");
            throw new IllegalStateException("Airodump is still running");
        }
        channel = ch;
    }
    static void setMac(String new_mac){
        if(isRunning()){
            Log.e("HIJACKER/Airodump", "Can't change settings while airodump is running");
            throw new IllegalStateException("Airodump is still running");
        }
        mac = new_mac;
    }
    static void setForWPA(boolean bool){
        if(isRunning()){
            Log.e("HIJACKER/Airodump", "Can't change settings while airodump is running");
            throw new IllegalStateException("Airodump is still running");
        }
        if(forWEP){
            Log.e("HIJACKER/Airodump", "Can't set forWPA when forWEP is enabled");
            throw new IllegalStateException("Tried to set forWPA when forWEP is enabled");
        }
        forWPA = bool;
    }
    static void setForWEP(boolean bool){
        if(isRunning()){
            Log.e("HIJACKER/Airodump", "Can't change setting while airodump is running");
            throw new IllegalStateException("Airodump is still running");
        }
        if(forWPA){
            Log.e("HIJACKER/Airodump", "Can't set forWEP when forWPA is enabled");
            throw new IllegalStateException("Tried to set forWEP when forWPA is enabled");
        }
        forWEP = bool;
    }
    static void setAP(AP ap){
        if(isRunning()){
            Log.e("HIJACKER/Airodump", "Can't change setting while airodump is running");
            throw new IllegalStateException("Airodump is still running");
        }
        mac = ap.mac;
        channel = ap.ch;
    }
    static int getChannel(){ return channel; }
    static String getMac(){ return mac; }
    static String getCapFile(){
        while(cap_thread.isAlive()){}
        return capFile;
    }
    static boolean writingToFile(){ return getCapFile()!=null; }
    static void startClean(){
        reset();
        start();
    }
    static void startClean(AP ap){
        reset();
        setAP(ap);
        start();
    }
    static void startClean(int ch){
        reset();
        setChannel(ch);
        start();
    }
    static void start(){
        String cmd = prefix + " " + airodump_dir + " --update 1 --berlin 1 --band ";

        if(band==BAND_5 || band==BAND_BOTH) cmd += "a";
        if(band==BAND_2 || band==BAND_BOTH) cmd += "bg";
        cmd += " ";

        if(forWPA) cmd += "-w " + cap_path + "/handshake ";
        else if(forWEP) cmd += "--ivs -w " + cap_path + "/wep_ivs ";
        else if(always_cap) cmd += "-w " + cap_path + "/cap ";

        if(channel!=0) cmd += "--channel " + channel + " ";

        if(mac!=null) cmd += "--bssid " + mac + " ";

        cmd += iface;

        stop();

        final String final_cmd = cmd;
        starting = true;
        running = true;
        new Thread(new Runnable(){
            @Override
            public void run(){
                // Put the internal card in monitor mode BEFORE launching airodump.
                // On qcacld the first con_mode switch takes ~30-40s; runOne() blocks until
                // enable_monMode finishes. We are on a background thread here, so blocking is
                // safe (no ANR). enable_monMode is idempotent -> instant if already monitor.
                if(enable_on_airodump && enable_monMode!=null && !enable_monMode.trim().isEmpty()){
                    runInHandler(new Runnable(){
                        @Override
                        public void run(){
                            Snackbar.make(rootView, "Enabling monitor mode (~35s)…", Snackbar.LENGTH_LONG).show();
                        }
                    });
                    runOne(enable_monMode);
                }
                if(!Airodump.isRunning()){              //user stopped while monitor was coming up
                    starting = false;
                    return;
                }
                if(debug) Log.d("HIJACKER/Airodump.start", final_cmd);
                try{
                    int mode = channel==0 ? 0 : 1;
                    Process process = execRoot(final_cmd);
                    starting = false;                   //airodump launched -> watchdog may supervise it
                    startChannelHopper();               //qcacld can't hop channels itself; drive it with iw
                    last_action = System.currentTimeMillis();
                    last_airodump = final_cmd;
                    BoundedBufferedReader in = new BoundedBufferedReader(new InputStreamReader(process.getErrorStream()));
                    String buffer;
                    while(Airodump.isRunning() && (buffer = in.readLine(MAX_READLINE_SIZE))!=null){
                        main(buffer, mode);
                    }
                }catch(IOException e){ Log.e("HIJACKER/Exception", "Caught Exception in Airodump.start() read thread: " + e.toString()); }
            }
        }).start();
        if(forWPA || forWEP || always_cap){
            if(cap_thread.isAlive()){
                //Need to restart it so it will wait again for airodump to start
                cap_thread.interrupt();
            }
            cap_thread = new Thread(cap_runnable);
            cap_thread.start();
        }else capFile = null;
        runInHandler(new Runnable(){
            @Override
            public void run(){
                if(menu!=null){
                    menu.getItem(2).setIcon(R.drawable.stop_drawable);
                    menu.getItem(2).setTitle(R.string.stop);
                }
                refreshState();
                notification();
            }
        });
    }
    static void stop(){
        last_action = System.currentTimeMillis();
        starting = false;
        running = false;
        runInHandler(new Runnable(){
            @Override
            public void run(){
                if(menu!=null){
                    menu.getItem(2).setIcon(R.drawable.start_drawable);
                    menu.getItem(2).setTitle(R.string.start);
                }
            }
        });
        stopWPA();
        Shell shell = getFreeShell();
        if(delete_extra && (forWPA || forWEP || always_cap)){
            String file_prefix = getCapFile();
            if(file_prefix!=null){
                file_prefix = file_prefix.substring(0, file_prefix.lastIndexOf("."));
                shell.run(busybox + " rm -rf " + file_prefix + "*.csv");
                shell.run(busybox + " rm -rf " + file_prefix + "*.netxml");
            }
        }
        shell.run(busybox + " kill $(" + busybox + " pidof airodump-ng)");
        shell.done();
        AP.saveAll();
        ST.saveAll();

        runInHandler(new Runnable(){
            @Override
            public void run(){
                refreshState();
                notification();
            }
        });
    }
    static boolean isRunning(){
        return running;
    }
    static boolean isStarting(){
        return starting;
    }
    /*
        Qualcomm's qcacld-3.0 is a FullMAC driver: airodump-ng's own channel switching
        (wireless-ext ioctl) is silently ignored, so with --band bg the card never leaves
        its current channel and airodump captures nothing. We therefore drive the channel
        externally with the bundled iw (nl80211, which the driver DOES honour), cycling
        while airodump is alive. The loop self-terminates when airodump exits, and runs
        through a LOGIN su shell so it has CAP_NET_ADMIN to set the channel.
    */
    private static void startChannelHopper(){
        try{
            String dir = airodump_dir.substring(0, airodump_dir.lastIndexOf('/') + 1);
            String iwbin = dir + "iw";
            String chans;
            if(channel != 0)               chans = Integer.toString(channel);   //locked (e.g. handshake capture)
            else if(band == BAND_5)        chans = "36 40 44 48 149 153 157 161";
            else if(band == BAND_BOTH)     chans = "1 6 11 2 3 4 5 7 8 9 10 12 13 36 40 44 48 149 153 157 161";
            else                           chans = "1 6 11 2 3 4 5 7 8 9 10 12 13";
            String loop =
                "sleep 2; " +
                "while " + busybox + " pidof airodump-ng >/dev/null 2>&1; do " +
                    "for c in " + chans + "; do " +
                        iwbin + " dev " + iface + " set channel $c 2>/dev/null; " +
                        busybox + " pidof airodump-ng >/dev/null 2>&1 || break; " +
                        "sleep 1; " +
                    "done; " +
                "done";
            Process hp = Runtime.getRuntime().exec("su");
            PrintWriter w = new PrintWriter(hp.getOutputStream());
            w.print(loop + "\nexit\n");
            w.flush();
        }catch(IOException e){
            Log.e("HIJACKER/Airodump", "Channel hopper failed to start: " + e.toString());
        }
    }
    public static void addAP(String essid, String mac, String enc, String cipher, String auth,
                             int pwr, int beacons, int data, int ivs, int ch){
        AP temp = getAPByMac(mac);
        if(temp==null) new AP(essid, mac, enc, cipher, auth, pwr, beacons, data, ivs, ch);
        else temp.update(essid, enc, cipher, auth, pwr, beacons, data, ivs, ch);
    }
    public static void addST(String mac, String bssid, String probes, int pwr, int lost, int frames){
        ST temp = getSTByMac(mac);
        if (bssid.equals("na")) bssid=null;
        if (temp == null) new ST(mac, bssid, pwr, lost, frames, probes);
        else temp.update(bssid, pwr, lost, frames, probes);
    }

    public static native int main(String str, int off);
}
