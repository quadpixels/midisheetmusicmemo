package com.midisheetmusicmemo;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

public class TommyIntroActivity extends Activity {
	TommyIntroView view;
	byte[] midi_file;
	String midi_title;
	Context ctx;
	public void onCreate(Bundle bundle) {
		super.onCreate(bundle);

		midi_file = this.getIntent().getByteArrayExtra(SheetMusicActivity.MidiDataID);
		midi_title= this.getIntent().getStringExtra(SheetMusicActivity.MidiTitleID);
		setTitle(midi_title);
		
		view = new TommyIntroView(this, bundle, midi_file, midi_title, this);
		setContentView(view);
	}
	
	public void startPlay() {
		Intent intent = new Intent();
        intent.setClass(TommyIntroActivity.this, TommyView2Activity.class);
        intent.putExtra(SheetMusicActivity.MidiDataID, midi_file);
        intent.putExtra(SheetMusicActivity.MidiTitleID, midi_title.toString());
        startActivity(intent);
        finish();
	}
	
	public void onDestroy() {
		if(view!=null) {
			view.free();
			view = null;
		}
		ctx = null;
		midi_file = null;
		midi_title = null;
		super.onDestroy();
		System.gc();
		ChooseSongActivity.logHeap();
		finish();
	}
	
}
