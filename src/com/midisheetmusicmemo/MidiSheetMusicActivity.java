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

import com.midisheetmusicmemo.R;

import android.app.Activity;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.*;
import android.widget.*;
import android.content.*;
import android.content.res.*;
import android.graphics.*;

/** @class MidiSheetMusicActivity
 * This is the launch activity for MidiSheetMusic.
 * It simply displays the splash screen, and a button to choose a song.
 */
public class MidiSheetMusicActivity extends Activity {
	static boolean DEBUG = false, USE_ORIGINAL_MSM = false;
	static boolean IS_TOMMY = false;
	static SheetMusic sheet0;
	static float density;
	LinearLayout linear_layout;
	public static MidiSheetMusicActivity activity;
	private CheckBox cb;
	
    @Override
    public void onCreate(Bundle savedInstanceState) {

        super.onCreate(savedInstanceState);

        DisplayMetrics dm = new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getMetrics(dm);
        density = dm.density;
        Log.v("MidiSheetMusicActivity", "Density="+density);
        
        loadImages();
        setContentView(R.layout.main);
        Button button = (Button) findViewById(R.id.choose_song);
        button.setOnClickListener(
                new View.OnClickListener() {
                    public void onClick(View v) {
                        chooseSong();
                    }
                }
        );
        
        cb = (CheckBox)findViewById(R.id.checkBox1);
        cb.setOnCheckedChangeListener(new CheckBox.OnCheckedChangeListener() {
			@Override
			public void onCheckedChanged(CompoundButton buttonView,
					boolean isChecked) {
				USE_ORIGINAL_MSM = isChecked;
			}
        });

        Button btn_hlp = (Button)findViewById(R.id.button_help);
        btn_hlp.setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View v) {
				showHelp();
			}
		});
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

    /** Always use landscape mode for this activity. */
    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
    }

    private void showHelp() {
    	Intent intent = new Intent(this, HelpActivity.class);
    	startActivity(intent);
    }
}

