package com.quadpixels.midisheetmusicmemo1;

import java.util.zip.CRC32;

import android.app.ActionBar;
import android.app.Activity;
import android.app.ActivityManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.widget.RelativeLayout;
import android.widget.Toast;

import com.midisheetmusicmemo.MidiFile;
import com.midisheetmusicmemo.MidiOptions;
import com.midisheetmusicmemo.MidiSheetMusicActivity;
import com.midisheetmusicmemo.SheetMusicActivity;
import com.quadpixels.midisheetmusicmemo1.TommyPopupView.HelpInfos;

public class TommyView2Activity extends Activity {
	TommyView2 tommyview = null;
	RelativeLayout relative_layout = null;
	Context ctx;
	byte[] midi_data = null;
	String midi_title = null, midi_uri_string = null;
	MidiFile midi_file = null;
	long midiCRC;
	MidiOptions options;
	SharedPreferences prefs_readme;
	static TommyPopupView popupview;
	Bundle bundle;
	
	protected void onCreate (Bundle savedInstanceState) {
		Log.v("TommyView2Activity", "onCreate called");
		super.onCreate(savedInstanceState);
		ctx = getApplicationContext();
		midi_data = this.getIntent().getByteArrayExtra(SheetMusicActivity.MidiDataID);
		midi_title= this.getIntent().getStringExtra(SheetMusicActivity.MidiTitleID);
		midi_uri_string = this.getIntent().getStringExtra(TommyConfig.FILE_URI_ID);
		prefs_readme    = ctx.getSharedPreferences("readme", Context.MODE_PRIVATE);
		boolean readme_shown = prefs_readme.getBoolean("readme2_shown", false);
		
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
		
		relative_layout = new RelativeLayout(ctx);
		tommyview = new TommyView2(this, savedInstanceState, midi_data, midi_title, midi_uri_string, options);
		relative_layout.addView(tommyview);

		if(readme_shown == false) {
			popupview = new TommyPopupView(ctx, relative_layout, tommyview, HelpInfos.INFO_RECYCLE_ZONE);
			popupview.which_help = HelpInfos.INFO_RECYCLE_ZONE;
			relative_layout.addView(popupview);
		}
		setContentView(relative_layout);
		
        ActivityManager activityManager = (ActivityManager) getApplicationContext().getSystemService(ACTIVITY_SERVICE);
        if(MidiSheetMusicActivity.DEBUG) {
        	Toast.makeText(this, "Heap="+activityManager.getMemoryClass(), Toast.LENGTH_SHORT).show();
        }
        
    	// Hide Status Bar
 		// Refer to this page:
 		// https://developer.android.com/training/system-ui/status.html
 		if (Build.VERSION.SDK_INT < 16) {
 			getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
 					WindowManager.LayoutParams.FLAG_FULLSCREEN);
 		} else {
 			View decorView = getWindow().getDecorView();
 			// Hide the status bar.
 			int uiOptions = View.SYSTEM_UI_FLAG_FULLSCREEN;
 			decorView.setSystemUiVisibility(uiOptions);
 			// Remember that you should never show the action bar if the
 			// status bar is hidden, so hide that too if necessary.
 			ActionBar actionBar = getActionBar();
			if (actionBar != null)
 				actionBar.hide();
 		}
	}
	
	protected void onDestroy() {
		super.onDestroy();
		if(isFinishing()) {
			tommyview.free();
			tommyview = null;
			midi_file = null;
		}
	}
	
	protected void onStop() {
		super.onStop();
		Log.v("TommyView2Activity", "onStop called");
		if(tommyview != null) {
			tommyview.pause();
		}
	}
	
	protected void onRestart() {
		super.onRestart();
		Log.v("TommyView2Activity", "onStart called");
		if(tommyview != null) {
			if(this.bundle != null) {
				tommyview.loadState(this.bundle);
				this.bundle.clear();
				this.bundle = null;
			}
			tommyview.resume();
			tommyview.populateSelectionTiles();
		}
	}
	
	protected void onSaveInstanceState(Bundle bundle) {
		Log.v("TommyView2Activity", "onSaveInstanceState called");
		tommyview.saveState(bundle);
		this.bundle = bundle;
	}
}
