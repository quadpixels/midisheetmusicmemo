package com.midisheetmusicmemo;

import com.midisheetmusicmemo.R;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.Dialog;
import android.app.ProgressDialog;
import android.content.Context;
import android.os.AsyncTask;
import android.os.Bundle;
import android.util.Log;
import android.widget.Toast;

public class TommyView2Activity extends Activity {
	TommyView2 tommyview = null;
	Context ctx;
	byte[] midi_file = null;
	String midi_title = null;
	
	protected void onCreate (Bundle savedInstanceState) {
		Log.v("TommyView2Activity", "onCreate called");
		super.onCreate(savedInstanceState);
		ctx = getApplicationContext();
		midi_file = this.getIntent().getByteArrayExtra(SheetMusicActivity.MidiDataID);
		midi_title= this.getIntent().getStringExtra(SheetMusicActivity.MidiTitleID);
		setTitle(midi_title);
		
		if(tommyview == null) {
			tommyview = new TommyView2(this, savedInstanceState, midi_file, midi_title);
		} else {
			tommyview.loadState(savedInstanceState);
		}

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
