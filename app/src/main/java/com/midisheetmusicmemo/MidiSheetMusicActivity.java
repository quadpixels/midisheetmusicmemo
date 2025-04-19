/*
 * Copyright (c) 2011-2012 Madhav Vaidyanathan
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

import com.quadpixels.midisheetmusicmemo.R;
import com.quadpixels.midisheetmusicmemo.TestActivity;

import android.app.Activity;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.*;
import android.widget.*;
import android.widget.LinearLayout.LayoutParams;
import android.content.*;
import android.content.SharedPreferences.Editor;
import android.content.res.*;
import android.graphics.*;

/** @class MidiSheetMusicActivity
 * This is the launch activity for MidiSheetMusic.
 * It simply displays the splash screen, and a button to choose a song.
 */
public class MidiSheetMusicActivity extends Activity {
	public static boolean DEBUG = false;
	enum RunningMode {
		MIDISHEETMUSIC_MEMO, ORIGINAL_MSM, PLAYGROUND_1
	};
	public static RunningMode RUNNING_MODE = RunningMode.MIDISHEETMUSIC_MEMO;
	public static boolean USE_FAST_RENDERING_METHOD = true;
	public static boolean SHOW_README=true;
	public static boolean SHOW_NEXTLINE = true; // 2014-06-14
	public static boolean IS_TOMMY = false;
	public static SheetMusic sheet0;
	public static float density;
	public static MidiSheetMusicActivity activity;
	private CheckBox cb2, cb3;
	private Spinner spinner1;
    LinearLayout intro_container;
    SharedPreferences prefs_readme;
    Button btn_test;
    
    long last_click_millis;
    int num_click;
	
    @Override
    public void onCreate(Bundle savedInstanceState) {
    	Log.v("MidiSheetMusicActivity", "onCreate");
    	MidiSheetMusicActivity.activity = this;
        super.onCreate(savedInstanceState);
        MidiFile.loadStrings(this); // Beginning to support multiple languages

        DisplayMetrics dm = new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getMetrics(dm);
        density = dm.density;
        Log.v("MidiSheetMusicActivity", "Density="+density);
        
        loadImages();
        setContentView(R.layout.main);
        prefs_readme = getApplicationContext().getSharedPreferences("readme", Context.MODE_PRIVATE);
        intro_container = (LinearLayout) findViewById(R.id.intro_container);
        
        {
        	intro_container.setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View arg0) {
					if(num_click == -999) return;
					long millis = System.currentTimeMillis();
					if(millis > last_click_millis + 400) {
						num_click = 0;
					}
					num_click += 1;
					if(num_click >= 7) {
						Toast.makeText(getApplicationContext(), "Developer options enabled!", Toast.LENGTH_SHORT).show();
						num_click = -999;
						cb2.setVisibility(View.VISIBLE);
						cb3.setVisibility(View.VISIBLE);
						btn_test.setVisibility(View.VISIBLE);
					}
					last_click_millis = millis;
				}
			});
        }
        
        Button button = (Button) findViewById(R.id.choose_song);
        button.setOnClickListener(
                new View.OnClickListener() {
                    public void onClick(View v) {
                        chooseSong();
                    }
                }
        );
        
        spinner1 = (Spinner)findViewById(R.id.spinner1);
        spinner1.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
			@Override
			public void onItemSelected(AdapterView<?> arg0, View arg1,
					int pos, long arg3) {
				switch(pos) {
				case 0:
					RUNNING_MODE = RunningMode.MIDISHEETMUSIC_MEMO; break;
				case 1:
					RUNNING_MODE = RunningMode.ORIGINAL_MSM;        break;
				case 2:
					RUNNING_MODE = RunningMode.PLAYGROUND_1;        break;
				default:
					break;
				}
			}
			@Override
			public void onNothingSelected(AdapterView<?> arg0) {
				
			}
		});

        cb2 = (CheckBox)findViewById(R.id.checkBox2);
        cb2.setOnCheckedChangeListener(new CheckBox.OnCheckedChangeListener() {
			@Override
			public void onCheckedChanged(CompoundButton buttonView,
					boolean isChecked) {
				USE_FAST_RENDERING_METHOD = isChecked;
			}
        });
        
        cb3 = (CheckBox)findViewById(R.id.checkBox3);
        cb3.setOnCheckedChangeListener(new CheckBox.OnCheckedChangeListener() {
			@Override
			public void onCheckedChanged(CompoundButton buttonView,
					boolean isChecked) {
				DEBUG = isChecked;
			}
        });
        
        Button btn_hlp = (Button)findViewById(R.id.button_help);
        btn_hlp.setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View v) {
				showHelp();
			}
		});
        
        btn_test = (Button)findViewById(R.id.testbutton);
        btn_test.setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View v) {
				Intent intent = new Intent(getApplicationContext(), TestActivity.class);
				startActivity(intent);
			}
		});
    }

    public static void resetQuickReadme() {
		Editor edt = activity.prefs_readme.edit();
		edt.putBoolean("readme1_shown", false);
		edt.putBoolean("readme2_shown", false);
		edt.commit();
    }
    
    /** Start the ChooseSongActivity when the "Choose Song" button is clicked */
    private void chooseSong() {
        Intent intent = new Intent(this, ChooseSongActivity.class);
        startActivity(intent);
    }

    /** Load all the resource images */
    private void loadImages() {
        ClefSymbol.LoadImages(this);
        TimeSigSymbol.LoadImages(this);
        MidiPlayer.LoadImages(this);
    }

    private void showHelp() {
    	Intent intent = new Intent(this, HelpActivity.class);
    	startActivity(intent);
    }
}

