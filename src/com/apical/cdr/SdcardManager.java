package com.apical.cdr;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.Environment;
import android.os.StatFs;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.text.TextUtils;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

import com.android.internal.os.storage.ExternalStorageFormatter;

public class SdcardManager {
    private final static String TAG = "SdcardManager";

    // keep at least 300MB free
    public static final long LOW_STORAGE_THRESHOLD_BYTES = 200*1024*1024;

    // dvr pathes
    public static final String DVR_SD_ROOT         = "/storage/extsd";
    public static final String DIRECTORY_DCIM      = DVR_SD_ROOT    + "/DCIM";
    public static final String DIRECTORY_PHOTO     = DIRECTORY_DCIM + "/DVR_Photo";
    public static final String DIRECTORY_VIDEO     = DIRECTORY_DCIM + "/DVR_Video";
    public static final String DIRECTORY_IMPTT     = DIRECTORY_DCIM + "/DVR_Important";
    public static final int    DVR_PHOTO_KEEP_NUM  = 100;
    public static final int    DVR_VIDEO_KEEP_NUM  = 10;
    public static final int    DVR_IMPTT_KEEP_NUM  = 10;

    // disk recycle thread
    private DiskRecycleThread mRecycleThread = null;
    private Context           mContext       = null;
    private MediaSaver        mMediaSaver    = null;
    private SDStateChangeListener mListerner = null;

    public SdcardManager(Context c, MediaSaver ms, SDStateChangeListener l) {
        mContext    = c;
        mMediaSaver = ms;
        mListerner  = l;
    }

    public void startDiskRecycle() {
        if (mRecycleThread == null) {
            mRecycleThread = new DiskRecycleThread(mMediaSaver);
            mRecycleThread.start();
        }
    }

    public void stopDiskRecycle() {
        if (mRecycleThread != null) {
            mRecycleThread.mStopCheck = true;
            mRecycleThread = null;
        }
    }

    public interface SDStateChangeListener {
        public void onSDStateChanged(boolean insert);
    };

    public void startSdStateMonitor() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_MEDIA_EJECT  );
        filter.addAction(Intent.ACTION_MEDIA_MOUNTED);
        filter.addDataScheme("file");
        mContext.registerReceiver(mMediaChangeReceiver, filter);
    }

    public void stopSdStateMonitor() {
        mContext.unregisterReceiver(mMediaChangeReceiver);
    }

    public static boolean isSdcardInsert() {
        /*
        BufferedReader br = null;
        String  str = null;
        boolean ret = false;

        try {
            br = new BufferedReader(new FileReader(new File("/proc/mounts")));
            while ((str = br.readLine()) != null) {
                if (str.contains(DVR_SD_ROOT)) {
                    ret = true;
                    break;
                }
            }
        } catch (IOException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        } finally {
            if(br != null) {
                try {
                    br.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }

        return ret;
        */
        String state = Environment.getStorageState(new File(DVR_SD_ROOT));
        if (!Environment.MEDIA_MOUNTED.equals(state)) {
            return false;
        }
        if (Environment.MEDIA_CHECKING.equals(state)) {
            return false;
        }
        return true;
    }

    public static void formatSDcard(Context context) {
        StorageManager storageManager = StorageManager.from(context);
        final StorageVolume[] storageVolumes = storageManager.getVolumeList();

        Intent intent = new Intent(ExternalStorageFormatter.FORMAT_ONLY);
        intent.setComponent(ExternalStorageFormatter.COMPONENT_NAME);
        intent.putExtra(StorageVolume.EXTRA_STORAGE_VOLUME, storageVolumes[0]);
        context.startService(intent);
    }

    public static int getBlockSize() {
        String state = Environment.getStorageState(new File(DVR_SD_ROOT));

        if (TextUtils.equals(state, Environment.MEDIA_MOUNTED)) {
            try {
                StatFs stat = new StatFs(DVR_SD_ROOT);
                if (state != null) {
                    return stat.getBlockSize();
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        return -1;
    }

    public static long getTotalSpace() {
        String state = Environment.getStorageState(new File(DVR_SD_ROOT));
        if (!Environment.MEDIA_MOUNTED.equals(state)) {
            return -1;
        }
        if (Environment.MEDIA_CHECKING.equals(state)) {
            return -1;
        }
        try {
            StatFs stat = new StatFs(DIRECTORY_DCIM);
            return stat.getBlockCount() * (long) stat.getBlockSize();
        } catch (Exception e) {
            Log.i(TAG, "Fail to access external storage", e);
        }
        return -1;
    }

    public static long getAvailableSpace() {
        String state = Environment.getStorageState(new File(DVR_SD_ROOT));
        if (!Environment.MEDIA_MOUNTED.equals(state)) {
            return -1;
        }
        if (Environment.MEDIA_CHECKING.equals(state)) {
            return -1;
        }
        try {
            StatFs stat = new StatFs(DIRECTORY_DCIM);
            return stat.getAvailableBlocks() * (long) stat.getBlockSize();
        } catch (Exception e) {
            Log.i(TAG, "Fail to access external storage", e);
        }
        return -1;
    }

    private BroadcastReceiver mMediaChangeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            final Uri uri = intent.getData();
            String   path = uri.getPath();

            if (action.equals(Intent.ACTION_MEDIA_EJECT)) {
                Log.i(TAG, "Intent.ACTION_MEDIA_EJECT path = " + path);
                if (path.equals(DVR_SD_ROOT)) {
                    Log.i(TAG, "sdcard removed");
                    if (mListerner != null) mListerner.onSDStateChanged(false);
                }
            } else if (action.equals(Intent.ACTION_MEDIA_MOUNTED)) {
                Log.i(TAG, "Intent.ACTION_MEDIA_MOUNTED = " + path);
                if (path.equals(DVR_SD_ROOT)) {
                    Log.i(TAG, "sdcard inserted");
                    if (mListerner != null) mListerner.onSDStateChanged(true);
                }
            }
        }
    };
};

