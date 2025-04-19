/*
 * Copyright (c) 2007-2012 Madhav Vaidyanathan
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

import java.util.*;

import com.midisheetmusicmemo.SheetMusic.Vec2;

import android.graphics.*;
import android.util.Log;


/* @class Staff
 * The Staff is used to draw a single Staff (a row of measures) in the 
 * SheetMusic Control. A Staff needs to draw
 * - The Clef
 * - The key signature
 * - The horizontal lines
 * - A list of MusicSymbols
 * - The left and right vertical lines
 *
 * The height of the Staff is determined by the number of pixels each
 * MusicSymbol extends above and below the staff.
 *
 * The vertical lines (left and right sides) of the staff are joined
 * with the staffs above and below it, with one exception.  
 * The last track is not joined with the first track.
 */

public class Staff {
    private ArrayList<MusicSymbol> symbols;  /** The music symbols in this staff */
    private ArrayList<LyricSymbol> lyrics;   /** The lyrics to display (can be null) */
    private int ytop;                   /** The y pixel of the top of the staff */
    private ClefSymbol clefsym;         /** The left-side Clef symbol */
    private AccidSymbol[] keys;         /** The key signature symbols */
    private boolean showMeasures;       /** If true, show the measure numbers */
    private int keysigWidth;            /** The width of the clef and key signature */
    private int width;                  /** The width of the staff in pixels */
    private int height;                 /** The height of the staff in pixels */
    private int tracknum;               /** The track this staff represents */
    private int totaltracks;            /** The total number of tracks */
    private int starttime;              /** The time (in pulses) of first symbol */
    private int endtime;                /** The time (in pulses) of last symbol */
    private int measureLength;          /** The time (in pulses) of a measure */

    // TOMMY
    private ArrayList<Integer> barSymbolIdxes; // Idx of bar symbols
    public HashMap<Integer, Float> symbolX = new HashMap<Integer, Float>();        // X coordinates, updated at each DrawMeasure.
    public int getNumMeasures() {
    	return barSymbolIdxes.size();
    }
    public int getMeasureIdxFromPulse(int pulse) {
    	if(pulse < getSymbols().get(0).getStartTime()) return -1;
    	for(int i=0; i<barSymbolIdxes.size(); i++) {
    		int lower_bound = getSymbols().get(barSymbolIdxes.get(i)).getStartTime();
    		int upper_bound = lower_bound;
    		if(i < barSymbolIdxes.size()-1) {
    			upper_bound = getSymbols().get(barSymbolIdxes.get(i+1)).getStartTime();
    		} else upper_bound = getSymbols().get(getSymbols().size()-1).getStartTime();
    		if(lower_bound <= pulse && upper_bound > pulse) return i;
    	}
    	return barSymbolIdxes.size() - 1;
    }
    public int getMeasureBeginPulse(int midx) {
    	return getSymbols().get(barSymbolIdxes.get(midx)).getStartTime();
    }
    
    /** Create a new staff with the given list of music symbols,
     * and the given key signature.  The clef is determined by
     * the clef of the first chord symbol. The track number is used
     * to determine whether to join this left/right vertical sides
     * with the staffs above and below. The MidiOptions are used
     * to check whether to display measure numbers or not.
     */
    public Staff(ArrayList<MusicSymbol> symbols, KeySignature key, 
                 MidiOptions options, int tracknum, int totaltracks)  {

        keysigWidth = SheetMusic.KeySignatureWidth(key);
        this.tracknum = tracknum;
        this.totaltracks = totaltracks;
        showMeasures = (options.showMeasures && tracknum == 0);
        if (options.time != null) {
            measureLength = options.time.getMeasure();
        }
        else {
            measureLength = options.defaultTime.getMeasure();
        }
        Clef clef = FindClef(symbols);

        clefsym = new ClefSymbol(clef, 0, false);
        keys = key.GetSymbols(clef);
        this.setSymbols(symbols);
        CalculateWidth(options.scrollVert);
        CalculateHeight();
        CalculateStartEndTime();
        FullJustify();
        computeMeasureBarSymbolIdxes();
    }

