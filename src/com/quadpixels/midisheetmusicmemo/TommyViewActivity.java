package com.quadpixels.midisheetmusicmemo;

import com.saltinemidisheetmusic.SheetMusicActivity;

import android.app.Activity;
import android.content.Context;
import android.os.Bundle;

public class TommyViewActivity extends Activity {
	TommyView tommyview = null;
	byte[] midi_data; String midi_title;
	
	protected void onCreate (Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		Context ctx = getApplicationContext();
		if(tommyview == null) {
			midi_data = this.getIntent().getByteArrayExtra(SheetMusicActivity.MidiDataID);
			midi_title= this.getIntent().getStringExtra(SheetMusicActivity.MidiTitleID);
			tommyview = new TommyView(ctx, midi_data, midi_title);
		}
		setContentView(tommyview);
	}
	
	protected void onDestroy() {
		super.onDestroy();
		tommyview.free();
		tommyview = null;
		midi_data = null;
	}
}
