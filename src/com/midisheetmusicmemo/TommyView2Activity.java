package com.midisheetmusicmemo;

import java.util.zip.CRC32;

import com.midisheetmusicmemo.R;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.Dialog;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.AsyncTask;
import android.os.Bundle;
import android.util.Log;
import android.widget.Toast;

public class TommyView2Activity extends Activity {
	TommyView2 tommyview = null;
	Context ctx;
	byte[] midi_data = null;
	String midi_title = null, midi_uri_string = null;
	MidiFile midi_file = null;
	long midiCRC;
	MidiOptions options;
	
	protected void onCreate (Bundle savedInstanceState) {
		Log.v("TommyView2Activity", "onCreate called");
		super.onCreate(savedInstanceState);
		ctx = getApplicationContext();
		midi_data = this.getIntent().getByteArrayExtra(SheetMusicActivity.MidiDataID);
		midi_title= this.getIntent().getStringExtra(SheetMusicActivity.MidiTitleID);
		midi_uri_string = this.getIntent().getStringExtra(TommyConfig.FILE_URI_ID);
		
		setTitle(midi_title);
		
		midi_file = new MidiFile(midi_data, midi_title);
		
		// Settings used by original MidiSheetMusic.
		options = new MidiOptions(midi_file);
		{
			CRC32 crc = new CRC32();
			crc.update(midi_data);
			SharedPreferences settings = getSharedPreferences(TommyConfig.TOMMY_PREF_NAME, MODE_PRIVATE);
			midiCRC = crc.getValue();
			String json = settings.getString("" + midiCRC, null);
	        MidiOptions savedOptions = MidiOptions.fromJson(json);
	        if (savedOptions != null) {
	            options.merge(savedOptions);
	        } else {
	        	Log.v("TommyView2Activity", "Saved prefs is NULL");
	        }
		}
		
		tommyview = new TommyView2(this, savedInstanceState, midi_data, midi_title, midi_uri_string, options);

        ActivityManager activityManager = (ActivityManager) getApplicationContext().getSystemService(ACTIVITY_SERVICE);
		Toast.makeText(this, "Heap="+activityManager.getMemoryClass(), Toast.LENGTH_SHORT).show();
		setContentView(tommyview);
	}
	
	protected void onDestroy() {
		super.onDestroy();
		if(isFinishing()) {
			tommyview.free();
			tommyview = null;
			midi_file = null;
		}
	}
	
	protected void onSaveInstanceState(Bundle bundle) {
		Log.v("TommyView2Activity", "onSaveInstanceState called");
		tommyview.saveState(bundle);
	}
}