    /** Return the width of the staff */
    public int getWidth() { return width; }

    /** Return the height of the staff */
    public int getHeight() { return height; }

    /** Return the track number of this staff (starting from 0 */
    public int getTrack() { return tracknum; }

    /** Return the starting time of the staff, the start time of
     *  the first symbol.  This is used during playback, to 
     *  automatically scroll the music while playing.
     */
    public int getStartTime() { return starttime; }

    /** Return the ending time of the staff, the endtime of
     *  the last symbol.  This is used during playback, to 
     *  automatically scroll the music while playing.
     */
    public int getEndTime() { return endtime; }
    public void setEndTime(int value) { endtime = value; }

    /** Find the initial clef to use for this staff.  Use the clef of
     * the first ChordSymbol.
     */
    private Clef FindClef(ArrayList<MusicSymbol> list) {
        for (MusicSymbol m : list) {
            if (m instanceof ChordSymbol) {
                ChordSymbol c = (ChordSymbol) m;
                return c.getClef();
            }
        }
        return Clef.Treble;
    }

    /** Calculate the height of this staff.  Each MusicSymbol contains the
     * number of pixels it needs above and below the staff.  Get the maximum
     * values above and below the staff.
     */
    public void CalculateHeight() {
        int above = 0;
        int below = 0;

        for (MusicSymbol s : getSymbols()) {
            above = Math.max(above, s.getAboveStaff());
            below = Math.max(below, s.getBelowStaff());
        }
        above = Math.max(above, clefsym.getAboveStaff());
        below = Math.max(below, clefsym.getBelowStaff());
        if (showMeasures) {
            above = Math.max(above, SheetMusic.NoteHeight * 3);
        }
        ytop = above + SheetMusic.NoteHeight;
        height = SheetMusic.NoteHeight*5 + ytop + below;
        if (lyrics != null) {
            height += SheetMusic.NoteHeight * 3/2;
        }

        /* Add some extra vertical space between the last track
         * and first track.
         */
        if (tracknum == totaltracks-1)
            height += SheetMusic.NoteHeight * 3;
    }

    /** Calculate the width of this staff */
    private void CalculateWidth(boolean scrollVert) {
        if (scrollVert) {
            width = SheetMusic.PageWidth;
            return;
        }
        width = keysigWidth;
        for (MusicSymbol s : getSymbols()) {
            width += s.getWidth();
        }
    }

    /** Calculate the start and end time of this staff. */
    private void CalculateStartEndTime() {
        starttime = endtime = 0;
        if (getSymbols().size() == 0) {
            return;
        }
        starttime = getSymbols().get(0).getStartTime();
        for (MusicSymbol m : getSymbols()) {
            if (endtime < m.getStartTime()) {
                endtime = m.getStartTime();
            }
            if (m instanceof ChordSymbol) {
                ChordSymbol c = (ChordSymbol) m;
                if (endtime < c.getEndTime()) {
                    endtime = c.getEndTime();
                }
            }
        }
    }


    /** Full-Justify the symbols, so that they expand to fill the whole staff. */
    private void FullJustify() {
        if (width != SheetMusic.PageWidth)
            return;

        int totalwidth = keysigWidth;
        int totalsymbols = 0;
        int i = 0;

        while (i < getSymbols().size()) {
            int start = getSymbols().get(i).getStartTime();
            totalsymbols++;
            totalwidth += getSymbols().get(i).getWidth();
            i++;
            while (i < getSymbols().size() && getSymbols().get(i).getStartTime() == start) {
                totalwidth += getSymbols().get(i).getWidth();
                i++;
            }
        }

        int extrawidth = (SheetMusic.PageWidth - totalwidth - 1) / totalsymbols;
        if (extrawidth > SheetMusic.NoteHeight*2) {
            extrawidth = SheetMusic.NoteHeight*2;
        }
        i = 0;
        while (i < getSymbols().size()) {
            int start = getSymbols().get(i).getStartTime();
            int newwidth = getSymbols().get(i).getWidth() + extrawidth;
            getSymbols().get(i).setWidth(newwidth);
            i++;
            while (i < getSymbols().size() && getSymbols().get(i).getStartTime() == start) {
                i++;
            }
        }
    }


