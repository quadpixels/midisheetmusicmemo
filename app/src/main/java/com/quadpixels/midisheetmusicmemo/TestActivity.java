package com.quadpixels.midisheetmusicmemo;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import com.midisheetmusicmemo.ClefSymbol;
import com.midisheetmusicmemo.MidiPlayer;
import com.quadpixels.midisheetmusicmemo.R;
import com.midisheetmusicmemo.TimeSigSymbol;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.widget.ImageView;
import android.widget.TextView;

import com.midisheetmusicmemo.*;

public class TestActivity extends Activity {
	TextView tv1;
	ImageView image1;
	Context ctx;


	private String getAllKeys(SharedPreferences who, String title) {
		StringBuilder sb = new StringBuilder();
		Map<String, ?> entries = who.getAll();
		Set<String> keys = entries.keySet();
		sb.append(String.format("[%s] %d entries\n", title, keys.size()));
		for(String k : keys) {
			sb.append(k);
			sb.append("\n");
		}
		return sb.toString();
	}
	
	void Test2() {
		// Minimal code required to load a Sheet Music ...
		ClefSymbol.LoadImages(ctx);
		TimeSigSymbol.LoadImages(ctx);
		MidiPlayer.LoadImages(ctx);
		
		Uri uri =  Uri.parse("file:///android_asset/Bach__Invention_No._13.mid");
		String display_name = uri.getLastPathSegment();
		FileUri fileuri = new FileUri(uri, display_name);
		
		byte[] midi_data = fileuri.getData(this);
		Log.v("Test2", String.format("midi_data = %d bytes", midi_data.length));
		MidiFile midifile = new MidiFile(midi_data, display_name);
		
		SheetMusic sheet = new SheetMusic(ctx);
		sheet.is_tommy_linebreak = true;
		sheet.is_first_measure_out_of_boundingbox = false;
		sheet.getHolder().setFixedSize(20, 20);
		MidiOptions options = new MidiOptions(midifile);
		sheet.init(midifile, options);
		sheet.ComputeMeasureHashesNoRender(800);
		
		int num_measures = sheet.getNumMeasures();
		int num_staffs   = sheet.getNumStaffs();
		Random random = new Random();
		int midx = random.nextInt(num_measures), sidx = random.nextInt(num_staffs);
		Bitmap bmp = sheet.RenderTile(midx, sidx, 1.0f, 1.0f);
		BitmapDrawable bd = new BitmapDrawable(bmp);
		image1.setImageDrawable(bd);
	}
	
	void Test1() {
		setContentView(R.layout.test1);
		tv1 = (TextView) findViewById(R.id.textView1);
		image1 = (ImageView) findViewById(R.id.imageView1);
		
		Context ctx = getApplicationContext();
		SharedPreferences prefs_highscores = ctx.getSharedPreferences("highscores", Context.MODE_PRIVATE);
		SharedPreferences prefs_lastplayed = ctx.getSharedPreferences("lastplayed", Context.MODE_PRIVATE);
		SharedPreferences prefs_playcount  = ctx.getSharedPreferences("playcounts", Context.MODE_PRIVATE);
		SharedPreferences prefs_quizcount  = ctx.getSharedPreferences("quizcounts", Context.MODE_PRIVATE);
		SharedPreferences prefs_quizstats  = ctx.getSharedPreferences("quizstats",  Context.MODE_PRIVATE);
		SharedPreferences prefs_finegrained= ctx.getSharedPreferences("finegrained", Context.MODE_PRIVATE);
		SharedPreferences prefs_colorscheme= ctx.getSharedPreferences("colorscheme", Context.MODE_PRIVATE);
		
		StringBuilder sb = new StringBuilder();
		sb.append(getAllKeys(prefs_highscores, "highscores"));
		sb.append(getAllKeys(prefs_lastplayed, "lastplayed"));
		sb.append(getAllKeys(prefs_playcount,  "playcount"));
		sb.append(getAllKeys(prefs_quizcount,  "quizcount"));
		sb.append(getAllKeys(prefs_quizstats,  "quizstats"));
		sb.append(getAllKeys(prefs_finegrained,"finegrained"));
		sb.append(getAllKeys(prefs_colorscheme,"colorscheme"));
		
		tv1.setText(sb.toString());
		
		TommyConfig.init(getApplicationContext());
		image1.setImageBitmap(TommyConfig.bmp_palette);
	}
	
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		ctx = getApplicationContext();
		Test1();
		Test2();
	}
}
