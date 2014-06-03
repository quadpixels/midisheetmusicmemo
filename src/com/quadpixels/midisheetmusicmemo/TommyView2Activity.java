package com.quadpixels.midisheetmusicmemo;

import java.util.zip.CRC32;

import com.quadpixels.midisheetmusicmemo.TommyPopupView.HelpInfos;
import com.saltinemidisheetmusic.MidiFile;
import com.saltinemidisheetmusic.MidiOptions;
import com.saltinemidisheetmusic.R;
import com.saltinemidisheetmusic.SheetMusicActivity;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.Dialog;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.AsyncTask;
import android.os.Bundle;
import android.util.Log;
import android.widget.RelativeLayout;
import android.widget.Toast;

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
		Toast.makeText(this, "Heap="+activityManager.getMemoryClass(), Toast.LENGTH_SHORT).show();
	}
	
	protected void onDestroy() {
		super.onDestroy();
		if(isFinishing()) {
			tommyview.free();
			tommyview = null;
			midi_file = null;
		}
	}
	
	protected void onStart() { // Resuming from alt+tab
		super.onStart();
		if(tommyview != null)
			tommyview.resume();
	}
	
	protected void onSaveInstanceState(Bundle bundle) {
		Log.v("TommyView2Activity", "onSaveInstanceState called");
		tommyview.saveState(bundle);
	}
}