    /** Add the lyric symbols that occur within this staff.
     *  Set the x-position of the lyric symbol.
     */
    public void AddLyrics(ArrayList<LyricSymbol> tracklyrics) {
        if (tracklyrics == null || tracklyrics.size() == 0) {
            return;
        }
        lyrics = new ArrayList<LyricSymbol>();
        int xpos = 0;
        int symbolindex = 0;
        for (LyricSymbol lyric : tracklyrics) {
            if (lyric.getStartTime() < starttime) {
                continue;
            }
            if (lyric.getStartTime() > endtime) {
                break;
            }
            /* Get the x-position of this lyric */
            while (symbolindex < getSymbols().size() &&
                   getSymbols().get(symbolindex).getStartTime() < lyric.getStartTime()) {
                xpos += getSymbols().get(symbolindex).getWidth();
                symbolindex++;
            }
            lyric.setX(xpos);
            if (symbolindex < getSymbols().size() &&
                (getSymbols().get(symbolindex) instanceof BarSymbol)) {
                lyric.setX(lyric.getX() + SheetMusic.NoteWidth);
            }
            lyrics.add(lyric);
        }
        if (lyrics.size() == 0) {
            lyrics = null;
        }
    }

    /** Draw the lyrics */
    private void DrawLyrics(Canvas canvas, Paint paint) {
        /* Skip the left side Clef symbol and key signature */
        int xpos = keysigWidth;
        int ypos = height - SheetMusic.NoteHeight * 3/2;

        for (LyricSymbol lyric : lyrics) {
            canvas.drawText(lyric.getText(),
                            xpos + lyric.getX(),
                            ypos,
                            paint);
        }
    }


    /** Draw the measure numbers for each measure */
    private void DrawMeasureNumbers(Canvas canvas, Paint paint) {
        /* Skip the left side Clef symbol and key signature */
        int xpos = keysigWidth;
        int ypos = ytop - SheetMusic.NoteHeight * 3;

        for (MusicSymbol s : getSymbols()) {
            if (s instanceof BarSymbol) {
                int measure = 1 + s.getStartTime() / measureLength;
                canvas.drawText("" + measure,
                                xpos + SheetMusic.NoteWidth/2,
                                ypos,
                                paint);
            }
            xpos += s.getWidth();
        }
    }

    private void computeMeasureBarSymbolIdxes() {
    	if(barSymbolIdxes == null) barSymbolIdxes = new ArrayList<Integer>();
    	barSymbolIdxes.clear();
    	int idx = 0;
    	for(MusicSymbol s : getSymbols()) {
    		if(s instanceof BarSymbol) {
    			barSymbolIdxes.add(idx);
    		}
    		idx++;
    	}
    }

    /** Draw the five horizontal lines of the staff */
    private void DrawHorizLines(Canvas canvas, Paint paint) {
        int line = 1;
        int y = ytop - SheetMusic.LineWidth;
        paint.setStrokeWidth(1);
        for (line = 1; line <= 5; line++) {
            canvas.drawLine(SheetMusic.LeftMargin, y, width-1, y, paint);
            y += SheetMusic.LineWidth + SheetMusic.LineSpace;
        }
    }
    

    private void DrawHorizontalLinesNoLeftMargin(Canvas canvas, Paint paint) {
    	int line = 1;
        int y = ytop - SheetMusic.LineWidth;
        paint.setStrokeWidth(1);
        for (line = 1; line <= 5; line++) {
            canvas.drawLine(0, y, width-1, y, paint);
            y += SheetMusic.LineWidth + SheetMusic.LineSpace;
        }
    }

