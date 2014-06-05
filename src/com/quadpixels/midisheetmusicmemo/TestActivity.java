package com.quadpixels.midisheetmusicmemo;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import com.midisheetmusicmemo.R;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.TextView;

public class TestActivity extends Activity {
	TextView tv1;
	
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
	
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.test1);
		tv1 = (TextView) findViewById(R.id.textView1);
		
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
	}
}