class DiskRecycleThread extends Thread
{
    private final static String TAG = "DiskRecycleThread";

    public  boolean    mStopCheck  = false;
    private MediaSaver mMediaSaver = null;

    public DiskRecycleThread(MediaSaver ms) {
        mMediaSaver = ms;
    }

    @Override
    public void run() {
        File dir_dcim  = new File(SdcardManager.DIRECTORY_DCIM );
        File dir_video = new File(SdcardManager.DIRECTORY_VIDEO);
        if (!dir_dcim .exists()) dir_dcim .mkdirs();
        if (!dir_video.exists()) dir_video.mkdirs();

        while (!mStopCheck) {
            long avail   = SdcardManager.getAvailableSpace();
            long recycle = SdcardManager.LOW_STORAGE_THRESHOLD_BYTES - avail;
            Log.d(TAG, "===ck=== avail = " + avail + ", recycle = " + recycle);
            if (avail < 0) {
                continue;
            }

            recycle = recycleDirectorySpace(SdcardManager.DIRECTORY_VIDEO, recycle, SdcardManager.DVR_VIDEO_KEEP_NUM, 0);
            recycle = recycleDirectorySpace(SdcardManager.DIRECTORY_IMPTT, recycle, SdcardManager.DVR_IMPTT_KEEP_NUM, 0);
            recycle = recycleDirectorySpace(SdcardManager.DIRECTORY_PHOTO, recycle, SdcardManager.DVR_PHOTO_KEEP_NUM, 1);
            if (recycle > 0) {
                Log.e(TAG, "===ck=== recycle disk space failed: " + recycle);
            }

            try {
                sleep(30*1000);
            } catch (Exception e) {}
        }
    }

    private long recycleDirectorySpace(String path, long recycle, int keep, int type) {
        if (recycle > 0) {
            File dir = new File(path);
            if (!dir.exists()) {
                Log.e(TAG, "can't find " + path + " directory !");
            }
            else {
                File[] files = dir.listFiles();
                int    num   = files.length - keep;
                if (num > 0) {
                    sortFilesByLastModified(files);

                    for (File f : files) {
                        recycle -= f.length();
                        f.delete();

                        try {
                            if (mMediaSaver != null) {
                                switch (type) {
                                case 0: mMediaSaver.delVideo(f.getCanonicalPath()); break;
                                case 1: mMediaSaver.delImage(f.getCanonicalPath()); break;
                                }
                            }
                        } catch (Exception e) {
                            e.printStackTrace();
                        }

                        if (recycle <= 0 || --num <= 0) {
                            break;
                        }
                    }
                }
            }
        }
        return recycle;
    }

    public static void sortFilesByLastModified(File[] source) {
        class FileComparator implements Comparator <File> {
            @Override
            public int compare(File left, File right) {
                long a = left .lastModified();
                long b = right.lastModified();
                if      (a > b) return  1;
                else if (a < b) return -1;
                else            return  0;
            }
        }

        if (source != null) {
            Arrays.sort(source, new FileComparator());
        }
    }
};

