    /** Draw the vertical lines at the far left and far right sides. */
    private void DrawEndLines(Canvas canvas, Paint paint) {
        paint.setStrokeWidth(1);

        /* Draw the vertical lines from 0 to the height of this staff,
         * including the space above and below the staff, with two exceptions:
         * - If this is the first track, don't start above the staff.
         *   Start exactly at the top of the staff (ytop - LineWidth)
         * - If this is the last track, don't end below the staff.
         *   End exactly at the bottom of the staff.
         */
        int ystart, yend;
        if (tracknum == 0)
            ystart = ytop - SheetMusic.LineWidth;
        else
            ystart = 0;

        if (tracknum == (totaltracks-1))
            yend = ytop + 4 * SheetMusic.NoteHeight;
        else
            yend = height;

        canvas.drawLine(SheetMusic.LeftMargin, ystart, SheetMusic.LeftMargin, yend, paint);

        canvas.drawLine(width-1, ystart, width-1, yend, paint);

    }

    /** Draw this staff. Only draw the symbols inside the clip area */
    public void Draw(Canvas canvas, Rect clip, Paint paint) {
        paint.setColor(Color.BLACK);
        int xpos = SheetMusic.LeftMargin + 5;

        /* Draw the left side Clef symbol */
        canvas.translate(xpos, 0);
        clefsym.Draw(canvas, paint, ytop);
        canvas.translate(-xpos, 0);
        xpos += clefsym.getWidth();

        /* Draw the key signature */
        for (AccidSymbol a : keys) {
            canvas.translate(xpos, 0);
            a.Draw(canvas, paint, ytop);
            canvas.translate(-xpos, 0);
            xpos += a.getWidth();
        }
       
        /* Draw the actual notes, rests, bars.  Draw the symbols one 
         * after another, using the symbol width to determine the
         * x position of the next symbol.
         *
         * For fast performance, only draw symbols that are in the clip area.
         */
        for (MusicSymbol s : getSymbols()) {
            if ((xpos <= clip.left + clip.width() + 50) && (xpos + s.getWidth() + 50 >= clip.left)) {
                canvas.translate(xpos, 0);
                s.Draw(canvas, paint, ytop);
                canvas.translate(-xpos, 0);
            }
            xpos += s.getWidth();
        }
        paint.setColor(Color.BLACK);
        DrawHorizLines(canvas, paint);
        DrawEndLines(canvas, paint);

        if (showMeasures) {
            DrawMeasureNumbers(canvas, paint);
        }
        if (lyrics != null) {
            DrawLyrics(canvas, paint);
        }

    }

    public ArrayList<Integer> getNotesInMeasure(int measureIdx) {
    	if(measureIdx < 0 || measureIdx >= barSymbolIdxes.size()) return null;
    	ArrayList<Integer> ret = new ArrayList<Integer>();
    	int idx_start = barSymbolIdxes.get(measureIdx), idx = idx_start;
    	boolean is_done = false;
    	while(true) {
    		if(idx >= getSymbols().size()) break;
    		if(is_done) break;
    		MusicSymbol sym = getSymbols().get(idx);
    		{
    			if(idx != idx_start && sym instanceof BarSymbol) { is_done = true;}
    			if(sym instanceof BarSymbol) {
    				if(idx == idx_start || measureIdx == barSymbolIdxes.size()-1) {
    					// Do not show BarSymbol except when this is the final measure.
    	    			ret.add(idx);
    				}
    			} else {
    				ret.add(idx);
    			}
    			idx++;
    		}
    	}
    	return ret;
    }
    
