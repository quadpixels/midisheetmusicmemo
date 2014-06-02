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
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.webkit.*;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.Toast;

/** @class HelpActivity
 *  The HelpActivity displays the help.html file in the assets directory.
 */
public class HelpActivity extends Activity {
	LinearLayout linear_layout = null;
	WebView webview = null;
	Button btn_reset_readme = null;
	
	String msg_resetreadme, html_filename;
	SharedPreferences prefs_readme;
	
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Context ctx = getApplicationContext();
        prefs_readme       = ctx.getSharedPreferences("readme", Context.MODE_PRIVATE);
        
        boolean is_shown_1 = prefs_readme.getBoolean("readme1_shown", false);
        boolean is_shown_2 = prefs_readme.getBoolean("readme2_shown", false);
        if(is_shown_1 == false && is_shown_2 == false) {
        	btn_reset_readme.setVisibility(View.GONE);
        }
        
        html_filename = getResources().getString(R.string.readme_html_file);

        setContentView(R.layout.help);
        
        linear_layout = (LinearLayout) findViewById(R.id.help_wrapper);
        webview = (WebView) findViewById(R.id.help_webview);
        webview.getSettings().setJavaScriptEnabled(false);
        webview.loadUrl(html_filename);
        btn_reset_readme = (Button)findViewById(R.id.button_reset_readme);
        
        msg_resetreadme = getResources().getString(R.string.reset_readme);
        
        btn_reset_readme.setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View v) {
				MidiSheetMusicActivity.resetQuickReadme();
				Toast.makeText(getApplicationContext(), msg_resetreadme, 
						Toast.LENGTH_LONG).show();
				btn_reset_readme.setVisibility(View.GONE);
			}
		});
    }
}

