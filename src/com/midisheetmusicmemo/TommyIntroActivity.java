package com.midisheetmusicmemo;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.widget.LinearLayout;

public class TommyIntroActivity extends Activity {
	TommyIntroView view;
	byte[] midi_data;
	String midi_title;
	Context ctx;
	MidiPlayer player;
	MidiOptions options;
	
	public void onCreate(Bundle bundle) {
		super.onCreate(bundle);

		midi_data = this.getIntent().getByteArrayExtra(SheetMusicActivity.MidiDataID);
		midi_title= this.getIntent().getStringExtra(SheetMusicActivity.MidiTitleID);
		setTitle(midi_title);
		

		view = new TommyIntroView(this, bundle, midi_data, midi_title, this);
		
		MidiFile midi_file = new MidiFile(midi_data, midi_title);
		options = new MidiOptions(midi_file);
		player = new MidiPlayer(this);
		
		setContentView(view);
		player.SetMidiFile(midi_file, options, view.sheet);
		/*
		player.MoveToMeasureBegin(0);
		player.Play();
		is_playing = true;*/
	}
	
	public boolean isMidiPlayerPlaying() {
		if(player.playstate == player.playing) return true;
		else return false;
	}
	
	public void stopMidiPlayer() {
		player.Stop();
	}
	
	public void startMidiPlayer() {
		player.Play();
	}
	
	
	public void startPlay() {
		Intent intent = new Intent();
        intent.setClass(TommyIntroActivity.this, TommyView2Activity.class);
        intent.putExtra(SheetMusicActivity.MidiDataID, midi_data);
        intent.putExtra(SheetMusicActivity.MidiTitleID, midi_title.toString());
        startActivity(intent);
        finish();
	}
	
	public void onDestroy() {
		player.Stop();
		if(view!=null) {
			view.free();
			view = null;
		}
		ctx = null;
		midi_data = null;
		midi_title = null;
		super.onDestroy();
		System.gc();
		ChooseSongActivity.logHeap();
		finish();
	}
	
}