    public void PadMeasure(int measure_idx, float total_x_padding) {
    	int idx_start = barSymbolIdxes.get(measure_idx);
    	int idx_end = (measure_idx < getNumMeasures()-1) ? barSymbolIdxes.get(measure_idx+1) :
    		getSymbols().size();
    	int total_pad = (int)(total_x_padding);
    	int num_syms_pad = 0;
    	
    	int i = idx_start;
    	// How many symbols need be padded?
    	while(i<idx_end) {
    		MusicSymbol sym = getSymbols().get(i);
    		int start = sym.getStartTime();
    		i++;
    		num_syms_pad ++;
    		while(i<idx_end && getSymbols().get(i).getStartTime() == start) i++;
    	}
    	
    	int each_pad = total_pad / num_syms_pad;
    	i = idx_start;
    	while(i<idx_end) {
    		MusicSymbol sym = getSymbols().get(i);
    		int start = sym.getStartTime();
    		int this_pad = (i==idx_end-1) ? total_pad : each_pad;
    		total_pad -= this_pad;
    		sym.setWidth(sym.getWidth() + this_pad);
    		i++;
    		while(i < idx_end && getSymbols().get(i).getStartTime() == start) {
    			i++; 
    		}
    	}
    }
    
    // December 2014
    public ArrayList<MusicSymbol> getSymbolsInMeasure(int measureIdx) {
    	ArrayList<MusicSymbol> ret = new ArrayList<MusicSymbol>();
    	ArrayList<Integer> idxes   = getNotesInMeasure(measureIdx);
    	for(Integer idx : idxes) {
    		ret.add(getSymbols().get(idx));
    	}
    	return ret;
    }
    
    // if canvas is null then just measure width
    // ( if canvas is null then dry run )
    public Vec2 DrawMeasure(Canvas canvas, int measureIdx, Paint paint, boolean is_clef,
    		float total_x_padding) {
    	Vec2 ret = new Vec2();
    	paint.setColor(Color.BLACK);
    	float xmin = 0, xmax = 0;
    	
    	// Draw something myself.
    	final int pad_clef = 10;
    	float each_x_padding = 0.0f;
    	{
    		float xpos = 0;
    		xmin = xpos;
    		
    		if(is_clef) {
    			xpos = xpos + pad_clef;
    			if(canvas != null) {
	    			canvas.translate(xpos, 0);
	    			clefsym.Draw(canvas, paint, ytop);
	    			canvas.translate(-xpos, 0);
    			}
    			xpos += clefsym.getWidth();

    	        for (AccidSymbol a : keys) {
    	        	if(canvas != null) {
	    	            canvas.translate(xpos, 0);
	    	            a.Draw(canvas, paint, ytop);
	    	            canvas.translate(-xpos, 0);
    	        	}
    	            xpos += a.getWidth();
    	        }
    		}
			
			ArrayList<Integer> symidxs = getNotesInMeasure(measureIdx);
			
			if(symidxs.size() > 0) {
				each_x_padding = total_x_padding / (1.0f*symidxs.size());
			} else {
				xpos += total_x_padding;
			}
			for(Integer idx : symidxs) {
				MusicSymbol sym = getSymbols().get(idx);
				if(canvas != null) {
					canvas.translate(xpos, 0);
					sym.Draw(canvas, paint, ytop);
					canvas.translate(-xpos, 0);
				}
				symbolX.put(idx, xpos);
				xpos += sym.getWidth();
				xpos = xpos + each_x_padding;
			}
    		
    		xmax = xpos;
    		

    		if(canvas!=null) 
    			DrawHorizontalLinesNoLeftMargin(canvas, paint);
    	}
    	
    	ret.x = (int)xmin; ret.y = (int)xmax;
    	return ret;
    }

