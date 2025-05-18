/*
 * Copyright (c) 2011-2013 Madhav Vaidyanathan
 *
 *  This program is free software; you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License version 2.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 */

package com.midisheetmusicmemo;

import java.text.DecimalFormat;

import android.app.*;
import android.os.*;
import android.widget.*;
import android.util.Log;
import android.content.*;

import org.json.*;

import com.quadpixels.midisheetmusicmemo1.TommyConfig;
import com.quadpixels.midisheetmusicmemo1.TommyIntroActivity;
import com.quadpixels.midisheetmusicmemo1.TommyPlaygroundActivity;
import com.quadpixels.midisheetmusicmemo1.R;

import android.graphics.*;
import android.graphics.drawable.*;

/** @class ChooseSongActivity
 * The ChooseSongActivity class is a tabbed view for choosing a song to play.
 * There are 3 tabs:
 * - All    (AllSongsActivity)    : Display a list of all songs
 * - Recent (RecentSongsActivity) : Display of list of recently opened songs
 * - Browse (FileBrowserActivity) : Let the user browse the filesystem for songs
 */
public class ChooseSongActivity extends TabActivity {

    static ChooseSongActivity globalActivity;

    @Override
    public void onCreate(Bundle state) {
        globalActivity = this;
        TommyConfig.init(this);
        super.onCreate(state);

       
        Bitmap allFilesIcon = BitmapFactory.decodeResource(this.getResources(), R.drawable.allfilesicon);
        Bitmap recentFilesIcon = BitmapFactory.decodeResource(this.getResources(), R.drawable.recentfilesicon);
        Bitmap browseFilesIcon = BitmapFactory.decodeResource(this.getResources(), R.drawable.browsefilesicon);

        final TabHost tabHost = getTabHost();

        tabHost.addTab(tabHost.newTabSpec("All")
                .setIndicator("All", new BitmapDrawable(allFilesIcon))
                .setContent(new Intent(this, AllSongsActivity.class)));

        tabHost.addTab(tabHost.newTabSpec("Recent")
                .setIndicator("Recent", new BitmapDrawable(recentFilesIcon))
                .setContent(new Intent(this, RecentSongsActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)));

        tabHost.addTab(tabHost.newTabSpec("Browse")
                .setIndicator("Browse", new BitmapDrawable(browseFilesIcon))
                .setContent(new Intent(this, FileBrowserActivity.class)));

    }

    public static void openFile(FileUri file) {
        globalActivity.doOpenFile(file);
    }

    public void doOpenFile(FileUri file) {
        byte[] data = file.getData(this);
        if (data == null || data.length <= 6 || !MidiFile.hasMidiHeader(data)) {
            ChooseSongActivity.showErrorDialog("Error: Unable to open song: " + file.toString(), this);
            return;
        }
        updateRecentFile(file);
        Intent intent = null;
        switch(MidiSheetMusicActivity.RUNNING_MODE) {
	        case ORIGINAL_MSM:
	        	intent = new Intent(Intent.ACTION_VIEW, file.getUri(), this, SheetMusicActivity.class);
	        	break;
	        case MIDISHEETMUSIC_MEMO:        
	        	intent = new Intent(this, TommyIntroActivity.class); break;
			case PLAYGROUND_1:
				intent = new Intent(this, TommyPlaygroundActivity.class); break;
			default:
				break;
        }
        if(intent != null) {
	        Log.v("doOpenFile", "URI: " + file.getUri().toString());
	        intent.putExtra(SheetMusicActivity.MidiTitleID, file.toString());
	        intent.putExtra(SheetMusicActivity.MidiDataID,  file.getData(this));
	        intent.putExtra(TommyConfig.FILE_URI_ID,        file.toStringFull());
	        startActivity(intent);
        } else {
        	
        }
    }


    /** Show an error dialog with the given message */
    public static void showErrorDialog(String message, Activity activity) {
        AlertDialog.Builder builder = new AlertDialog.Builder(activity);
        builder.setMessage(message);
        builder.setCancelable(false);
        builder.setPositiveButton("OK", new DialogInterface.OnClickListener() {
           public void onClick(DialogInterface dialog, int id) {
           }
        });
        AlertDialog alert = builder.create();
        alert.show();
    }

    /** Save the given FileUri into the "recentFiles" preferences.
     *  Save a maximum of 10 recent files.
     */
    public void updateRecentFile(FileUri recentfile) {
        try {
            SharedPreferences settings = getSharedPreferences("midisheetmusic.recentFiles", 0);
            SharedPreferences.Editor editor = settings.edit();
            JSONArray prevRecentFiles = null;
            String recentFilesString = settings.getString("recentFiles", null);
            if (recentFilesString != null) {
                prevRecentFiles = new JSONArray(recentFilesString);
            }
            else {
                prevRecentFiles = new JSONArray();
            }
            JSONArray recentFiles = new JSONArray();
            JSONObject recentFileJson = recentfile.toJson();
            recentFiles.put(recentFileJson);
            for (int i = 0; i < prevRecentFiles.length(); i++) {
                if (i >= 10) {
                    break; // only store 10 most recent files
                }
                JSONObject file = prevRecentFiles.getJSONObject(i); 
                if (!FileUri.equalJson(recentFileJson, file)) {
                    recentFiles.put(file);
                }
            }
            editor.putString("recentFiles", recentFiles.toString() );
            editor.commit();
        }
        catch (Exception e) {
        }
    }

	public static void logHeap() {
	    Double allocated = new Double(Debug.getNativeHeapAllocatedSize())/new Double((1048576));
	    Double available = new Double(Debug.getNativeHeapSize())/1048576.0f;
	    Double free = new Double(Debug.getNativeHeapFreeSize())/1048576.0f;
	    DecimalFormat df = new DecimalFormat();
	    df.setMaximumFractionDigits(2);
	    df.setMinimumFractionDigits(2);

	    Log.d("blah", "debug. =================================");
	    Log.d("blah", "debug.heap native: allocated " + df.format(allocated) + "MB of " + df.format(available) + "MB (" + df.format(free) + "MB free)");
	    Log.d("blah", "debug.memory: allocated: " + df.format(new Double(Runtime.getRuntime().totalMemory()/1048576)) + "MB of " + df.format(new Double(Runtime.getRuntime().maxMemory()/1048576))+ "MB (" + df.format(new Double(Runtime.getRuntime().freeMemory()/1048576)) +"MB free)");
	    System.gc();
	    System.gc();
	}
}

