package com.fastbrowser.pc;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.webkit.WebView;

public class ResizeWebView extends WebView {
    // The WebView always follows its parent window so no stale background/content
    // is left behind when Android resizes the browser in freeform/multi-window mode.
    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        requestLayout();
        invalidate();
    }
    private float startDist=0, startScale=1f;
    public ResizeWebView(Context c, AttributeSet a){super(c,a);}
    private float dist(MotionEvent e){ if(e.getPointerCount()<2)return 0; float x=e.getX(0)-e.getX(1), y=e.getY(0)-e.getY(1); return (float)Math.hypot(x,y); }
    @Override public boolean onTouchEvent(MotionEvent e){
        if(e.getPointerCount()>=2){
            if(e.getActionMasked()==MotionEvent.ACTION_POINTER_DOWN){ startDist=dist(e); startScale=getScale(); }
            else if((e.getActionMasked()==MotionEvent.ACTION_MOVE)&&startDist>0){ float d=dist(e); if(d>0){ float s=Math.max(.5f,Math.min(2.5f,startScale*d/startDist)); getSettings().setTextZoom(Math.round(s*100)); } }
            else if(e.getActionMasked()==MotionEvent.ACTION_POINTER_UP) startDist=0;
        }
        return super.onTouchEvent(e);
    }
}