    // TOMMY: The Index of the notes in the given time.
    // Tommy (On 2014-05-03)
    // The BarSymbol may be causing some glitches?
    public int shade_measure_idx = 0;
    public float shade_measure_x_begin   = 0.0f, shade_measure_x_end = 0.0f;
    public int shade_note_idx = 0;
    public void updateShadedNoteX(int curr_measure, int currentPulseTime, int prevPulseTime) {
    	if(curr_measure < 0) {
    		return;
    	}
    	synchronized(barSymbolIdxes) {
	    	int idx = -999;
	    	for(int i=curr_measure; i<barSymbolIdxes.size(); i++) {
	    		idx = i;
	    		BarSymbol bs = (BarSymbol)(getSymbols().get(barSymbolIdxes.get(idx)));
	    		if(idx != barSymbolIdxes.size() - 1) {
	    			BarSymbol bs_next = (BarSymbol)(getSymbols().get(barSymbolIdxes.get(idx+1)));
		    		if(bs.getStartTime() <= currentPulseTime && 
		    				bs_next.getStartTime() >= currentPulseTime) {
		    			shade_measure_idx = idx;
		    			break;
		    		}
	    		} else {
	    			if(bs.getStartTime() <= currentPulseTime) {
	    				shade_measure_idx = idx;
	    				break;
	    			}
	    		}
	    	}
	    	
	    	// Search in that measure.
	    	int sidx_begin = barSymbolIdxes.get(idx), sidx_end;
	    	if(idx == barSymbolIdxes.size() - 1) { sidx_end = getSymbols().size(); }
	    	else { sidx_end = barSymbolIdxes.get(idx + 1); }
	    	for(int idx1=sidx_begin; idx1<sidx_end; idx1++) {
	    		MusicSymbol ms = getSymbols().get(idx1);
	    		MusicSymbol ms_next = null;
	    		if(idx1 < symbols.size() - 1) {
	    			ms_next = symbols.get(idx1 + 1);
	    		}
	    		
				int st = ms.getStartTime();

				boolean ok = false;
	    		if(ms_next != null) {
		    		if(st <= currentPulseTime && ms_next.getStartTime() >= currentPulseTime) {
		    			ok = true;
			    	}
	    		} else {
	    			if(st <= currentPulseTime) {
	    				ok = true;
	    			}
	    		}
	    		
	    		if(ok) {
	    			if(symbolX.containsKey(idx1)) {
		    			shade_note_idx = idx1;
	    				shade_measure_x_begin = symbolX.get(idx1);
	    				shade_measure_x_end   = shade_measure_x_begin + ms.getWidth();
	    				break;
		    		}
	    		}
	    	}
    	}
    }

