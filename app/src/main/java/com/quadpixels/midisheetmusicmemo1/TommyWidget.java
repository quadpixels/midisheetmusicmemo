package com.quadpixels.midisheetmusicmemo1;

import com.midisheetmusicmemo.MidiSheetMusicActivity;
import com.quadpixels.midisheetmusicmemo1.R;

import android.app.PendingIntent;
import android.app.Service;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;
import android.util.DisplayMetrics;
import android.util.Log;
import android.widget.RemoteViews;

public class TommyWidget extends AppWidgetProvider {	
	static float density = 1.0f;
	
    @Override
    public void onUpdate(Context context, AppWidgetManager appWidgetManager, int[] appWidgetIds) {
    	
    	// 2014-07-07
    	// To update means to call the update stuff from anywhere, whether by service or
    	//   in the activity.
    	/*
        ComponentName cn = context.startService(new Intent(context, UpdateService.class));
        DisplayMetrics dm = context.getResources().getDisplayMetrics();
        density = dm.density;
        Log.v("TommyWidget onUpdate", "cn=" + cn);
        */
    }
    
    @Override
    public void onEnabled(Context context) {
    	ComponentName cn = context.startService(new Intent(context, UpdateService.class));
        DisplayMetrics dm = context.getResources().getDisplayMetrics();
        density = dm.density;
        Log.v("TommyWidget onEnabled", "cn=" + cn);
    }
    
    public static class UpdateService extends Service {
        @Override
        public void onStart(Intent intent, int startId) { // Hook the button to open MidiSheetMusic, no specific files

    		Intent msmm_intent = new Intent(this, MidiSheetMusicActivity.class);
            PendingIntent pending_intent = PendingIntent.getActivity(this,
                    0 /* no requestCode */, msmm_intent, PendingIntent.FLAG_CANCEL_CURRENT | PendingIntent.FLAG_MUTABLE);

    		RemoteViews updateViews = new RemoteViews(getPackageName(), R.layout.widget_sheetmusic_uninited);
            
            updateViews.setOnClickPendingIntent(R.id.widget_image_view_uninited, pending_intent);
            updateViews.setOnClickPendingIntent(R.id.widget_uninited, pending_intent);
            updateViews.setOnClickPendingIntent(R.id.widget_text_view_uninited, pending_intent);
            
            ComponentName thisWidget = new ComponentName(this, TommyWidget.class);
            AppWidgetManager manager = AppWidgetManager.getInstance(this);
            manager.updateAppWidget(thisWidget, updateViews);
            
            stopSelf();
        }
    	
		@Override
		public IBinder onBind(Intent intent) {
			return null;
		}
    }
}