    /** Shade all the chords played in the given time.
     *  Un-shade any chords shaded in the previous pulse time.
     *  Store the x coordinate location where the shade was drawn.
     */
    public int ShadeNotes(Canvas canvas, Paint paint, int shade,
                           int currentPulseTime, int prevPulseTime, int x_shade) {

        /* If there's nothing to unshade, or shade, return */
        if ((starttime > prevPulseTime || endtime < prevPulseTime) &&
            (starttime > currentPulseTime || endtime < currentPulseTime)) {
            return x_shade;
        }

        /* Skip the left side Clef symbol and key signature */
        int xpos = keysigWidth;

        MusicSymbol curr = null;
        ChordSymbol prevChord = null;
        int prev_xpos = 0;

        /* Loop through the symbols. 
         * Unshade symbols where start <= prevPulseTime < end
         * Shade symbols where start <= currentPulseTime < end
         */ 
        for (int i = 0; i < getSymbols().size(); i++) {
            curr = getSymbols().get(i);
            if (curr instanceof BarSymbol) {
                xpos += curr.getWidth();
                continue;
            }

            int start = curr.getStartTime();
            int end = 0;
            if (i+2 < getSymbols().size() && getSymbols().get(i+1) instanceof BarSymbol) {
                end = getSymbols().get(i+2).getStartTime();
            }
            else if (i+1 < getSymbols().size()) {
                end = getSymbols().get(i+1).getStartTime();
            }
            else {
                end = endtime;
            }


            /* If we've past the previous and current times, we're done. */
            if ((start > prevPulseTime) && (start > currentPulseTime)) {
                if (x_shade == 0) {
                    x_shade = xpos;
                }
                return x_shade;
            }
            /* If shaded notes are the same, we're done */
            if ((start <= currentPulseTime) && (currentPulseTime < end) &&
                (start <= prevPulseTime) && (prevPulseTime < end)) {

                x_shade = xpos;
                return x_shade;
            }

            boolean redrawLines = false;

            /* If symbol is in the previous time, draw a white background */
            if ((start <= prevPulseTime) && (prevPulseTime < end)) {
                canvas.translate(xpos-2, -2);
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(Color.WHITE);
                canvas.drawRect(0, 0, curr.getWidth()+4, this.getHeight()+4, paint);
                paint.setStyle(Paint.Style.STROKE);
                paint.setColor(Color.BLACK);
                canvas.translate(-(xpos-2), 2);
                canvas.translate(xpos, 0);
                curr.Draw(canvas, paint, ytop);
                canvas.translate(-xpos, 0);

                redrawLines = true;
            }

            /* If symbol is in the current time, draw a shaded background */
            if ((start <= currentPulseTime) && (currentPulseTime < end)) {
                x_shade = xpos;
                canvas.translate(xpos, 0);
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(shade);
                canvas.drawRect(0, 0, curr.getWidth(), this.getHeight(), paint);
                paint.setStyle(Paint.Style.STROKE);
                paint.setColor(Color.BLACK);
                curr.Draw(canvas, paint, ytop);
                canvas.translate(-xpos, 0);
                redrawLines = true;
            }

            /* If either a gray or white background was drawn, we need to redraw
             * the horizontal staff lines, and redraw the stem of the previous chord.
             */
            if (redrawLines) {
                int line = 1;
                int y = ytop - SheetMusic.LineWidth;
                paint.setStyle(Paint.Style.STROKE);
                paint.setColor(Color.BLACK);
                paint.setStrokeWidth(1);
                canvas.translate(xpos-2, 0);
                for (line = 1; line <= 5; line++) {
                    canvas.drawLine(0, y, curr.getWidth()+4, y, paint);
                    y += SheetMusic.LineWidth + SheetMusic.LineSpace;
                }
                canvas.translate(-(xpos-2), 0);

                if (prevChord != null) {
                    canvas.translate(prev_xpos, 0);
                    prevChord.Draw(canvas, paint, ytop);
                    canvas.translate(-prev_xpos, 0);
                }
                if (showMeasures) {
                    DrawMeasureNumbers(canvas, paint);
                }
                if (lyrics != null) {
                    DrawLyrics(canvas, paint);
                }
            }
            if (curr instanceof ChordSymbol) {
                ChordSymbol chord = (ChordSymbol) curr;
                if (chord.getStem() != null && !chord.getStem().getReceiver()) {
                    prevChord = (ChordSymbol) curr;
                    prev_xpos = xpos;
                }
            }
            xpos += curr.getWidth();
        }
        return x_shade;
    }

    /** Return the pulse time corresponding to the given point.
     *  Find the notes/symbols corresponding to the x position,
     *  and return the startTime (pulseTime) of the symbol.
     */
    public int PulseTimeForPoint(Point point) {

        int xpos = keysigWidth;
        int pulseTime = starttime;
        for (MusicSymbol sym : getSymbols()) {
            pulseTime = sym.getStartTime();
            if (point.x <= xpos + sym.getWidth()) {
                return pulseTime;
            }
            xpos += sym.getWidth();
        }
        return pulseTime;
    }


    @Override
    public String toString() {
        String result = "Staff clef=" + clefsym.toString() + "\n";
        result += "  Keys:\n";
        for (AccidSymbol a : keys) {
            result += "    " + a.toString() + "\n";
        }
        result += "  Symbols:\n";
        for (MusicSymbol s : keys) {
            result += "    " + s.toString() + "\n";
        }
        for (MusicSymbol m : getSymbols()) {
            result += "    " + m.toString() + "\n";
        }
        result += "End Staff\n";
        return result;
    }


    public void free() {
    	synchronized(barSymbolIdxes) {
	    	if(getSymbols() != null) getSymbols().clear();
	    	if(barSymbolIdxes != null) barSymbolIdxes.clear();
	    	if(lyrics != null) lyrics.clear();
    	}
    }
	public ArrayList<MusicSymbol> getSymbols() {
		return symbols;
	}
	public void setSymbols(ArrayList<MusicSymbol> symbols) {
		this.symbols = symbols;
	}
    
}


