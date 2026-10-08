package com.fastbrowser.pc;

import android.app.*;
import android.os.*;
import android.content.*;
import android.content.ContentValues;
import android.webkit.WebResourceRequest;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.view.*;
import android.view.animation.AlphaAnimation;
import android.webkit.*;
import android.provider.Settings;
import android.provider.MediaStore;
import android.os.Environment;
import java.io.OutputStream;
import java.io.ByteArrayInputStream;
import java.text.SimpleDateFormat;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.RecognitionListener;
import android.Manifest;
import android.content.pm.PackageManager;
import androidx.webkit.WebSettingsCompat;
import androidx.webkit.WebViewFeature;
import android.widget.*;
import java.util.*;

public class MainActivity extends Activity {
    private FrameLayout webContainer;
    private LinearLayout tabsBar;
    private EditText url;
    private TextView searchEngine;
    private TextView linksButton;
    private SharedPreferences prefs;
    private final ArrayList<Tab> tabs = new ArrayList<>();
    private final LinkedHashMap<String, VideoLinkDetector.VideoCandidate> videoLinks = new LinkedHashMap<>();
    private final LinkedHashMap<String, DownloadItem> downloadQueue = new LinkedHashMap<>();
    private int currentTab = 0;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable mediaScan;
    private boolean hasNewLinks = false;
    private Dialog mouseDialog;
    private View mouseCursor;
    private float mouseX = 0.5f, mouseY = 0.5f;
    private boolean mouseDragMode = false;
    private boolean mouseZoomMode = false;
    private boolean mouseDragging = false;
    private Dialog radioDialog;
    private WebView radioResolverWeb;
    private View fullScreenView;
    private WebChromeClient.CustomViewCallback fullScreenCallback;
    private TextView fullScreenExitButton;
    private static final int REQ_WEB_PERMISSIONS = 7412;
    private static final int REQ_IMAGE_SEARCH = 7413;
    private static final int REQ_VOICE_SEARCH = 7414;
    private static final int REQ_DNS_VPN = 7415;
    private PermissionRequest pendingWebPermissionRequest;
    private ValueCallback<Uri[]> fileChooserCallback;
    private View highTrafficDot;
    private Runnable highTrafficHide;
    private Runnable highTrafficMonitor;
    private boolean highTrafficWarningShown = false;
    private boolean desktopMode = false;
    private boolean nightMode = false;
    private boolean noImagesMode = false;
    private boolean adBlockEnabled = false;
    private boolean incognitoMode = false;
    private SpeechRecognizer speechRecognizer;

    private static class Engine {
        String name, icon, searchUrl;
        Engine(String n, String i, String u){name=n;icon=i;searchUrl=u;}
    }
    private static class Tab {
        WebView web;
        String title = "New Tab";
        String lastUrl = "https://www.google.com";
        Tab(WebView w){web=w;}
    }

    private static class DownloadItem {
        final String url, title, mime, userAgent, referer;
        DownloadItem(String url, String title, String mime, String userAgent, String referer){
            this.url=url; this.title=title; this.mime=mime; this.userAgent=userAgent; this.referer=referer;
        }
    }

    private final ArrayList<Engine> engines = new ArrayList<>();
    private Engine activeEngine;
    private VideoLinkDetector detector;

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_main);
        prefs = getSharedPreferences("state", 0);
        webContainer = findViewById(R.id.webContainer);
        tabsBar = findViewById(R.id.tabs);
        url = findViewById(R.id.url);
        searchEngine = findViewById(R.id.searchEngine);
        setupEngines();
        activeEngine = findEngine(prefs.getString("engine", "Google"));
        if(activeEngine == null) activeEngine = engines.get(0);
        updateEngineButton();
        detector = new VideoLinkDetector(this::onVideoCandidate);
        desktopMode = prefs.getBoolean("desktop_mode", false);
        nightMode = prefs.getBoolean("night_mode", false);
        noImagesMode = prefs.getBoolean("no_images", false);
        adBlockEnabled = prefs.getBoolean("ad_block", false);
        loadDownloadQueue();
        setupToolbar();
        addBottom();
        if (!restoreTabs(b)) {
            addTab(prefs.getString("last", "fastbrowser:home"));
        }
    }

    private void setupEngines(){
        engines.add(new Engine("Google", "G", "https://www.google.com/search?q=%s"));
        engines.add(new Engine("Bing", "B", "https://www.bing.com/search?q=%s"));
        engines.add(new Engine("DuckDuckGo", "D", "https://duckduckgo.com/?q=%s"));
        engines.add(new Engine("Brave Search", "Br", "https://search.brave.com/search?q=%s"));
        engines.add(new Engine("Startpage", "S", "https://www.startpage.com/sp/search?query=%s"));
        engines.add(new Engine("Gerdoo", "گ", "https://gerdoo.me/search?q=%s"));
        engines.add(new Engine("Zarebin", "ذ", "https://zarebin.ir/search?q=%s"));
        engines.add(new Engine("Parsijoo", "پ", "https://parsijoo.ir/search?q=%s"));
        engines.add(new Engine("Yooz", "ی", "https://yooz.ir/search/?q=%s"));
        engines.add(new Engine("Jasjoo", "ج", "https://jasjoo.com/search?q=%s"));
        engines.add(new Engine("Baidu", "百", "https://www.baidu.com/s?wd=%s"));
        engines.add(new Engine("Sogou", "搜", "https://www.sogou.com/web?query=%s"));
        engines.add(new Engine("360 Search", "360", "https://www.so.com/s?q=%s"));
        engines.add(new Engine("Shenma", "神", "https://m.sm.cn/s?q=%s"));
        engines.add(new Engine("Naver", "N", "https://search.naver.com/search.naver?query=%s"));
        engines.add(new Engine("Daum", "D", "https://search.daum.net/search?q=%s"));
        engines.add(new Engine("Yahoo! Japan", "Y!", "https://search.yahoo.co.jp/search?p=%s"));
        engines.add(new Engine("Goo Japan", "goo", "https://search.goo.ne.jp/web.jsp?MT=%s"));
        loadCustomEngines();
    }

    private void loadCustomEngines(){
        String raw=prefs==null?"":prefs.getString("custom_engines","");
        if(raw.isEmpty()) return;
        try{org.json.JSONArray a=new org.json.JSONArray(raw);for(int i=0;i<a.length();i++){org.json.JSONObject o=a.optJSONObject(i);if(o!=null){String n=o.optString("name"),ic=o.optString("icon","C"),u=o.optString("url");if(!n.isEmpty()&&u.contains("%s"))engines.add(new Engine(n,ic,u));}}}catch(Exception ignored){}
    }
    private void saveCustomEngines(){
        org.json.JSONArray a=new org.json.JSONArray();
        for(int i=18;i<engines.size();i++){Engine e=engines.get(i);org.json.JSONObject o=new org.json.JSONObject();try{o.put("name",e.name);o.put("icon",e.icon);o.put("url",e.searchUrl);a.put(o);}catch(Exception ignored){}}
        prefs.edit().putString("custom_engines",a.toString()).apply();
    }

    private Engine findEngine(String name){ for(Engine e:engines) if(e.name.equals(name)) return e; return null; }
    private void updateEngineButton(){ searchEngine.setText(activeEngine.icon); searchEngine.setContentDescription(activeEngine.name); }

    private void setupToolbar(){
        findViewById(R.id.back).setOnClickListener(v->{WebView w=getWeb(); if(w.canGoBack())w.goBack();});
        findViewById(R.id.forward).setOnClickListener(v->{WebView w=getWeb(); if(w.canGoForward())w.goForward();});
        findViewById(R.id.reload).setOnClickListener(v->getWeb().reload());
        findViewById(R.id.go).setOnClickListener(v->load(url.getText().toString()));
        url.setOnEditorActionListener((v,a,e)->{load(url.getText().toString());return true;});
        searchEngine.setOnClickListener(v->showEngineMenu());
        findViewById(R.id.imageSearch).setOnClickListener(v->startImageSearch());
        findViewById(R.id.voiceSearch).setOnClickListener(v->startVoiceSearch());
    }

    private void startVoiceSearch(){
        // Voice recognition stays in the browser Activity; no external speech UI is launched.
        if(!SpeechRecognizer.isRecognitionAvailable(this)){ showMessage4("Voice search is not available"); return; }
        if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_WEB_PERMISSIONS + 10);
            showMessage4("Microphone permission is required");
            return;
        }
        if(speechRecognizer!=null){ try{speechRecognizer.destroy();}catch(Exception ignored){} }
        speechRecognizer=SpeechRecognizer.createSpeechRecognizer(this);
        speechRecognizer.setRecognitionListener(new RecognitionListener(){
            @Override public void onReadyForSpeech(Bundle params){ showMessage4("Listening…"); }
            @Override public void onBeginningOfSpeech(){}
            @Override public void onRmsChanged(float rmsdB){}
            @Override public void onBufferReceived(byte[] buffer){}
            @Override public void onEndOfSpeech(){}
            @Override public void onPartialResults(Bundle partialResults){}
            @Override public void onEvent(int eventType, Bundle params){}
            @Override public void onError(int error){ showMessage4("Voice search was not recognized"); stopVoiceRecognizer(); }
            @Override public void onResults(Bundle results){
                ArrayList<String> r=results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if(r!=null&&!r.isEmpty()){ url.setText(r.get(0)); load(r.get(0)); }
                stopVoiceRecognizer();
            }
        });
        Intent i=new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS,false);
        speechRecognizer.startListening(i);
    }

    private void stopVoiceRecognizer(){
        if(speechRecognizer!=null){ try{speechRecognizer.stopListening();}catch(Exception ignored){} try{speechRecognizer.destroy();}catch(Exception ignored){} speechRecognizer=null; }
    }

    private void startImageSearch(){
        try {
            Intent i=new Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
            i.setType("image/*");
            startActivityForResult(i, REQ_IMAGE_SEARCH);
        } catch(Exception e){ showMessage4("Image picker is not available"); }
    }

    @Override protected void onActivityResult(int requestCode,int resultCode,Intent data){
        super.onActivityResult(requestCode,resultCode,data);
        if(requestCode==REQ_DNS_VPN){
            if(resultCode==RESULT_OK && pendingDnsPrimary!=null) startDnsService(pendingDnsPrimary,pendingDnsSecondary);
            else showMessage4("DNS permission was not granted");
            pendingDnsPrimary=null; pendingDnsSecondary=null;
            return;
        }
        if(requestCode==REQ_VOICE_SEARCH){
            if(resultCode==RESULT_OK && data!=null){
                ArrayList<String> r=data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
                if(r!=null && !r.isEmpty()){ url.setText(r.get(0)); load(r.get(0)); }
            }
            return;
        }
        if(requestCode==REQ_IMAGE_SEARCH){
            if(resultCode==RESULT_OK && data!=null && data.getData()!=null){
                Uri image=data.getData();
                // Image selection may use Android's picker, but the search itself stays in this WebView.
                String u=image.toString().replace("\\","\\\\").replace("'","\\'");
                String html="<html><body style='font-family:sans-serif;padding:18px;background:#fff8d8;text-align:center'>"+
                        "<h3>Image search</h3><p>Image selected. Search is performed inside Fast_Browser_PC.</p>"+
                        "<img src='"+u+"' style='max-width:90%;max-height:55vh'><br><br>"+
                        "<button onclick=\"location.href='https://lens.google.com/'\">Search image in browser</button>"+
                        "</body></html>";
                getWeb().loadDataWithBaseURL("https://fastbrowser.local/image-search/",html,"text/html","UTF-8",null);
            }
            return;
        }
        if(requestCode==7413 && fileChooserCallback!=null){
            Uri[] result=null;
            if(resultCode==RESULT_OK && data!=null){
                if(data.getClipData()!=null){int n=data.getClipData().getItemCount(); result=new Uri[n]; for(int i=0;i<n;i++) result[i]=data.getClipData().getItemAt(i).getUri();}
                else if(data.getData()!=null) result=new Uri[]{data.getData()};
            }
            fileChooserCallback.onReceiveValue(result); fileChooserCallback=null;
        }
    }
    private void showEngineMenu(){
        PopupMenu menu = new PopupMenu(this, searchEngine);
        for(Engine e:engines){
            MenuItem item=menu.getMenu().add(e.icon+"  "+e.name);
            item.setOnMenuItemClickListener(x->{activeEngine=e; prefs.edit().putString("engine",e.name).apply(); updateEngineButton(); return true;});
        }
        MenuItem add=menu.getMenu().add("＋  Add search engine");
        add.setOnMenuItemClickListener(x->{showAddEngineDialog(); return true;});
        menu.show();
    }

    private void showAddEngineDialog(){
        LinearLayout box=new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL); box.setPadding(28,4,28,0);
        EditText name=new EditText(this); name.setHint("Name");
        EditText icon=new EditText(this); icon.setHint("Icon / short name");
        EditText template=new EditText(this); template.setHint("Search URL containing %s");
        box.addView(name); box.addView(icon); box.addView(template);
        new AlertDialog.Builder(this).setTitle("Add search engine").setView(box)
            .setPositiveButton("Add",(d,w)->{
                String n=name.getText().toString().trim(), i=icon.getText().toString().trim(), u=template.getText().toString().trim();
                if(!n.isEmpty()&&!u.isEmpty()&&u.contains("%s")){ engines.add(new Engine(n,i.isEmpty()?n.substring(0,1):i,u)); saveCustomEngines(); activeEngine=engines.get(engines.size()-1); prefs.edit().putString("engine",n).apply(); updateEngineButton(); }
            }).setNegativeButton("Cancel",null).show();
    }

    private void addTab(String initial){
        WebView w=new WebView(this); configureWeb(w);
        w.setSaveEnabled(true);
        Tab t=new Tab(w); t.lastUrl=initial; tabs.add(t);
        switchTab(tabs.size()-1); load(initial);
    }

    /** Restores every open tab, including its WebView back/forward state and scroll position. */
    private boolean restoreTabs(Bundle state){
        if(state==null) return false;
        int count=state.getInt("tab_count",0);
        if(count<=0) return false;
        for(int i=0;i<count;i++){
            Bundle saved=state.getBundle("tab_"+i);
            if(saved==null) continue;
            WebView w=new WebView(this);
            configureWeb(w);
            w.setSaveEnabled(true);
            Tab t=new Tab(w);
            t.title=saved.getString("title","New Tab");
            t.lastUrl=saved.getString("url","fastbrowser:home");
            tabs.add(t);
            Bundle webState=saved.getBundle("web");
            if(webState!=null){
                try { w.restoreState(webState); } catch(Exception ignored) {}
            }
        }
        if(tabs.isEmpty()) return false;
        currentTab=Math.max(0,Math.min(state.getInt("current_tab",0),tabs.size()-1));
        webContainer.removeAllViews();
        webContainer.addView(tabs.get(currentTab).web,new FrameLayout.LayoutParams(-1,-1));
        url.setText(tabs.get(currentTab).web.getUrl()!=null ? tabs.get(currentTab).web.getUrl() : tabs.get(currentTab).lastUrl);
        refreshTabs();
        startMediaScanner(tabs.get(currentTab).web);
        return true;
    }

    @Override protected void onSaveInstanceState(Bundle out){
        super.onSaveInstanceState(out);
        out.putInt("tab_count",tabs.size());
        out.putInt("current_tab",currentTab);
        for(int i=0;i<tabs.size();i++){
            Tab t=tabs.get(i);
            Bundle saved=new Bundle();
            saved.putString("title",t.title);
            String u=t.web.getUrl();
            saved.putString("url",TextUtils.isEmpty(u)?t.lastUrl:u);
            Bundle webState=new Bundle();
            try { t.web.saveState(webState); } catch(Exception ignored) {}
            saved.putBundle("web",webState);
            out.putBundle("tab_"+i,saved);
        }
    }

    private void configureWeb(WebView w){
        w.setSaveEnabled(true);
        WebSettings s=w.getSettings();
        // Full-featured WebView configuration for modern, script-heavy websites.
        s.setJavaScriptEnabled(true);
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setGeolocationEnabled(true);
        s.setLoadsImagesAutomatically(true);
        s.setBlockNetworkImage(false);
        s.setBlockNetworkLoads(false);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setAllowFileAccessFromFileURLs(false);
        s.setAllowUniversalAccessFromFileURLs(false);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setBuiltInZoomControls(false); s.setDisplayZoomControls(false);
        s.setSupportZoom(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setSupportMultipleWindows(true);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        // Start each newly loaded page fitted to the available WebView width.
        // The user can still zoom/resize afterwards.
        w.setInitialScale(0);
        s.setTextZoom(100);
        s.setDefaultFontSize(16);
        s.setDefaultFixedFontSize(13);
        s.setMinimumFontSize(8);
        s.setMinimumLogicalFontSize(8);
        s.setStandardFontFamily("sans-serif");
        s.setSansSerifFontFamily("sans-serif");
        s.setSerifFontFamily("serif");
        s.setFixedFontFamily("monospace");
        s.setCursiveFontFamily("cursive");
        s.setFantasyFontFamily("fantasy");
        if(Build.VERSION.SDK_INT >= 21){
            s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        }
        if(Build.VERSION.SDK_INT >= 26){
            s.setSafeBrowsingEnabled(true);
        }
        if(WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK)){
            WebSettingsCompat.setForceDark(s, WebSettingsCompat.FORCE_DARK_OFF);
        }
        CookieManager cm=CookieManager.getInstance();
        cm.setAcceptCookie(true);
        if(Build.VERSION.SDK_INT >= 21) cm.setAcceptThirdPartyCookies(w,true);
        w.setWebChromeClient(new WebChromeClient(){
            @Override public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture, Message resultMsg){
                if(tabs.size() >= 20) return false;
                addTab("about:blank");
                WebView child=getWeb();
                WebView.WebViewTransport transport=(WebView.WebViewTransport)resultMsg.obj;
                transport.setWebView(child);
                resultMsg.sendToTarget();
                return true;
            }
            @Override public void onCloseWindow(WebView window){
                if(tabs.size()>1){
                    int idx=-1; for(int i=0;i<tabs.size();i++) if(tabs.get(i).web==window){idx=i;break;}
                    if(idx>=0){tabs.remove(idx); switchTab(Math.max(0,Math.min(currentTab,tabs.size()-1)));}
                }
            }
            @Override public boolean onJsAlert(WebView view,String url,String message,JsResult result){
                new AlertDialog.Builder(MainActivity.this).setMessage(message).setPositiveButton("OK",(d,w1)->result.confirm()).setOnCancelListener(d->result.cancel()).show();
                return true;
            }
            @Override public boolean onJsConfirm(WebView view,String url,String message,JsResult result){
                new AlertDialog.Builder(MainActivity.this).setMessage(message).setNegativeButton("Cancel",(d,w1)->result.cancel()).setPositiveButton("OK",(d,w1)->result.confirm()).setOnCancelListener(d->result.cancel()).show();
                return true;
            }
            @Override public boolean onJsPrompt(WebView view,String url,String message,String defaultValue,JsPromptResult result){
                EditText input=new EditText(MainActivity.this); input.setSingleLine(false); input.setText(defaultValue);
                new AlertDialog.Builder(MainActivity.this).setMessage(message).setView(input).setNegativeButton("Cancel",(d,w1)->result.cancel()).setPositiveButton("OK",(d,w1)->result.confirm(input.getText().toString())).setOnCancelListener(d->result.cancel()).show();
                return true;
            }
            @Override public void onGeolocationPermissionsShowPrompt(String origin,GeolocationPermissions.Callback callback){
                if(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED){
                    requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION},REQ_WEB_PERMISSIONS);
                    callback.invoke(origin,false,false);
                } else callback.invoke(origin,true,false);
            }
            @Override public void onPermissionRequest(PermissionRequest request){
                runOnUiThread(()->{
                    ArrayList<String> needed=new ArrayList<>();
                    for(String r:request.getResources()){
                        if(PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(r) && checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED) needed.add(Manifest.permission.CAMERA);
                        if(PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(r) && checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED) needed.add(Manifest.permission.RECORD_AUDIO);
                    }
                    if(!needed.isEmpty()){
                        pendingWebPermissionRequest=request;
                        requestPermissions(needed.toArray(new String[0]),REQ_WEB_PERMISSIONS);
                    } else request.grant(request.getResources());
                });
            }
            @Override public boolean onShowFileChooser(WebView webView,ValueCallback<Uri[]> callback,FileChooserParams params){
                Intent i=params.createIntent();
                try{startActivityForResult(i,7413); fileChooserCallback=callback;}catch(Exception e){callback.onReceiveValue(null);} return true;
            }
            @Override public void onShowCustomView(View view,CustomViewCallback callback){
                if(fullScreenView!=null){callback.onCustomViewHidden();return;}
                fullScreenView=view; fullScreenCallback=callback;
                addContentView(view,new ViewGroup.LayoutParams(-1,-1));
                view.bringToFront();
                getWindow().getDecorView().setSystemUiVisibility(5894|View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
                showFullscreenExitButton();
            }
            @Override public void onHideCustomView(){
                hideFullscreenView();
            }
        });
    }

    private void hideFullscreenView() {
        removeFullscreenExitButton();
        if(fullScreenView!=null){
            ViewParent parent=fullScreenView.getParent();
            if(parent instanceof ViewGroup)((ViewGroup)parent).removeView(fullScreenView);
            fullScreenView=null;
        }
        if(fullScreenCallback!=null){
            fullScreenCallback.onCustomViewHidden();
            fullScreenCallback=null;
        }
        getWindow().getDecorView().setSystemUiVisibility(0);
    }

    /** Adds a small, semi-transparent, draggable exit control over fullscreen media. */
    private void showFullscreenExitButton() {
        removeFullscreenExitButton();
        TextView b = new TextView(this);
        b.setText("↩");
        b.setTextColor(Color.WHITE);
        b.setTextSize(18f);
        b.setGravity(Gravity.CENTER);
        b.setContentDescription("Exit full screen");
        b.setBackgroundColor(Color.argb(125, 0, 0, 0));
        b.setAlpha(0.62f);
        b.setPadding(0, 0, 0, 1);
        final float d = getResources().getDisplayMetrics().density;
        int size = (int)(44 * d + 0.5f);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(size, size, Gravity.TOP | Gravity.END);
        lp.setMargins(0, (int)(18*d), (int)(10*d), 0);
        addContentView(b, lp);
        b.bringToFront();
        fullScreenExitButton = b;

        b.setOnTouchListener(new View.OnTouchListener() {
            float downX, downY, startX, startY;
            boolean moved;
            @Override public boolean onTouch(View v, MotionEvent e) {
                ViewGroup.LayoutParams rawLp = v.getLayoutParams();
                if (!(rawLp instanceof FrameLayout.LayoutParams)) return false;
                FrameLayout.LayoutParams flp = (FrameLayout.LayoutParams) rawLp;
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downX=e.getRawX(); downY=e.getRawY();
                        startX=flp.leftMargin; startY=flp.topMargin;
                        moved=false;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        float nx=startX+(e.getRawX()-downX);
                        float ny=startY+(e.getRawY()-downY);
                        if(Math.abs(e.getRawX()-downX)>8*d || Math.abs(e.getRawY()-downY)>8*d) moved=true;
                        int sw=getResources().getDisplayMetrics().widthPixels;
                        int sh=getResources().getDisplayMetrics().heightPixels;
                        flp.gravity=Gravity.TOP|Gravity.START;
                        flp.leftMargin=(int)Math.max(0,Math.min(nx,sw-v.getWidth()));
                        flp.topMargin=(int)Math.max(0,Math.min(ny,sh-v.getHeight()));
                        v.setLayoutParams(flp);
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        if(e.getActionMasked()==MotionEvent.ACTION_UP && !moved) {
                            hideFullscreenView();
                        }
                        return true;
                }
                return true;
            }
        });
    }

    private void removeFullscreenExitButton() {
        if(fullScreenExitButton!=null) {
            ViewParent parent=fullScreenExitButton.getParent();
            if(parent instanceof ViewGroup)((ViewGroup)parent).removeView(fullScreenExitButton);
            fullScreenExitButton=null;
        }
    }

    /** Shows a red blinking traffic warning in the top corner for exactly 3 seconds. */
    private void showHighTrafficWarning() {
        if (webContainer == null) return;
        if (highTrafficHide != null) handler.removeCallbacks(highTrafficHide);
        if (highTrafficDot != null) webContainer.removeView(highTrafficDot);

        TextView dot = new TextView(this);
        dot.setContentDescription("High internet usage");
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(Color.RED);
        dot.setBackground(bg);

        int size = (int)(28 * getResources().getDisplayMetrics().density + 0.5f);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(size, size, Gravity.TOP | Gravity.END);
        int margin = (int)(8 * getResources().getDisplayMetrics().density + 0.5f);
        lp.setMargins(0, margin, margin, 0);
        webContainer.addView(dot, lp);
        highTrafficDot = dot;
        highTrafficWarningShown = true;

        AlphaAnimation blink = new AlphaAnimation(1f, 0.15f);
        blink.setDuration(260);
        blink.setRepeatMode(AlphaAnimation.REVERSE);
        blink.setRepeatCount(AlphaAnimation.INFINITE);
        dot.startAnimation(blink);

        highTrafficHide = () -> {
            if (highTrafficDot != null) {
                highTrafficDot.clearAnimation();
                webContainer.removeView(highTrafficDot);
                highTrafficDot = null;
            }
            highTrafficWarningShown = false;
        };
        handler.postDelayed(highTrafficHide, 3000L);
    }

    /**
     * Estimates transferred bytes using the Web Performance Resource Timing API.
     * A warning is raised when the page has transferred about 5 MB or more during
     * the initial loading period. Cached resources may report zero bytes, so the
     * estimate intentionally uses the largest available browser timing value.
     */
    private void startHighTrafficMonitor(WebView w) {
        if (highTrafficMonitor != null) handler.removeCallbacks(highTrafficMonitor);
        highTrafficWarningShown = false;
        final long started = SystemClock.uptimeMillis();
        final long[] lastBytes = {0L};
        final long[] peakBytes = {0L};
        final long threshold = 5L * 1024L * 1024L;

        highTrafficMonitor = new Runnable() {
            @Override public void run() {
                if (w.getUrl() == null || SystemClock.uptimeMillis() - started > 9000L) return;
                String js = "(function(){try{var a=performance.getEntriesByType('resource')||[];var n=0;for(var i=0;i<a.length;i++){var x=a[i],t=Number(x.transferSize)||0,e=Number(x.encodedBodySize)||0,d=Number(x.decodedBodySize)||0;n+=Math.max(t,e,d);}return String(Math.floor(n));}catch(e){return '0';}})()";
                w.evaluateJavascript(js, value -> {
                    try {
                        if (value == null) return;
                        long bytes = Long.parseLong(value.replace("\\\"", "").replace("\"", ""));
                        if (bytes > peakBytes[0]) peakBytes[0] = bytes;
                        // Also accept a large new burst, not only a large total.
                        long delta = Math.max(0L, bytes - lastBytes[0]);
                        lastBytes[0] = Math.max(lastBytes[0], bytes);
                        if (!highTrafficWarningShown && (peakBytes[0] >= threshold || delta >= threshold)) {
                            showHighTrafficWarning();
                            return;
                        }
                    } catch (Exception ignored) { }
                    handler.postDelayed(this, 500L);
                });
            }
        };
        handler.postDelayed(highTrafficMonitor, 500L);

        w.setWebViewClient(new WebViewClient(){
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r){
                if(r==null||r.getUrl()==null) return false;
                String scheme=r.getUrl().getScheme();
                if(scheme==null || "http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) return false;
                final android.net.Uri externalUri=r.getUrl();
                final String external=externalUri.toString();
                new AlertDialog.Builder(MainActivity.this)
                        .setTitle("Open outside browser?")
                        .setMessage(external)
                        .setNegativeButton("Cancel",null)
                        .setPositiveButton("Open",(d,w1)->{
                            try{ startActivity(new Intent(Intent.ACTION_VIEW,externalUri)); }catch(Exception ex){ showMessage4("No app can open this link"); }
                        }).show();
                return true;
            }
            @Override public void onLoadResource(WebView v, String u){ detector.inspect(u, v.getUrl(), "resource"); }
            @Override public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest req){
                String u=req==null||req.getUrl()==null?"":req.getUrl().toString();
                if(adBlockEnabled && isBlockedAdUrl(u)) return emptyResponse();
                detector.inspect(req, v.getUrl());
                return super.shouldInterceptRequest(v, req);
            }
            @Override public WebResourceResponse shouldInterceptRequest(WebView v, String u){
                if(adBlockEnabled && isBlockedAdUrl(u)) return emptyResponse();
                detector.inspect(u, v.getUrl(), "network");
                return super.shouldInterceptRequest(v, u);
            }

            @Override public void onPageStarted(WebView v,String u,android.graphics.Bitmap icon){
                detector.clear();
                startMediaScanner(v);
                startHighTrafficMonitor(v);
            }
            @Override public void onPageFinished(WebView v,String u){
                Tab t=null;
                int finishedIndex=-1;
                for(int i=0;i<tabs.size();i++){ if(tabs.get(i).web==v){ t=tabs.get(i); finishedIndex=i; break; } }
                if(t==null) return;
                t.lastUrl=u; t.title=v.getTitle()==null?"Tab":v.getTitle();
                if(finishedIndex==currentTab) url.setText(u);
                if(!incognitoMode) { prefs.edit().putString("last",u).apply(); addHistoryEntry(u, v.getTitle()); }
                refreshTabs();
                injectMediaScanner(v);
                CookieManager.getInstance().flush();
            }
            @Override public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail){
                int idx=-1; for(int i=0;i<tabs.size();i++) if(tabs.get(i).web==view){idx=i;break;}
                if(idx>=0){tabs.remove(idx); if(tabs.isEmpty()) addTab("fastbrowser:home"); else switchTab(Math.min(currentTab,tabs.size()-1));}
                return true;
            }
        });
        if (Build.VERSION.SDK_INT >= 24) {
            ServiceWorkerController.getInstance().setServiceWorkerClient(new ServiceWorkerClient(){
                @Override public WebResourceResponse shouldInterceptRequest(WebResourceRequest request){
                    String u=request==null||request.getUrl()==null?"":request.getUrl().toString();
                    if(adBlockEnabled && isBlockedAdUrl(u)) return emptyResponse();
                    detector.inspect(request, getWebSafeUrl());
                    return super.shouldInterceptRequest(request);
                }
            });
        }
        w.setDownloadListener((u,ua,cd,mime,len)->{
            // The browser's own downloader is used only after a real download URL is exposed.
            detector.inspect(u, w.getUrl(), "download");
            startDownload(u,ua,cd,mime);
        });
        // Remember the last point the user touched inside the page. The Copy button
        // uses this point to copy the content of the clicked internal section/window
        // instead of blindly copying the whole page. Returning false keeps normal
        // WebView scrolling, links and controls working.
        w.setOnTouchListener((view, event) -> {
            if (event.getAction() == MotionEvent.ACTION_DOWN ||
                    event.getAction() == MotionEvent.ACTION_UP) {
                w.setTag(R.id.webContainer, new float[]{event.getX(), event.getY()});
            }
            return false;
        });
        w.setOnLongClickListener(view -> {
            return handleLongPress(w);
        });
        applyModesToWeb(w);
    }

    private String getWebSafeUrl(){ return tabs.isEmpty() ? "" : tabs.get(currentTab).lastUrl; }

    private void startMediaScanner(final WebView v){
        if(mediaScan != null) handler.removeCallbacks(mediaScan);
        mediaScan = new Runnable(){ @Override public void run(){
            if(v == getWeb()) injectMediaScanner(v);
            handler.postDelayed(this, 2200);
        }};
        handler.postDelayed(mediaScan, 1000);
    }

    private void injectMediaScanner(WebView v){
        String js = "(function(){var a=[];try{"+
                "document.querySelectorAll('video,audio,source').forEach(function(x){if(x.src)a.push(x.src);if(x.currentSrc)a.push(x.currentSrc);});"+
                "document.querySelectorAll('[src]').forEach(function(x){var s=x.src||'';if(/\\.(mp4|webm|m4v|mov|mkv|m3u8|mpd|ts)(\\?|#|$)/i.test(s))a.push(s);});"+
                "if(window.performance&&performance.getEntriesByType){performance.getEntriesByType('resource').forEach(function(e){var s=e.name||'';if(/\\.(mp4|webm|m4v|mov|mkv|m3u8|mpd|ts)(\\?|#|$)/i.test(s)||/manifest|playlist/i.test(s))a.push(s);});}"+
                "}catch(e){}return JSON.stringify(a.filter(function(x){return x&&/^https?:/i.test(x);}));})()";
        v.evaluateJavascript(js, value->{
            if(value==null) return;
            String raw=value;
            if(raw.startsWith("\"") && raw.endsWith("\"")) raw=raw.substring(1,raw.length()-1).replace("\\\"","\"").replace("\\\\","\\");
            try {
                org.json.JSONArray arr=new org.json.JSONArray(raw);
                for(int i=0;i<arr.length();i++) detector.inspect(arr.optString(i), v.getUrl(), "page");
            } catch(Exception ignored) {}
        });
    }

    private WebView getWeb(){ return tabs.get(currentTab).web; }
    private void switchTab(int index){
        if(index<0||index>=tabs.size())return;
        currentTab=index; webContainer.removeAllViews(); webContainer.addView(tabs.get(index).web,new FrameLayout.LayoutParams(-1,-1));
        ensureMouseCursor();
        url.setText(tabs.get(index).lastUrl); refreshTabs(); startMediaScanner(tabs.get(index).web);
    }

    private void refreshTabs(){
        tabsBar.removeAllViews();
        for(int i=0;i<tabs.size();i++){
            final int index=i;
            LinearLayout tab=new LinearLayout(this); tab.setGravity(Gravity.CENTER_VERTICAL); tab.setPadding(9,0,5,0); tab.setBackgroundColor(i==currentTab?Color.WHITE:Color.rgb(241,227,170));
            TextView title=new TextView(this); title.setText(tabs.get(i).title); title.setTextSize(10); title.setSingleLine(true); title.setMaxWidth(180); title.setPadding(2,0,5,0);
            TextView close=new TextView(this); close.setText("×"); close.setTextSize(15); close.setGravity(Gravity.CENTER); close.setPadding(5,0,5,0);
            tab.addView(title,new LinearLayout.LayoutParams(-2,-1)); tab.addView(close,new LinearLayout.LayoutParams(30,-1));
            tab.setOnClickListener(v->switchTab(index)); close.setOnClickListener(v->closeTab(index));
            tabsBar.addView(tab,new LinearLayout.LayoutParams(-2,-1));
        }
        TextView plus=new TextView(this); plus.setText("＋"); plus.setTextSize(18); plus.setGravity(Gravity.CENTER); plus.setPadding(8,0,10,0); plus.setOnClickListener(v->addTab("fastbrowser:home"));
        tabsBar.addView(plus,new LinearLayout.LayoutParams(40,-1));
    }

    private void closeTab(int index){
        if(tabs.size()==1){tabs.get(0).lastUrl="fastbrowser:home"; load(tabs.get(0).lastUrl); return;}
        tabs.get(index).web.destroy(); tabs.remove(index); if(currentTab>=tabs.size())currentTab=tabs.size()-1; else if(index<currentTab)currentTab--; switchTab(currentTab);
    }

    private void load(String q){
        q=q.trim(); if(q.isEmpty())return;
        if("fastbrowser:home".equalsIgnoreCase(q)){
            loadHome(getWeb());
            tabs.get(currentTab).lastUrl="fastbrowser:home";
            url.setText("Home");
            return;
        }
        if(!q.matches("(?i)^[a-z][a-z0-9+.-]*://.*$")){
            if(q.contains(".")&&!q.contains(" ")) q="https://"+q;
            else q=activeEngine.searchUrl.replace("%s",Uri.encode(q));
        }
        detector.clear(); getWeb().loadUrl(q); tabs.get(currentTab).lastUrl=q;
    }

    private void loadHome(WebView v){
        detector.clear();
        String html = HOME_HTML;
        v.loadDataWithBaseURL("https://fastbrowser.local/", html, "text/html", "UTF-8", null);
        tabs.get(currentTab).title="Home";
        refreshTabs();
    }

    private static final String HOME_HTML = "<!doctype html><html><head><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><style>" +
            "*{box-sizing:border-box}body{margin:0;padding:18px 14px 26px;background:#fff8d8;color:#243447;font-family:sans-serif}" +
            "h1{font-size:21px;text-align:center;margin:4px 0 18px;color:#26384a}" +
            "h2{font-size:18px;margin:20px 0 10px;padding:8px 10px;background:#f1e3aa;border-radius:9px}" +
            "h3{font-size:15px;margin:15px 0 8px;color:#5a4a18;border-bottom:1px solid #ddcf98;padding-bottom:5px}" +
            ".grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(145px,1fr));gap:9px}" +
            ".card{display:flex;align-items:center;gap:9px;text-decoration:none;color:#1e3347;background:#fffdf3;border:1px solid #ded5ad;border-radius:9px;padding:9px;min-height:62px;box-shadow:0 1px 2px #00000012}" +
            ".card:active{background:#f5edc9}.icon{width:34px;height:34px;flex:0 0 34px;border-radius:7px;object-fit:contain;background:white}.name{font-size:13px;font-weight:600}.meta{font-size:10px;color:#777;margin-top:3px}" +
            ".note{font-size:10px;color:#777;text-align:center;margin-top:18px}" +
            "</style></head><body>" +
            "<h1>Fast Browser PC — Home</h1>" +
            "<h2>💬 پیام‌رسان‌های ایرانی و خارجی</h2><div class=grid>" +
            homeCard("https://bale.ai","بله (Bale)","پیام‌رسان ایرانی","https://bale.ai/favicon.ico") +
            homeCard("https://eitaa.com","ایتا (Eitaa)","پیام‌رسان ایرانی","https://eitaa.com/favicon.ico") +
            homeCard("https://rubika.ir","روبیکا (Rubika)","پیام‌رسان و شبکه اجتماعی ایرانی","https://rubika.ir/favicon.ico") +
            homeCard("https://gap.im","گپ (Gap)","پیام‌رسان ایرانی","https://gap.im/favicon.ico") +
            homeCard("https://igap.net","آی‌گپ (iGap)","پیام‌رسان ایرانی","https://igap.net/favicon.ico") +
            homeCard("https://splus.ir","سروش پلاس","پیام‌رسان ایرانی","https://splus.ir/favicon.ico") +
            homeCard("https://telegram.org","Telegram","پیام‌رسان بین‌المللی","https://telegram.org/favicon.ico") +
            homeCard("https://web.whatsapp.com","WhatsApp","پیام‌رسان بین‌المللی","https://web.whatsapp.com/favicon.ico") +
            homeCard("https://signal.org","Signal","پیام‌رسان بین‌المللی","https://signal.org/favicon.ico") +
            homeCard("https://discord.com","Discord","گفت‌وگو و جامعه آنلاین","https://discord.com/favicon.ico") +
            homeCard("https://www.viber.com","Viber","پیام‌رسان بین‌المللی","https://www.viber.com/favicon.ico") +
            "</div>" +
            "<h2>🤖 هوش مصنوعی</h2><div class=grid>" +
            homeCard("https://chatgpt.com","ChatGPT","هوش مصنوعی و دستیار گفتگو","https://chatgpt.com/favicon.ico") +
            homeCard("https://gemini.google.com","Google Gemini","هوش مصنوعی گوگل","https://gemini.google.com/favicon.ico") +
            homeCard("https://claude.ai","Claude","دستیار هوش مصنوعی","https://claude.ai/favicon.ico") +
            homeCard("https://copilot.microsoft.com","Microsoft Copilot","دستیار هوش مصنوعی مایکروسافت","https://copilot.microsoft.com/favicon.ico") +
            homeCard("https://www.perplexity.ai","Perplexity","جست‌وجو و پاسخ هوش مصنوعی","https://www.perplexity.ai/favicon.ico") +
            homeCard("https://chat.deepseek.com","DeepSeek","هوش مصنوعی و کدنویسی","https://chat.deepseek.com/favicon.ico") +
            homeCard("https://grok.com","Grok","دستیار هوش مصنوعی","https://grok.com/favicon.ico") +
            homeCard("https://poe.com","Poe","دسترسی به چند مدل هوش مصنوعی","https://poe.com/favicon.ico") +
            "</div>" +
            "<h2>📚 ویکی و دانش</h2><div class=grid>" +
            homeCard("https://www.wikipedia.org","Wikipedia","دانشنامه آزاد","https://www.wikipedia.org/static/favicon/wikipedia.ico") +
            homeCard("https://www.wiktionary.org","Wiktionary","واژه‌نامه آزاد","https://www.wiktionary.org/static/favicon/wiktionary.ico") +
            homeCard("https://www.wikibooks.org","Wikibooks","کتاب‌های آزاد","https://www.wikibooks.org/static/favicon/wikibooks.ico") +
            homeCard("https://www.wikiquote.org","Wikiquote","نقل‌قول‌ها و منابع","https://www.wikiquote.org/static/favicon/wikiquote.ico") +
            homeCard("https://www.wikidata.org","Wikidata","پایگاه داده دانش","https://www.wikidata.org/static/favicon/wikidata.ico") +
            homeCard("https://commons.wikimedia.org","Wikimedia Commons","رسانه و تصاویر آزاد","https://commons.wikimedia.org/static/favicon/commons.ico") +
            "</div>" +
            "<h2>🎬 ویدئو، آرشیو و سرگرمی</h2><div class=grid>" +
            homeCard("https://archive.org","Internet Archive","آرشیو دیجیتال بزرگ","https://archive.org/favicon.ico") +
            homeCard("https://www.aparat.com","آپارات (Aparat)","اشتراک‌گذاری ویدئو","https://www.aparat.com/favicon.ico") +
            homeCard("https://telewebion.com","تلوبیون (Telewebion)","تلویزیون و ویدئوی آنلاین","https://telewebion.com/favicon.ico") +
            homeCard("https://www.filimo.com","فیلیمو (Filimo)","فیلم و سریال آنلاین","https://www.filimo.com/favicon.ico") +
            homeCard("https://www.namava.ir","نماوا (Namava)","فیلم و سریال آنلاین","https://www.namava.ir/favicon.ico") +
            homeCard("https://www.youtube.com","YouTube","ویدئو و پخش آنلاین","https://www.youtube.com/favicon.ico") +
            homeCard("https://vimeo.com","Vimeo","ویدئو و پخش آنلاین","https://vimeo.com/favicon.ico") +
            homeCard("https://www.dailymotion.com","Dailymotion","اشتراک‌گذاری ویدئو","https://www.dailymotion.com/favicon.ico") +
            "</div>" +
            "<h2>💻 برنامه‌نویسی، ساخت برنامه و بازی</h2><div class=grid>" +
            homeCard("https://github.com","GitHub","کد، مخزن، پروژه و همکاری","https://github.com/favicon.ico") +
            homeCard("https://gitlab.com","GitLab","مخزن کد و DevOps","https://gitlab.com/favicon.ico") +
            homeCard("https://developer.android.com/studio","Android Studio","محیط رسمی ساخت برنامه‌های Android","https://developer.android.com/favicon.ico") +
            homeCard("https://godotengine.org","Godot Engine","ساخت بازی و برنامه","https://godotengine.org/favicon.ico") +
            homeCard("https://unity.com","Unity","موتور ساخت بازی","https://unity.com/favicon.ico") +
            homeCard("https://itch.io","itch.io","بازی‌های مستقل و توسعه‌دهندگان","https://itch.io/favicon.ico") +
            "</div>" +
            "<h2>📻 رادیو و موسیقی</h2>" +
            "<h3>آسیا</h3><div class=grid>" +
            homeCard("https://www.jiosaavn.com","JioSaavn","هند • رادیو و موسیقی","https://www.jiosaavn.com/favicon.ico") +
            homeCard("https://wynk.in/music","Wynk Music","هند • موسیقی","https://wynk.in/favicon.ico") +
            "</div>" +
            "<h3>آفریقا</h3><div class=grid>" +
            homeCard("https://connectu.vodacom.co.za","ConnectU","آفریقای جنوبی • رادیو / آموزش","https://connectu.vodacom.co.za/favicon.ico") +
            "</div>" +
            "<h3>اروپا</h3><div class=grid>" +
            homeCard("https://www.deezer.com","Deezer","فرانسه • رادیو و موسیقی","https://www.deezer.com/favicon.ico") +
            "</div>" +
            "<h3>آمریکای شمالی</h3><div class=grid>" +
            homeCard("https://www.iheart.com","iHeartRadio","آمریکا • رادیو زنده","https://www.iheart.com/favicon.ico") +
            "</div>" +
            "<h3>اقیانوسیه</h3><div class=grid>" +
            homeCard("https://www.telstra.com.au/entertainment/music","Telstra Music","استرالیا • موسیقی","https://www.telstra.com.au/favicon.ico") +
            "</div>" +
            "<h2>🌐 سایر سایت‌ها</h2>" +
            "<h3>آسیا</h3><div class=grid>" +
            homeCard("https://www.jio.com","Jio","هند • درگاه اصلی","https://www.jio.com/favicon.ico") +
            homeCard("https://www.jiocinema.com","JioCinema","هند • محتوای رایگان","https://www.jiocinema.com/favicon.ico") +
            "</div>" +
            "<h3>آفریقا</h3><div class=grid>" +
            homeCard("https://connectu.vodacom.co.za","ConnectU","آفریقای جنوبی • درگاه اختصاصی","https://connectu.vodacom.co.za/favicon.ico") +
            "</div>" +
            "<h3>اروپا</h3><div class=grid>" +
            homeCard("https://www.nhs.uk","NHS","انگلستان • سلامت","https://www.nhs.uk/favicon.ico") +
            "</div>" +
            "<h3>آمریکای شمالی</h3><div class=grid>" +
            homeCard("https://www.t-mobile.com","T-Mobile","آمریکا • درگاه اصلی","https://www.t-mobile.com/favicon.ico") +
            "</div>" +
            "<h3>آمریکای جنوبی</h3><div class=grid>" +
            homeCard("https://www.vivo.com.br","Vivo","برزیل • درگاه اصلی","https://www.vivo.com.br/favicon.ico") +
            "</div>" +
            "<h3>جهانی</h3><div class=grid>" +
            homeCard("https://www.freebasics.com","Free Basics","سرویس جهانی","https://www.freebasics.com/favicon.ico") +
            "</div>" +
            "<h2>📺📻 تلویزیون و رادیو استریم جهانی آنلاین</h2>" +
            "<h3>📺 تلویزیون زنده و استریم تصویری</h3><div class=grid>" +
            homeCard("https://pluto.tv/","Pluto TV","تلویزیون زنده و فیلم • استریم آنلاین","https://pluto.tv/favicon.ico") +
            homeCard("https://watch.plex.tv/","Plex","تلویزیون زنده و محتوای آنلاین","https://www.plex.tv/favicon.ico") +
            homeCard("https://www.euronews.com/live","Euronews Live","اخبار زنده بین‌المللی","https://www.euronews.com/favicon.ico") +
            homeCard("https://www.aljazeera.com/video/live/","Al Jazeera Live","پخش زنده تلویزیونی","https://www.aljazeera.com/favicon.ico") +
            "</div>" +
            "<h3>📻 رادیو زنده جهانی</h3><div class=grid>" +
            homeCard("https://radio.garden/","Radio Garden","رادیوی زنده از سراسر جهان","https://radio.garden/favicon.ico") +
            homeCard("https://tunein.com/","TuneIn","رادیو، موسیقی و اخبار زنده","https://tunein.com/favicon.ico") +
            homeCard("https://www.radio.net/","radio.net","ده‌ها هزار ایستگاه رادیویی جهان","https://www.radio.net/favicon.ico") +
            "</div>" +
            "<div class=note>بعضی سرویس‌ها ممکن است بر اساس کشور، مجوز محتوا یا شرایط سرویس در دسترس نباشند.</div></body></html>";

    private static String homeCard(String href,String name,String meta,String icon){
        return "<a class=card href=\""+href+"\"><img class=icon src=\""+icon+"\" onerror=\"this.style.display='none'\"><div><div class=name>"+name+"</div><div class=meta>"+meta+"</div></div></a>";
    }

    private void addBottom(){
        LinearLayout bar=findViewById(R.id.bottom);
        // Compact text-only controls: no decorative icons, leaving maximum space for the web page.
        String[] names={"Setups","Tool","Links","Muse","Copy","PC_Phone","Text Size"};
        for(String n:names){
            TextView t=new TextView(this);
            t.setText(n);
            t.setTextSize(10);
            t.setTextColor(Color.rgb(20,35,55));
            t.setGravity(Gravity.CENTER);
            t.setSingleLine(true);
            t.setPadding(1,0,1,0);
            bar.addView(t,new LinearLayout.LayoutParams(0,-1,1));
            if(n.equals("Setups")) t.setOnClickListener(v->showSetups());
            else if(n.equals("PC_Phone")) t.setOnClickListener(v->toggleDesktopMode());
            else if(n.equals("Text Size")) t.setOnClickListener(v->showTextSize());
            else if(n.equals("Copy")) t.setOnClickListener(v->copyPage());
            else if(n.equals("Links")){ linksButton=t; t.setOnClickListener(v->showVideoLinks()); }
            else if(n.equals("Tool")) t.setOnClickListener(v->showTools());
            else if(n.equals("Muse")) t.setOnClickListener(v->showMouseWindow());
        }
    }

    /**
     * Mouse window: the supplied reference image is the complete visual surface.
     * Transparent touch zones are placed exactly over its touchpad and four
     * labelled controls, so no replacement artwork or extra decoration is used.
     */
    private void showMouseWindow(){
        if(mouseDialog != null && mouseDialog.isShowing()) return;

        final Dialog d = new Dialog(this);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);

        final FrameLayout root = new FrameLayout(this);
        root.setClipChildren(true);

        final ImageView image = new ImageView(this);
        image.setImageResource(R.drawable.mouse_window);
        image.setScaleType(ImageView.ScaleType.FIT_XY);
        image.setClickable(false);
        image.setFocusable(false);
        root.addView(image, new FrameLayout.LayoutParams(-1,-1));

        // The upper part of the reference image is the mouse pad.
        final View touchpad = new View(this);
        touchpad.setBackgroundColor(Color.TRANSPARENT);
        touchpad.setClickable(true);
        touchpad.setFocusable(false);
        root.addView(touchpad, new FrameLayout.LayoutParams(-1,0));

        final TextView left = transparentZone();
        final TextView drag = transparentZone();
        final TextView zoom = transparentZone();
        final TextView close = transparentZone();
        root.addView(left);
        root.addView(drag);
        root.addView(zoom);
        root.addView(close);

        // Two transparent resize handles sit on the two upper corners of the reference image.
        // The yellow Drag button itself is the window-move handle.
        final View resizeLeftHandle = transparentZone();
        final View resizeRightHandle = transparentZone();
        root.addView(resizeLeftHandle);
        root.addView(resizeRightHandle);

        Runnable layoutZones = () -> {
            int rw=root.getWidth(), rh=root.getHeight();
            if(rw<=0 || rh<=0) return;
            float sx=rw/430f, sy=rh/290f;
            setMouseZoneLayout(touchpad,0,0,430,211,sx,sy);
            setMouseZoneLayout(left,0,211,126,79,sx,sy);
            setMouseZoneLayout(drag,126,211,128,79,sx,sy);
            setMouseZoneLayout(zoom,254,211,84,79,sx,sy);
            setMouseZoneLayout(close,338,211,92,79,sx,sy);
            setMouseZoneLayout(resizeLeftHandle,0,0,38,38,sx,sy);
            setMouseZoneLayout(resizeRightHandle,392,0,38,38,sx,sy);
        };
        root.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob)->layoutZones.run());

        // Yellow Drag button: press and drag it to move the entire mouse window.
        drag.setOnTouchListener(new View.OnTouchListener(){
            float downX,downY; int startX,startY;
            @Override public boolean onTouch(View v, MotionEvent e){
                Window ww=d.getWindow(); if(ww==null) return true;
                WindowManager.LayoutParams lp=ww.getAttributes();
                switch(e.getActionMasked()){
                    case MotionEvent.ACTION_DOWN:
                        downX=e.getRawX(); downY=e.getRawY(); startX=lp.x; startY=lp.y;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        lp.x=startX+Math.round(e.getRawX()-downX);
                        lp.y=startY+Math.round(e.getRawY()-downY);
                        ww.setAttributes(lp);
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        return true;
                    default: return true;
                }
            }
        });

        // Both upper corners resize the window. Left corner keeps the right edge fixed;
        // right corner keeps the left edge fixed. The image remains at its 430:290 ratio.
        View.OnTouchListener leftResizeListener = new View.OnTouchListener(){
            float downX; int startW,startX;
            @Override public boolean onTouch(View v, MotionEvent e){
                Window ww=d.getWindow(); if(ww==null) return true;
                WindowManager.LayoutParams lp=ww.getAttributes();
                switch(e.getActionMasked()){
                    case MotionEvent.ACTION_DOWN:
                        downX=e.getRawX(); startW=root.getWidth(); startX=lp.x; return true;
                    case MotionEvent.ACTION_MOVE:{
                        DisplayMetrics dm=getResources().getDisplayMetrics();
                        int maxW=(int)(dm.widthPixels*0.95f);
                        int delta=Math.round(e.getRawX()-downX);
                        int newW=Math.max(dp(250),Math.min(maxW,startW-delta));
                        int newH=Math.round(newW*(290f/430f));
                        lp.x=startX+delta;
                        ww.setAttributes(lp);
                        ww.setLayout(newW,newH);
                        return true;
                    }
                    default: return true;
                }
            }
        };
        View.OnTouchListener rightResizeListener = new View.OnTouchListener(){
            float downX; int startW;
            @Override public boolean onTouch(View v, MotionEvent e){
                Window ww=d.getWindow(); if(ww==null) return true;
                WindowManager.LayoutParams lp=ww.getAttributes();
                switch(e.getActionMasked()){
                    case MotionEvent.ACTION_DOWN:
                        downX=e.getRawX(); startW=root.getWidth(); return true;
                    case MotionEvent.ACTION_MOVE:{
                        DisplayMetrics dm=getResources().getDisplayMetrics();
                        int maxW=(int)(dm.widthPixels*0.95f);
                        int newW=Math.max(dp(250),Math.min(maxW,startW+Math.round(e.getRawX()-downX)));
                        int newH=Math.round(newW*(290f/430f));
                        ww.setLayout(newW,newH);
                        return true;
                    }
                    default: return true;
                }
            }
        };
        resizeLeftHandle.setOnTouchListener(leftResizeListener);
        resizeRightHandle.setOnTouchListener(rightResizeListener);

        touchpad.setOnTouchListener(new View.OnTouchListener(){
            float lx,ly;
            @Override public boolean onTouch(View v, MotionEvent e){
                switch(e.getActionMasked()){
                    case MotionEvent.ACTION_DOWN:
                        lx=e.getX(); ly=e.getY();
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        float dx=e.getX()-lx, dy=e.getY()-ly;
                        lx=e.getX(); ly=e.getY();
                        if(mouseZoomMode){
                            if(Math.abs(dy)>0.5f) zoomAtPointer(dy);
                        }else{
                            moveMousePointer(dx,dy,v.getWidth(),v.getHeight());
                            if(mouseDragging){ dispatchMouseMoveSmooth(); }
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        if(mouseDragging){ dispatchMouseEvent("mouseup"); mouseDragging=false; }
                        return true;
                    default: return true;
                }
            }
        });

        left.setOnTouchListener((v,e)->{
            if(e.getActionMasked()==MotionEvent.ACTION_UP){
                if(mouseDragging){ dispatchMouseEvent("mouseup"); mouseDragging=false; }
                dispatchMouseClick();
            }
            return true;
        });
        zoom.setOnClickListener(v -> {
            mouseZoomMode=!mouseZoomMode;
            zoom.setSelected(mouseZoomMode);
            showMessage4(mouseZoomMode ? "Point Zoom ON" : "Point Zoom OFF");
        });
        close.setOnClickListener(v -> {
            if(mouseDragging){ dispatchMouseEvent("mouseup"); mouseDragging=false; }
            mouseDragMode=false;
            mouseZoomMode=false;
            if(mouseCursor!=null) mouseCursor.setVisibility(View.GONE);
            d.dismiss();
        });

        d.setContentView(root);
        d.setOnDismissListener(x->{
            mouseDialog=null;
            mouseDragging=false;
            mouseDragMode=false;
            mouseZoomMode=false;
            if(mouseCursor!=null) mouseCursor.setVisibility(View.GONE);
        });

        d.show();
        Window w=d.getWindow();
        if(w!=null){
            w.setBackgroundDrawableResource(android.R.color.transparent);
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            WindowManager.LayoutParams lp=w.getAttributes();
            lp.dimAmount=0.25f;
            w.setAttributes(lp);
            DisplayMetrics dm=getResources().getDisplayMetrics();
            int width=Math.min((int)(dm.widthPixels*0.92f), dp(430));
            int height=Math.round(width*(290f/430f));
            lp.gravity=Gravity.TOP|Gravity.LEFT;
            lp.x=Math.max(0,(dm.widthPixels-width)/2);
            lp.y=Math.max(0,(dm.heightPixels-height)/3);
            w.setAttributes(lp);
            w.setLayout(width,height);
        }
        mouseDialog=d;
        root.post(() -> {
            layoutZones.run();
            ensureMouseCursor();
            mouseCursor.setVisibility(View.VISIBLE);
            updateMouseCursor();
        });
    }

    private int dp(int value){
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void setMouseZoneLayout(View v,int x,int y,int width,int height,float sx,float sy){
        FrameLayout.LayoutParams lp=(FrameLayout.LayoutParams)v.getLayoutParams();
        lp.leftMargin=Math.round(x*sx);
        lp.topMargin=Math.round(y*sy);
        lp.width=Math.max(1,Math.round(width*sx));
        lp.height=Math.max(1,Math.round(height*sy));
        v.setLayoutParams(lp);
    }

    private TextView transparentZone(){
        TextView v=new TextView(this);
        v.setBackgroundColor(Color.TRANSPARENT);
        v.setClickable(true);
        v.setFocusable(true);
        return v;
    }

    private void addMouseZone(FrameLayout root,View v,int x,int y,int width,int height){
        FrameLayout.LayoutParams lp=new FrameLayout.LayoutParams(0,0);
        lp.leftMargin=x; lp.topMargin=y; lp.width=width; lp.height=height;
        root.addView(v,lp);
        // Scale the fixed reference coordinates with the actual window.
        v.post(()->{
            float sx=root.getWidth()/430f, sy=root.getHeight()/290f;
            FrameLayout.LayoutParams p=(FrameLayout.LayoutParams)v.getLayoutParams();
            p.leftMargin=Math.round(x*sx); p.topMargin=Math.round(y*sy);
            p.width=Math.round(width*sx); p.height=Math.round(height*sy);
            v.setLayoutParams(p);
        });
    }

    private void ensureMouseCursor(){
        if(mouseCursor!=null && mouseCursor.getParent()==webContainer) return;
        mouseCursor=new View(this);
        GradientDrawable g=new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(Color.argb(210,20,20,20));
        g.setStroke(2,Color.WHITE);
        mouseCursor.setBackground(g);
        mouseCursor.setClickable(false);
        mouseCursor.setVisibility(View.GONE);
        FrameLayout.LayoutParams lp=new FrameLayout.LayoutParams(22,22);
        webContainer.addView(mouseCursor,lp);
        mouseCursor.bringToFront();
    }

    private void updateMouseCursor(){
        if(mouseCursor==null) return;
        mouseCursor.post(()->{
            int x=Math.max(0,Math.min(webContainer.getWidth()-22,(int)(mouseX*webContainer.getWidth()-11)));
            int y=Math.max(0,Math.min(webContainer.getHeight()-22,(int)(mouseY*webContainer.getHeight()-11)));
            FrameLayout.LayoutParams lp=(FrameLayout.LayoutParams)mouseCursor.getLayoutParams();
            lp.leftMargin=x; lp.topMargin=y; mouseCursor.setLayoutParams(lp);
            mouseCursor.bringToFront();
        });
    }

    private void moveMousePointer(float dx,float dy,float padW,float padH){
        float scale=1.7f;
        mouseX=Math.max(0.0f,Math.min(1.0f,mouseX+(dx/Math.max(1f,padW))*scale));
        mouseY=Math.max(0.0f,Math.min(1.0f,mouseY+(dy/Math.max(1f,padH))*scale));
        updateMouseCursor();
    }

    private boolean mouseMovePending=false;
    private void dispatchMouseMoveSmooth(){
        if(mouseMovePending) return;
        mouseMovePending=true;
        if(mouseCursor!=null) mouseCursor.postOnAnimation(()->{
            mouseMovePending=false;
            dispatchMouseEvent("mousemove");
        });
    }

    private void dispatchMouseClick(){
        WebView w=getWeb();
        if(w==null || w.getWidth()<=0 || w.getHeight()<=0) return;
        final float nx=mouseX, ny=mouseY;
        String js="(function(){try{"+
                "var vw=Math.max(1,window.innerWidth),vh=Math.max(1,window.innerHeight);"+
                "var x=Math.max(0,Math.min(vw-1,"+(nx)+"*vw));"+
                "var y=Math.max(0,Math.min(vh-1,"+(ny)+"*vh));"+
                "var el=document.elementFromPoint(x,y);"+
                "if(!el)return false;"+
                "['pointerdown','mousedown','pointerup','mouseup'].forEach(function(t){"+
                "var isUp=(t==='pointerup'||t==='mouseup'||t==='click');"+
                "var ev;if(t.indexOf('pointer')===0){ev=new PointerEvent(t,{bubbles:true,cancelable:true,clientX:x,clientY:y,button:0,buttons:isUp?0:1,pointerId:1,pointerType:'mouse'});}"+
                "else{ev=new MouseEvent(t,{bubbles:true,cancelable:true,view:window,clientX:x,clientY:y,button:0,buttons:isUp?0:1});}"+
                "el.dispatchEvent(ev);});"+
                "if(typeof el.click==='function')el.click(); return true;"+
                "}catch(e){return false;}})()";
        w.evaluateJavascript(js,null);
    }

    private void dispatchMouseEvent(String type){
        WebView w=getWeb();
        if(w==null) return;
        final float nx=mouseX, ny=mouseY;
        String js="(function(){try{"+
                "var x=Math.max(0,Math.min(window.innerWidth-1,"+nx+"*window.innerWidth));"+
                "var y=Math.max(0,Math.min(window.innerHeight-1,"+ny+"*window.innerHeight));"+
                "var el=document.elementFromPoint(x,y);"+
                "if(!el)return false;"+
                "var ev=new MouseEvent('"+type+"',{bubbles:true,cancelable:true,view:window,clientX:x,clientY:y,button:0,buttons:"+(type.equals("mouseup")||type.equals("click")?0:1)+"});"+
                "el.dispatchEvent(ev);"+
                "if('"+type+"'==='click'&&typeof el.click==='function')el.click();"+
                "return true;}catch(e){return false;}})()";
        w.evaluateJavascript(js,null);
    }

    private void zoomAtPointer(float dy){
        WebView w=getWeb();
        if(w==null || w.getWidth()<=0 || w.getHeight()<=0) return;
        if(Build.VERSION.SDK_INT>=21){
            float oldScale=w.getScale();
            int px=(int)(mouseX*w.getWidth()), py=(int)(mouseY*w.getHeight());
            int sx=w.getScrollX(), sy=w.getScrollY();
            w.zoomBy(dy<0 ? 1.18f : 0.85f);
            w.postDelayed(()->{
                float ns=w.getScale();
                if(oldScale<=0 || ns<=0) return;
                float ratio=ns/oldScale;
                w.scrollTo(Math.max(0,Math.round((sx+px)*ratio-px)), Math.max(0,Math.round((sy+py)*ratio-py)));
            },80);
        }
    }

    /** Saves the currently visible browser page as a good-quality JPEG screenshot. */
    private void saveWebViewScreenshotJpg(){
        final WebView w=getWeb();
        if(w==null){ showMessage4("صفحه‌ای برای گرفتن اسکرین‌شات وجود ندارد."); return; }
        w.post(()->{
            try{
                int width=w.getWidth(), height=w.getHeight();
                if(width<=0 || height<=0) throw new IllegalStateException();
                android.graphics.Bitmap b=android.graphics.Bitmap.createBitmap(width,height,android.graphics.Bitmap.Config.ARGB_8888);
                android.graphics.Canvas c=new android.graphics.Canvas(b);
                w.draw(c);
                String stamp=new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss",Locale.US).format(new Date());
                String name="FastBrowser_Screenshot_"+stamp+".jpg";
                if(Build.VERSION.SDK_INT>=29){
                    ContentValues v=new ContentValues();
                    v.put(MediaStore.Downloads.DISPLAY_NAME,name);
                    v.put(MediaStore.Downloads.MIME_TYPE,"image/jpeg");
                    v.put(MediaStore.Downloads.RELATIVE_PATH,Environment.DIRECTORY_DOWNLOADS);
                    Uri u=getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,v);
                    if(u==null) throw new IllegalStateException();
                    try(OutputStream os=getContentResolver().openOutputStream(u)){
                        if(os==null) throw new IllegalStateException();
                        if(!b.compress(android.graphics.Bitmap.CompressFormat.JPEG,94,os)) throw new IllegalStateException();
                    }
                }else{
                    java.io.File dir=Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                    if(!dir.exists() && !dir.mkdirs()) throw new IllegalStateException();
                    java.io.File out=new java.io.File(dir,name);
                    try(java.io.FileOutputStream os=new java.io.FileOutputStream(out)){
                        if(!b.compress(android.graphics.Bitmap.CompressFormat.JPEG,94,os)) throw new IllegalStateException();
                    }
                }
                b.recycle();
                showMessage4("اسکرین‌شات JPG با کیفیت خوب ذخیره شد: "+name);
            }catch(Exception e){
                showMessage4("ذخیره اسکرین‌شات ناموفق بود.");
            }
        });
    }

    /** Save the current page in a user-selected text/document format. */
    private void showSavePageDialog(){
        final String[] formats={"TXT — متن صفحه","HTML — صفحه کامل","XML — ساختار صفحه","JSON — اطلاعات صفحه"};
        new AlertDialog.Builder(this)
                .setTitle("Save Page")
                .setItems(formats,(d,which)->saveCurrentPageAs(which))
                .show();
    }

    private void saveCurrentPageAs(int format){
        if(tabs.isEmpty() || tabs.get(currentTab).web==null){
            showMessage4("صفحه‌ای برای ذخیره وجود ندارد.");
            return;
        }
        final WebView w=tabs.get(currentTab).web;
        final String urlValue=w.getUrl()==null?"":w.getUrl();
        final String titleValue=w.getTitle()==null?"FastBrowser_Page":w.getTitle();
        final String[] extensions={"txt","html","xml","json"};
        final String ext=extensions[Math.max(0,Math.min(format,extensions.length-1))];
        final String safeBase=safeFilePart(titleValue);
        final String stamp=new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss",Locale.US).format(new Date());
        final String fileName="FastBrowser_"+safeBase+"_"+stamp+"."+ext;

        String js;
        if(format==0){
            js="(function(){try{return document.body?document.body.innerText:(document.documentElement?document.documentElement.innerText:'')}catch(e){return ''}})()";
        }else if(format==1 || format==2){
            js="(function(){try{return document.documentElement?document.documentElement.outerHTML:''}catch(e){return ''}})()";
        }else if(format==3){
            js="(function(){try{return JSON.stringify({url:location.href,title:document.title,text:(document.body?document.body.innerText:''),html:(document.documentElement?document.documentElement.outerHTML:'')})}catch(e){return '{}'}})()";
        }else{
            // MHTML is saved as a standards-friendly single document containing the current HTML.
            js="(function(){try{return document.documentElement?document.documentElement.outerHTML:''}catch(e){return ''}})()";
        }
        w.evaluateJavascript(js, value->{
            String content=decodeJavascriptString(value);
            if(format==2){
                content="<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                        +"<saved-page>\n"
                        +"  <url>"+escapeXml(urlValue)+"</url>\n"
                        +"  <title>"+escapeXml(titleValue)+"</title>\n"
                        +"  <html>"+escapeXml(content)+"</html>\n"
                        +"</saved-page>\n";
            }else if(format==3 && (content==null || content.trim().isEmpty())){
                content="{}";
            }
            writePageFile(fileName,content,ext);
        });
    }

    private String decodeJavascriptString(String value){
        if(value==null) return "";
        String v=value.trim();
        // evaluateJavascript normally returns a JSON string.
        if(v.length()>=2 && v.charAt(0)=='"'){
            try{
                Object parsed=new org.json.JSONTokener(v).nextValue();
                if(parsed instanceof String) v=(String)parsed;
            }catch(Exception ignored){}
        }
        // Some page scripts return an object containing a text field.
        if(v.startsWith("{")){
            try{
                org.json.JSONObject o=new org.json.JSONObject(v);
                String text=o.optString("text", null);
                if(text!=null) v=text;
            }catch(Exception ignored){}
        }
        v=v.replace("\\/","/");
        java.util.regex.Matcher m=java.util.regex.Pattern.compile("\\\\u([0-9a-fA-F]{4})").matcher(v);
        StringBuffer out=new StringBuffer();
        while(m.find()){
            m.appendReplacement(out, java.util.regex.Matcher.quoteReplacement(
                    String.valueOf((char)Integer.parseInt(m.group(1),16))));
        }
        m.appendTail(out);
        return out.toString().trim();
    }

    /** Shows a temporary browser message and hides it automatically after 4 seconds. */
    private void showMessage4(String message){
        if(isFinishing() || (android.os.Build.VERSION.SDK_INT >= 17 && isDestroyed())) return;
        final android.widget.Toast toast = android.widget.Toast.makeText(this, message == null ? "" : message, android.widget.Toast.LENGTH_LONG);
        toast.show();
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(toast::cancel, 4000L);
    }

    private String escapeXml(String s){
        if(s==null) return "";
        return s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;")
                .replace("\"","&quot;").replace("'","&apos;");
    }

    private String safeFilePart(String s){
        String x=s==null?"Page":s.replaceAll("[\\\\/:*?\"<>|\\r\\n\\t]","_").trim();
        if(x.isEmpty()) x="Page";
        return x.length()>50?x.substring(0,50):x;
    }

    private void writePageFile(String name,String content,String ext){
        try{
            byte[] bytes=(content==null?"":content).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            String mime;
            if("txt".equals(ext)) mime="text/plain";
            else if("html".equals(ext)) mime="text/html";
            else if("xml".equals(ext)) mime="application/xml";
            else mime="application/json";
            if(Build.VERSION.SDK_INT>=29){
                ContentValues v=new ContentValues();
                v.put(MediaStore.Downloads.DISPLAY_NAME,name);
                v.put(MediaStore.Downloads.MIME_TYPE,mime);
                v.put(MediaStore.Downloads.RELATIVE_PATH,Environment.DIRECTORY_DOWNLOADS);
                Uri u=getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,v);
                if(u==null) throw new IllegalStateException("insert failed");
                try(OutputStream os=getContentResolver().openOutputStream(u)){
                    if(os==null) throw new IllegalStateException("output failed");
                    os.write(bytes);
                }
            }else{
                java.io.File dir=Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                if(!dir.exists()&&!dir.mkdirs()) throw new IllegalStateException("mkdir failed");
                java.io.File out=new java.io.File(dir,name);
                try(java.io.FileOutputStream os=new java.io.FileOutputStream(out)){os.write(bytes);}
            }
            showMessage4("صفحه با فرمت ."+ext+" ذخیره شد.");
        }catch(Exception e){
            showMessage4("ذخیره صفحه ناموفق بود: "+e.getMessage());
        }
    }

    private void showTools(){
        final String[] tools={"Bookmarks","History","Downloads","Incognito","Find in Page","Full Screen","Night Mode","No Images","Search Page","Snapshots","Save Page","Add Bookmark","Share","Add to Desktop","Desktop Mode","Website Settings","Ad Block","Radio","Widgets","Settings","Quit"};
        new AlertDialog.Builder(this).setTitle("Tool").setItems(tools,(d,w)->{
            switch(w){
                case 0: showBookmarks(); break;
                case 1: showHistory(); break;
                case 2: showDownloadWindow(); break;
                case 3: toggleIncognito(); break;
                case 4: findInPage(); break;
                case 5: toggleFullScreen(); break;
                case 6: toggleNightMode(); break;
                case 7: toggleNoImages(); break;
                case 8: searchPage(); break;
                case 9: saveWebViewScreenshotJpg(); break;
                case 10: showSavePageDialog(); break;
                case 11: addBookmark(); break;
                case 12: sharePage(); break;
                case 13: addToDesktop(); break;
                case 14: toggleDesktopMode(); break;
                case 15: showWebsiteSettings(); break;
                case 16: toggleAdBlock(); break;
                case 17: showRadioWindow(); break;
                case 18: showWidgetManager(); break;
                case 19: showSetups(); break;
                case 20: finish(); break;
            }
        }).show();
    }

    private static final String WIDGET_DEFAULTS =
            "Common Ninja|https://www.commoninja.com/widgets\n"+
            "Elfsight|https://elfsight.com/html-widgets/\n"+
            "POWR|https://www.powr.io/plugins\n"+
            "SociableKIT|https://www.sociablekit.com/widgets/";

    private ArrayList<String[]> getWidgetSources(){
        ArrayList<String[]> out=new ArrayList<>();
        String saved=prefs.getString("widget_sources", "");
        String data=saved.trim().isEmpty()?WIDGET_DEFAULTS:saved;
        for(String line:data.split("\\n")){
            String[] a=line.split("\\|",2);
            if(a.length==2 && !a[0].trim().isEmpty() && !a[1].trim().isEmpty()) out.add(new String[]{a[0].trim(),a[1].trim()});
        }
        return out;
    }

    private void saveWidgetSources(ArrayList<String[]> list){
        StringBuilder b=new StringBuilder();
        for(String[] a:list){
            if(a[0].contains("\\n")||a[0].contains("|")||a[1].contains("\\n")||a[1].contains("|")) continue;
            if(b.length()>0)b.append('\n'); b.append(a[0]).append('|').append(a[1]);
        }
        prefs.edit().putString("widget_sources",b.toString()).apply();
    }

    private void showWidgetManager(){
        ArrayList<String[]> list=getWidgetSources();
        String[] names=new String[list.size()+2];
        for(int i=0;i<list.size();i++) names[i]=list.get(i)[0];
        names[list.size()]="+ Add widget source";
        names[list.size()+1]="Manage / remove sources";
        new AlertDialog.Builder(this).setTitle("Widgets").setItems(names,(d,w)->{
            if(w<list.size()) showWidgetSourceActions(list.get(w)[0],list.get(w)[1]);
            else if(w==list.size()) addWidgetSource();
            else manageWidgetSources();
        }).show();
    }

    private void showWidgetSourceActions(String name,String source){
        String[] actions={"Apply to browser","Open widget source","Save source"};
        new AlertDialog.Builder(this).setTitle(name).setItems(actions,(d,w)->{
            if(w==0) applyWidgetOverlay(name,source);
            else if(w==1) getWeb().loadUrl(source);
            else showMessage4("منبع از قبل ذخیره است.");
        }).show();
    }

    /** Loads a widget resource into a small movable browser overlay. This keeps the current tab intact. */
    private void applyWidgetOverlay(String name,String source){
        final Dialog dialog=new Dialog(this);
        dialog.getWindow();
        FrameLayout root=new FrameLayout(this);
        root.setBackgroundColor(Color.WHITE);
        TextView title=new TextView(this);
        title.setText(name+"  ×"); title.setTextSize(12); title.setTextColor(Color.DKGRAY); title.setGravity(Gravity.CENTER_VERTICAL);
        title.setPadding(10,0,10,0);
        GradientDrawable barBg=new GradientDrawable(); barBg.setColor(0xFFE8EEF5); title.setBackground(barBg);
        WebView widget=new WebView(this); configureWeb(widget); widget.loadUrl(source);
        root.addView(widget,new FrameLayout.LayoutParams(-1,-1));
        FrameLayout.LayoutParams tp=new FrameLayout.LayoutParams(-1,40,Gravity.TOP); root.addView(title,tp);
        dialog.setContentView(root);
        Window win=dialog.getWindow();
        if(win!=null){ win.setBackgroundDrawableResource(android.R.color.transparent); win.setLayout(-1,-2); }
        title.setOnClickListener(v->dialog.dismiss());
        dialog.setOnShowListener(x->{ Window w=dialog.getWindow(); if(w!=null){w.setLayout((int)(getResources().getDisplayMetrics().widthPixels*.92f),(int)(getResources().getDisplayMetrics().heightPixels*.58f)); w.setGravity(Gravity.CENTER);}});
        dialog.show();
    }

    private void addWidgetSource(){
        LinearLayout box=new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL); box.setPadding(24,8,24,0);
        EditText name=new EditText(this); name.setHint("Widget source name");
        EditText link=new EditText(this); link.setHint("https://..."); link.setSingleLine(true); link.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_VARIATION_URI);
        box.addView(name); box.addView(link);
        new AlertDialog.Builder(this).setTitle("Add widget source").setView(box).setPositiveButton("Add",(d,w)->{
            String n=name.getText().toString().trim(), u=link.getText().toString().trim();
            if(n.isEmpty()||u.isEmpty()||!(u.startsWith("http://")||u.startsWith("https://"))){showMessage4("نام و لینک معتبر وارد کنید.");return;}
            ArrayList<String[]> list=getWidgetSources(); list.add(new String[]{n,u}); saveWidgetSources(list); showMessage4("منبع ابزارک ذخیره شد.");
        }).setNegativeButton("Cancel",null).show();
    }

    private void manageWidgetSources(){
        ArrayList<String[]> list=getWidgetSources();
        if(list.isEmpty()){showMessage4("منبعی وجود ندارد.");return;}
        String[] names=new String[list.size()]; for(int i=0;i<list.size();i++) names[i]=list.get(i)[0]+"\n"+list.get(i)[1];
        new AlertDialog.Builder(this).setTitle("Remove widget source").setItems(names,(d,w)->{list.remove(w);saveWidgetSources(list);showMessage4("منبع حذف شد.");}).setNegativeButton("Cancel",null).show();
    }

    private void showSetups(){
        String[] items={
                "Bookmarks / History",
                "Incognito mode: "+(incognitoMode?"ON":"OFF"),
                "Ad Block: "+(adBlockEnabled?"ON":"OFF"),
                "Night Mode: "+(nightMode?"ON":"OFF"),
                "No Images: "+(noImagesMode?"ON":"OFF"),
                "PC mode: "+(desktopMode?"ON":"OFF"),
                "Browser DNS (Primary / Secondary)",
                "TLS / Tunnel link",
                "Clean",
                "Clear browsing data"
        };
        new AlertDialog.Builder(this).setTitle("Setups").setItems(items,(d,w)->{
            switch(w){
                case 0: showBookmarks(); break;
                case 1: toggleIncognito(); break;
                case 2: toggleAdBlock(); break;
                case 3: toggleNightMode(); break;
                case 4: toggleNoImages(); break;
                case 5: toggleDesktopMode(); break;
                case 6: showDnsSettings(); break;
                case 7: showTlsTunnelSettings(); break;
                case 8: showCleanDialog(); break;
                case 9: clearBrowsingData(); break;
            }
        }).show();
    }

    private void showTlsTunnelSettings(){
        final EditText link=new EditText(this);
        link.setSingleLine(true);
        link.setHint("tls://, tunnel://, vless://, vmess://, trojan://, ss:// ...");
        link.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        link.setText(prefs.getString("tunnel_link", ""));
        LinearLayout box=new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL); box.setPadding(28,8,28,0);
        box.addView(link,new LinearLayout.LayoutParams(-1,56));
        new AlertDialog.Builder(this).setTitle("TLS / Tunnel")
                .setMessage("لینک‌های TLS و Tunnel را ذخیره و به برنامه‌ای که آن نوع لینک را پشتیبانی می‌کند منتقل کنید. مرورگر ادعای اجرای داخلی پروتکل‌های تونل را بدون موتور مربوطه نمی‌کند.")
                .setView(box)
                .setNegativeButton("Cancel",null)
                .setNeutralButton("Saved links",(d,w)->showSavedTunnelLinks())
                .setPositiveButton("Save & Open",(d,w)->{
                    String u=link.getText().toString().trim();
                    if(!isTunnelUri(u)){ showMessage4("Unsupported tunnel/TLS link"); return; }
                    prefs.edit().putString("tunnel_link",u).apply();
                    openTunnelUri(u);
                }).show();
    }

    private boolean isTunnelUri(String u){
        if(u==null||u.isEmpty()) return false;
        String x=u.toLowerCase(Locale.US);
        String[] schemes={"tls://","tunnel://","vless://","vmess://","trojan://","ss://","ssconf://","hysteria://","hysteria2://","hy2://","tuic://","wireguard://"};
        for(String q:schemes) if(x.startsWith(q)) return true;
        return false;
    }

    private void openTunnelUri(String u){
        try{
            Intent i=new Intent(Intent.ACTION_VIEW, Uri.parse(u));
            startActivity(i);
        }catch(Exception e){
            showMessage4("No installed app can handle this tunnel link");
        }
    }

    private void showSavedTunnelLinks(){
        String u=prefs.getString("tunnel_link","");
        if(u.isEmpty()){ showMessage4("No saved TLS/Tunnel link"); return; }
        new AlertDialog.Builder(this).setTitle("Saved TLS / Tunnel link")
                .setMessage(u)
                .setNegativeButton("Close",null)
                .setNeutralButton("Delete",(d,w)->prefs.edit().remove("tunnel_link").apply())
                .setPositiveButton("Open",(d,w)->openTunnelUri(u)).show();
    }

    private void showDnsSettings(){
        final EditText primary = new EditText(this);
        primary.setSingleLine(true); primary.setHint("Primary DNS (IPv4)");
        primary.setInputType(android.text.InputType.TYPE_CLASS_PHONE);
        primary.setText(prefs.getString("dns_primary", "1.1.1.1"));
        final EditText secondary = new EditText(this);
        secondary.setSingleLine(true); secondary.setHint("Secondary DNS (IPv4)");
        secondary.setInputType(android.text.InputType.TYPE_CLASS_PHONE);
        secondary.setText(prefs.getString("dns_secondary", "8.8.8.8"));
        LinearLayout box=new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL); box.setPadding(28,8,28,0);
        box.addView(primary,new LinearLayout.LayoutParams(-1,48));
        box.addView(secondary,new LinearLayout.LayoutParams(-1,48));
        boolean enabled=prefs.getBoolean("dns_enabled",false);
        new AlertDialog.Builder(this).setTitle("Browser DNS")
                .setMessage("این DNS فقط برای مسیر DNS مرورگر تنظیم می‌شود؛ ترافیک عادی وب از شبکه معمولی عبور می‌کند.")
                .setView(box)
                .setNegativeButton("Cancel",null)
                .setNeutralButton(enabled?"Stop DNS":"Disable",(d,w)->stopBrowserDns())
                .setPositiveButton(enabled?"Apply":"Save & Start",(d,w)->{
                    String p=primary.getText().toString().trim(), s=secondary.getText().toString().trim();
                    if(!validIpv4(p) || (!s.isEmpty() && !validIpv4(s))){ showMessage4("Enter valid IPv4 DNS addresses"); return; }
                    if(s.isEmpty()) s=null;
                    prefs.edit().putString("dns_primary",p).putString("dns_secondary",s==null?"":s).apply();
                    requestAndStartDns(p,s);
                }).show();
    }

    private void requestAndStartDns(String primary,String secondary){
        Intent prep=android.net.VpnService.prepare(this);
        if(prep!=null){
            pendingDnsPrimary=primary; pendingDnsSecondary=secondary;
            startActivityForResult(prep,REQ_DNS_VPN);
        }else startDnsService(primary,secondary);
    }
    private String pendingDnsPrimary, pendingDnsSecondary;
    private void startDnsService(String primary,String secondary){
        Intent i=new Intent(this,DnsVpnService.class).setAction(DnsVpnService.ACTION_START)
                .putExtra(DnsVpnService.EXTRA_PRIMARY,primary).putExtra(DnsVpnService.EXTRA_SECONDARY,secondary);
        startService(i);
        prefs.edit().putBoolean("dns_enabled",true).apply();
        showMessage4("Browser DNS enabled");
    }
    private void stopBrowserDns(){
        try{ startService(new Intent(this,DnsVpnService.class).setAction(DnsVpnService.ACTION_STOP)); }catch(Exception ignored){}
        prefs.edit().putBoolean("dns_enabled",false).apply();
        showMessage4("Browser DNS disabled");
    }
    private static boolean validIpv4(String s){
        if(s==null||s.isEmpty())return false; String[] a=s.split("\\."); if(a.length!=4)return false;
        try{for(String x:a){int v=Integer.parseInt(x);if(v<0||v>255)return false;}return true;}catch(Exception e){return false;}
    }

    private void toggleDesktopMode(){
        desktopMode=!desktopMode;
        prefs.edit().putBoolean("desktop_mode",desktopMode).apply();
        applyModesToWeb(getWeb());
        getWeb().reload();
        showMessage4(desktopMode?"PC mode ON":"Phone mode ON");
    }

    private void toggleNightMode(){
        nightMode=!nightMode;
        prefs.edit().putBoolean("night_mode",nightMode).apply();
        applyModesToWeb(getWeb());
        showMessage4(nightMode?"Night Mode ON":"Night Mode OFF");
    }

    private void toggleNoImages(){
        noImagesMode=!noImagesMode;
        prefs.edit().putBoolean("no_images",noImagesMode).apply();
        applyModesToWeb(getWeb());
        getWeb().reload();
        showMessage4(noImagesMode?"Images OFF":"Images ON");
    }

    private void toggleAdBlock(){
        adBlockEnabled=!adBlockEnabled;
        prefs.edit().putBoolean("ad_block",adBlockEnabled).apply();
        getWeb().reload();
        showMessage4(adBlockEnabled?"Ad Block ON":"Ad Block OFF");
    }

    private void toggleIncognito(){
        incognitoMode=!incognitoMode;
        if(incognitoMode){
            getWeb().clearHistory(); getWeb().clearCache(true);
            showMessage4("Incognito ON — this session is not saved");
        }else showMessage4("Incognito OFF");
    }

    private void toggleFullScreen(){
        int flags=getWindow().getDecorView().getSystemUiVisibility();
        boolean on=(flags & View.SYSTEM_UI_FLAG_FULLSCREEN)!=0;
        getWindow().getDecorView().setSystemUiVisibility(on?0:(View.SYSTEM_UI_FLAG_FULLSCREEN|View.SYSTEM_UI_FLAG_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY));
    }

    private void applyModesToWeb(WebView w){
        if(w==null) return;
        WebSettings s=w.getSettings();
        s.setUserAgentString(desktopMode ? "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140 Safari/537.36" : WebSettings.getDefaultUserAgent(this));
        s.setLoadsImagesAutomatically(!noImagesMode);
        if(WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK)) WebSettingsCompat.setForceDark(s, nightMode?WebSettingsCompat.FORCE_DARK_ON:WebSettingsCompat.FORCE_DARK_OFF);
        w.setBackgroundColor(nightMode?Color.rgb(28,28,28):Color.WHITE);
    }

    private void showBookmarks(){
        ArrayList<String> values=readStringList("bookmarks");
        if(values.isEmpty()){ new AlertDialog.Builder(this).setTitle("Bookmarks").setMessage("No bookmarks yet.").setPositiveButton("OK",null).show(); return; }
        new AlertDialog.Builder(this).setTitle("Bookmarks").setItems(values.toArray(new String[0]),(d,w)->load(values.get(w).split("\\n",2).length>1 ? values.get(w).split("\\n",2)[1] : values.get(w))).setNegativeButton("Close",null).show();
    }

    private void addBookmark(){
        String u=getWeb().getUrl(); if(!isHttpUrl(u)){showMessage4("No webpage to bookmark");return;}
        String title=getWeb().getTitle(); if(TextUtils.isEmpty(title)) title=u;
        ArrayList<String> values=readStringList("bookmarks");
        values.removeIf(x->x.endsWith("\n"+u) || x.equals(u));
        values.add(0,title+"\n"+u);
        writeStringList("bookmarks",values);
        showMessage4("Bookmark added");
    }

    private void showHistory(){
        ArrayList<String> values=readStringList("history");
        if(values.isEmpty()){ new AlertDialog.Builder(this).setTitle("History").setMessage("No history yet.").setPositiveButton("OK",null).show(); return; }
        new AlertDialog.Builder(this).setTitle("History").setItems(values.toArray(new String[0]),(d,w)->{
            String[] a=values.get(w).split("\\n",2); if(a.length>1) load(a[1]);
        }).setNegativeButton("Clear",(d,w)->{prefs.edit().remove("history").apply();}).show();
    }

    private void addHistoryEntry(String u,String title){
        if(!isHttpUrl(u)) return;
        ArrayList<String> values=readStringList("history");
        String entry=(TextUtils.isEmpty(title)?u:title)+"\n"+u;
        values.removeIf(x->x.endsWith("\n"+u)); values.add(0,entry);
        while(values.size()>100) values.remove(values.size()-1);
        writeStringList("history",values);
    }

    private ArrayList<String> readStringList(String key){
        ArrayList<String> out=new ArrayList<>();
        String raw=prefs.getString(key,""); if(raw.isEmpty()) return out;
        try{ org.json.JSONArray a=new org.json.JSONArray(raw); for(int i=0;i<a.length();i++) out.add(a.optString(i)); }catch(Exception ignored){}
        return out;
    }
    private void writeStringList(String key,ArrayList<String> values){
        org.json.JSONArray a=new org.json.JSONArray(); for(String x:values)a.put(x); prefs.edit().putString(key,a.toString()).apply();
    }

    private void showCleanDialog(){
        final String[] labels={"Cache","Cookies / site data","History","Saved last page"};
        final boolean[] checked={true,true,true,false};
        new AlertDialog.Builder(this)
                .setTitle("Clean")
                .setMultiChoiceItems(labels,checked,(d,which,isChecked)->checked[which]=isChecked)
                .setNegativeButton("Cancel",null)
                .setPositiveButton("Clean now",(d,w)->{
                    if(checked[0]) for(Tab t:tabs) t.web.clearCache(true);
                    if(checked[1]) { CookieManager.getInstance().removeAllCookies(null); CookieManager.getInstance().flush(); }
                    if(checked[2]) { for(Tab t:tabs) t.web.clearHistory(); prefs.edit().remove("history").apply(); }
                    if(checked[3]) prefs.edit().remove("last").apply();
                    showMessage4("Clean completed");
                }).show();
    }

    private void clearBrowsingData(){
        for(Tab t:tabs){t.web.clearHistory();t.web.clearCache(true);}
        CookieManager.getInstance().removeAllCookies(null); CookieManager.getInstance().flush();
        prefs.edit().remove("history").remove("last").apply();
        showMessage4("Browsing data cleared");
    }

    private void addToDesktop(){
        String u=getWeb().getUrl(); if(!isHttpUrl(u)){showMessage4("No webpage to add");return;}
        String title=TextUtils.isEmpty(getWeb().getTitle())?u:getWeb().getTitle();
        if(Build.VERSION.SDK_INT>=26){
            try{
                android.content.pm.ShortcutManager sm=getSystemService(android.content.pm.ShortcutManager.class);
                if(sm!=null && sm.isRequestPinShortcutSupported()){
                    android.content.pm.ShortcutInfo si=new android.content.pm.ShortcutInfo.Builder(this,"fb_"+Math.abs(u.hashCode()))
                            .setShortLabel(title.length()>20?title.substring(0,20):title)
                            .setLongLabel(title).setIntent(new Intent(Intent.ACTION_VIEW,Uri.parse(u))).build();
                    sm.requestPinShortcut(si,null);
                    showMessage4("Shortcut request sent to launcher"); return;
                }
            }catch(Exception ignored){}
        }
        try{
            Intent shortcut=new Intent("com.android.launcher.action.INSTALL_SHORTCUT");
            shortcut.putExtra(Intent.EXTRA_SHORTCUT_NAME,title);
            shortcut.putExtra(Intent.EXTRA_SHORTCUT_INTENT,new Intent(Intent.ACTION_VIEW,Uri.parse(u)));
            shortcut.putExtra("duplicate",false);
            sendBroadcast(shortcut);
            showMessage4("Desktop shortcut request sent");
        }catch(Exception e){
            try{startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(u)));}catch(Exception ignored){showMessage4("Desktop shortcut is not supported here");}
        }
    }

    private void showWebsiteSettings(){
        WebSettings s=getWeb().getSettings();
        String[] items={"JavaScript: "+(s.getJavaScriptEnabled()?"ON":"OFF"),"Cookies: ON","Media without tap: "+(s.getMediaPlaybackRequiresUserGesture()?"OFF":"ON"),"Wide viewport: "+(s.getUseWideViewPort()?"ON":"OFF")};
        new AlertDialog.Builder(this).setTitle("Website Settings").setItems(items,(d,w)->{
            if(w==0){s.setJavaScriptEnabled(!s.getJavaScriptEnabled());getWeb().reload();}
            else if(w==2){s.setMediaPlaybackRequiresUserGesture(!s.getMediaPlaybackRequiresUserGesture());}
        }).show();
    }

    private boolean isBlockedAdUrl(String u){
        if(TextUtils.isEmpty(u)) return false;
        String l=u.toLowerCase(Locale.US);
        String[] p={"doubleclick.net","googlesyndication.com","googleadservices.com","adservice.google.com","adnxs.com","advertising.com","adsrvr.org","taboola.com","outbrain.com","criteo.com","amazon-adsystem.com","scorecardresearch.com","zedo.com","/ads/","/adserver","/advert","/banner/","/vast/","/tracking/","/pixel.gif"};
        for(String x:p) if(l.contains(x)) return true;
        return false;
    }
    private WebResourceResponse emptyResponse(){ return new WebResourceResponse("text/plain","UTF-8",new ByteArrayInputStream(new byte[0])); }

    /**
     * Small independent radio window. Playback belongs to RadioPlaybackService,
     * so closing this dialog or closing the browser Activity does not stop audio.
     */
    private void showRadioWindow(){
        if(radioDialog!=null && radioDialog.isShowing()) return;
        LinearLayout root=new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(14,10,14,8);
        root.setBackgroundColor(Color.rgb(255,250,225));

        TextView status=new TextView(this);
        status.setText("Radio • آماده پخش"); status.setTextSize(13); status.setPadding(2,2,2,8);
        root.addView(status,new LinearLayout.LayoutParams(-1,-2));

        EditText urlBox=new EditText(this);
        urlBox.setSingleLine(true); urlBox.setTextSize(12); urlBox.setHint("Stream URL / HLS / DASH / HTML page / M3U8");
        root.addView(urlBox,new LinearLayout.LayoutParams(-1,44));

        EditText titleBox=new EditText(this);
        titleBox.setSingleLine(true); titleBox.setTextSize(12); titleBox.setHint("Radio name (optional)");
        root.addView(titleBox,new LinearLayout.LayoutParams(-1,42));

        CheckBox low=new CheckBox(this); low.setText("حالت کم‌حجم / سرور کاهنده"); low.setTextSize(12);
        root.addView(low,new LinearLayout.LayoutParams(-1,40));

        EditText relay=new EditText(this); relay.setSingleLine(true); relay.setTextSize(11);
        relay.setHint("URL سرور کاهنده (اختیاری؛ Liquidsoap/Icecast)");
        relay.setVisibility(View.GONE);
        root.addView(relay,new LinearLayout.LayoutParams(-1,40));
        low.setOnCheckedChangeListener((b,checked)->relay.setVisibility(checked?View.VISIBLE:View.GONE));

        LinearLayout buttons=new LinearLayout(this); buttons.setGravity(Gravity.CENTER);
        TextView play=new TextView(this); play.setText("▶ پخش"); play.setGravity(Gravity.CENTER); play.setTextSize(12); play.setPadding(18,9,18,9);
        TextView stop=new TextView(this); stop.setText("🛑 Stop"); stop.setGravity(Gravity.CENTER); stop.setTextSize(12); stop.setPadding(18,9,18,9);
        TextView close=new TextView(this); close.setText("بستن"); close.setGravity(Gravity.CENTER); close.setTextSize(12); close.setPadding(18,9,18,9);
        buttons.addView(play); buttons.addView(stop); buttons.addView(close); root.addView(buttons,new LinearLayout.LayoutParams(-1,48));

        TextView info=new TextView(this);
        info.setText("پخش در پس‌زمینه ادامه دارد؛ بستن این پنجره یا مرورگر پخش را متوقف نمی‌کند. فقط Stop آن را متوقف می‌کند.");
        info.setTextSize(10); info.setPadding(2,5,2,2); root.addView(info);

        radioDialog=new Dialog(this); radioDialog.requestWindowFeature(Window.FEATURE_NO_TITLE); radioDialog.setContentView(root);
        Window rw=radioDialog.getWindow();
        if(rw!=null){rw.setBackgroundDrawableResource(android.R.color.transparent);}
        radioDialog.setOnDismissListener(d->{radioDialog=null; if(radioResolverWeb!=null){radioResolverWeb.destroy();radioResolverWeb=null;}});
        play.setOnClickListener(v->{
            String entered=urlBox.getText().toString().trim();
            if(entered.isEmpty()){showMessage4("آدرس رادیو را وارد کنید");return;}
            String selected=entered;
            if(low.isChecked() && !relay.getText().toString().trim().isEmpty()) selected=relay.getText().toString().trim();
            String title=titleBox.getText().toString().trim(); if(title.isEmpty()) title="Fast Radio";
            if(isDirectRadioUrl(selected)){
                startRadioPlayback(selected,title); status.setText("Radio • در حال پخش: "+title);
            } else {
                resolveRadioHtml(selected,title,status);
            }
        });
        stop.setOnClickListener(v->{stopRadioPlayback();status.setText("Radio • متوقف شد");});
        close.setOnClickListener(v->radioDialog.dismiss());
        radioDialog.show();
        rw=radioDialog.getWindow(); if(rw!=null){DisplayMetrics dm=getResources().getDisplayMetrics(); int ww=(int)(dm.widthPixels*0.90f); int hh=Math.min((int)(dm.heightPixels*0.52f),620); rw.setLayout(ww,hh);}
    }

    private boolean isDirectRadioUrl(String u){
        String l=u.toLowerCase(Locale.US);
        return l.matches(".*\\.(m3u8|m3u|mp3|aac|m4a|ogg|oga|opus|wav|flac|webm|mpd)([?#].*)?$")
                || l.startsWith("http://") || l.startsWith("https://") && (l.contains("/stream") || l.contains("/listen") || l.contains("/radio") || l.contains("icecast") || l.contains("shoutcast"));
    }

    private void startRadioPlayback(String stream,String title){
        Intent i=new Intent(this,RadioPlaybackService.class).setAction(RadioPlaybackService.ACTION_PLAY)
                .putExtra(RadioPlaybackService.EXTRA_URL,stream).putExtra(RadioPlaybackService.EXTRA_TITLE,title);
        if(Build.VERSION.SDK_INT>=26) startForegroundService(i); else startService(i);
    }

    private void stopRadioPlayback(){
        Intent i=new Intent(this,RadioPlaybackService.class).setAction(RadioPlaybackService.ACTION_STOP);
        startService(i);
    }

    private void resolveRadioHtml(String page,String title,TextView status){
        if(!isHttpUrl(page)){showMessage4("آدرس معتبر نیست");return;}
        if(radioResolverWeb!=null) radioResolverWeb.destroy();
        radioResolverWeb=new WebView(this);
        WebSettings s=radioResolverWeb.getSettings(); s.setJavaScriptEnabled(true); s.setDomStorageEnabled(true); s.setMediaPlaybackRequiresUserGesture(false);
        radioResolverWeb.setVisibility(View.GONE);
        if(radioDialog!=null && radioDialog.getWindow()!=null){
            ViewGroup parent=(ViewGroup)radioDialog.getWindow().getDecorView();
            // Keep the resolver attached to the Activity decor, invisible to the user.
            addContentView(radioResolverWeb,new ViewGroup.LayoutParams(1,1));
        }
        radioResolverWeb.setWebViewClient(new WebViewClient(){
            @Override public void onPageFinished(WebView view,String url){
                String js="(function(){var a=[];document.querySelectorAll('audio,source,video').forEach(function(x){if(x.currentSrc)a.push(x.currentSrc);if(x.src)a.push(x.src);});if(window.performance&&performance.getEntriesByType)performance.getEntriesByType('resource').forEach(function(e){var n=e.name||'';if(/\\.(m3u8|mp3|aac|m4a|ogg|opus|wav|mpd)(\\?|#|$)/i.test(n))a.push(n);});return JSON.stringify(a.filter(function(x){return /^https?:/i.test(x);}));})()";
                view.evaluateJavascript(js,val->{
                    String raw=val==null?"":val; if(raw.startsWith("\"")&&raw.endsWith("\""))raw=raw.substring(1,raw.length()-1).replace("\\\"","\"").replace("\\\\","\\");
                    try{org.json.JSONArray a=new org.json.JSONArray(raw); if(a.length()>0){String stream=a.optString(0); startRadioPlayback(stream,title); status.setText("Radio • در حال پخش: "+title); showMessage4("Stream پیدا شد");}else status.setText("Radio • Stream صوتی پیدا نشد");}catch(Exception e){status.setText("Radio • تشخیص Stream ناموفق بود");}
                });
            }
        });
        radioResolverWeb.loadUrl(page);
        status.setText("Radio • در حال یافتن Stream...");
    }

    /**
     * Long-press handling is deliberately based on WebView's hit-test result.
     * This lets the browser catch normal links, images and media without
     * turning an ordinary press into a download or navigation action.
     */
    private boolean handleLongPress(WebView w){
        WebView.HitTestResult hit = w.getHitTestResult();
        if(hit == null) return false;
        int type = hit.getType();
        String extra = hit.getExtra();
        String page = w.getUrl();
        String ua = w.getSettings().getUserAgentString();

        if(type == WebView.HitTestResult.EDIT_TEXT_TYPE) {
            // Let Android/WebView keep its normal text-edit selection menu.
            return false;
        }

        if(type == WebView.HitTestResult.ANCHOR_TYPE || type == WebView.HitTestResult.SRC_ANCHOR_TYPE) {
            if(isHttpUrl(extra) && looksDownloadable(extra)) {
                showLongPressResourceMenu(w, extra, fileNameFor(extra, "file"), null, ua, page, "File");
                return true;
            }
        }
        if(type == WebView.HitTestResult.IMAGE_TYPE || type == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE) {
            if(isHttpUrl(extra)) {
                showLongPressImageMenu(w, extra, fileNameFor(extra, "image"), ua, page);
                return true;
            }
        }
        if(isHttpUrl(extra) && looksLikeVideoUrl(extra)) {
            detector.inspect(extra, page, "long-press");
            showLongPressResourceMenu(w, extra, fileNameFor(extra, "video"), "video/*", ua, page, "Video");
            return true;
        }
        if(isHttpUrl(extra) && looksDownloadable(extra)) {
            showLongPressResourceMenu(w, extra, fileNameFor(extra, "file"), null, ua, page, "File");
            return true;
        }
        return false;
    }

    private boolean looksLikeVideoUrl(String url){
        if(url==null) return false;
        String u=url.toLowerCase(Locale.US);
        int q=u.indexOf('?');
        if(q>=0) u=u.substring(0,q);
        return u.endsWith(".mp4") || u.endsWith(".webm") || u.endsWith(".m4v") ||
               u.endsWith(".mov") || u.endsWith(".mkv") || u.endsWith(".avi") ||
               u.endsWith(".flv") || u.endsWith(".3gp") || u.endsWith(".ts") ||
               u.endsWith(".m3u8") || u.endsWith(".mpd");
    }

    /**
     * Images are special: some sites deliberately do not expose a normal download
     * action.  A long press therefore offers a dated local copy as well as the
     * existing search/download actions.
     */
    private void showLongPressImageMenu(WebView w, String resourceUrl, String name, String ua, String referer){
        if(!isHttpUrl(resourceUrl)) return;
        String safeName=(name==null || name.trim().isEmpty()) ? "image" : name.trim();
        String type=resourceTypeFor(safeName,"image/*","Image");
        LinearLayout box=new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(20,8,20,4);
        TextView info=new TextView(this);
        info.setText("نام: "+safeName+"\nنوع: "+type+"\nیک کپی با تاریخ روز در پوشه Downloads ذخیره می‌شود.");
        info.setTextIsSelectable(true);
        info.setPadding(4,4,4,14);
        box.addView(info);
        AlertDialog d=new AlertDialog.Builder(this)
                .setTitle("عکس لمس‌شده")
                .setView(box)
                .setNegativeButton("انصراف",null)
                .setNeutralButton("جستجو بر اساس نام و نوع",null)
                .setPositiveButton("ذخیره کپی امروز",null).create();
        d.setOnShowListener(v->{
            d.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(x->{
                String target=activeEngine.searchUrl.replace("%s",Uri.encode(safeName+" "+type));
                d.dismiss(); load(target);
            });
            d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(x->{
                saveDatedImageCopy(w, resourceUrl, safeName, ua, referer);
                d.dismiss();
            });
        });
        d.show();
    }

    private String datedFileName(String original){
        String base=original==null?"image":original;
        int q=base.indexOf('?'); if(q>=0) base=base.substring(0,q);
        int h=base.indexOf('#'); if(h>=0) base=base.substring(0,h);
        // Saved screenshots/copies are always normalized to JPEG for smaller files
        // while keeping good visual quality.
        String ext="jpg";
        String stamp=new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss",Locale.US).format(new Date());
        return "FastBrowser_"+stamp+"."+ext;
    }

    /** First tries the real image URL through DownloadManager; if that is rejected,
     * asks the page to return the rendered image as a data URL and stores that copy. */
    private void saveDatedImageCopy(WebView w,String imageUrl,String original,String ua,String referer){
        final String outName=datedFileName(original);
        if(isHttpUrl(imageUrl)){
            try{
                DownloadManager dm=(DownloadManager)getSystemService(DOWNLOAD_SERVICE);
                DownloadManager.Request r=new DownloadManager.Request(Uri.parse(imageUrl));
                r.setMimeType("image/*");
                if(!TextUtils.isEmpty(ua)) r.addRequestHeader("User-Agent",ua);
                if(!TextUtils.isEmpty(referer)) r.addRequestHeader("Referer",referer);
                String cookies=CookieManager.getInstance().getCookie(imageUrl);
                if(!TextUtils.isEmpty(cookies)) r.addRequestHeader("Cookie",cookies);
                r.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS,outName);
                r.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                r.setAllowedOverMetered(true); r.setAllowedOverRoaming(true);
                dm.enqueue(r);
                showMessage4("کپی عکس در Downloads ذخیره شد: "+outName);
                return;
            }catch(Exception ignored){}
        }
        // Fallback for pages using a protected/blob/data-rendered image.
        String js="(function(){try{var imgs=[].slice.call(document.images);var p=document.elementFromPoint("+lastTouchX(w)+","+lastTouchY(w)+");var img=(p&&p.tagName==='IMG')?p:null;if(!img){for(var i=0;i<imgs.length;i++){var r=imgs[i].getBoundingClientRect();if(r.left<=window.innerWidth/2&&r.right>=window.innerWidth/2&&r.top<=window.innerHeight/2&&r.bottom>=window.innerHeight/2){img=imgs[i];break;}}}if(!img)return '';var c=document.createElement('canvas');c.width=img.naturalWidth||img.width;c.height=img.naturalHeight||img.height;var x=c.getContext('2d');x.drawImage(img,0,0,c.width,c.height);return c.toDataURL('image/jpeg',0.95);}catch(e){return '';}})()";
        w.evaluateJavascript(js,data->{
            String raw=decodeJavascriptString(data);
            if(raw.startsWith("data:image/")){
                saveDataImageToDownloads(raw,outName);
            }else{
                showMessage4("این سایت اجازه کپی مستقیم تصویر را نداد.");
            }
        });
    }

    private float lastTouchX(WebView w){ Object o=w.getTag(R.id.webContainer); return o instanceof float[] ? ((float[])o)[0] : w.getWidth()/2f; }
    private float lastTouchY(WebView w){ Object o=w.getTag(R.id.webContainer); return o instanceof float[] ? ((float[])o)[1] : w.getHeight()/2f; }

    private void saveDataImageToDownloads(String dataUrl,String outName){
        try{
            int comma=dataUrl.indexOf(',');
            if(comma<0) throw new IllegalArgumentException();
            byte[] bytes=android.util.Base64.decode(dataUrl.substring(comma+1),android.util.Base64.DEFAULT);
            if(Build.VERSION.SDK_INT>=29){
                ContentValues v=new ContentValues();
                v.put(MediaStore.Downloads.DISPLAY_NAME,outName);
                v.put(MediaStore.Downloads.MIME_TYPE,"image/jpeg");
                v.put(MediaStore.Downloads.RELATIVE_PATH,Environment.DIRECTORY_DOWNLOADS);
                Uri u=getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,v);
                if(u==null) throw new IllegalStateException();
                try(OutputStream os=getContentResolver().openOutputStream(u)){ if(os==null) throw new IllegalStateException(); os.write(bytes); }
            }else{
                java.io.File dir=Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                if(!dir.exists() && !dir.mkdirs()) throw new IllegalStateException();
                try(java.io.FileOutputStream os=new java.io.FileOutputStream(new java.io.File(dir,outName))){ os.write(bytes); }
            }
            showMessage4("کپی عکس ذخیره شد: "+outName);
        }catch(Exception e){ showMessage4("ذخیره کپی عکس ناموفق بود."); }
    }

    /** Long-press resource menu: search by file name/type or download. */
    private void showLongPressResourceMenu(WebView w, String resourceUrl, String name,
                                           String mime, String ua, String referer, String kind){
        if(!isHttpUrl(resourceUrl)) return;
        String safeName=(name==null || name.trim().isEmpty()) ? kind.toLowerCase(Locale.US) : name.trim();
        String type=resourceTypeFor(safeName,mime,kind);
        LinearLayout box=new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(20,8,20,4);
        TextView info=new TextView(this);
        info.setText("نام: "+safeName+"\nنوع: "+type);
        info.setTextIsSelectable(true);
        info.setPadding(4,4,4,14);
        box.addView(info);
        AlertDialog d=new AlertDialog.Builder(this)
                .setTitle("فایل لمس‌شده")
                .setView(box)
                .setNegativeButton("انصراف",null)
                .setNeutralButton("جستجو بر اساس نام و نوع",null)
                .setPositiveButton("دانلود",null).create();
        d.setOnShowListener(v->{
            d.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(x->{
                String target=activeEngine.searchUrl.replace("%s",Uri.encode(safeName+" "+type));
                d.dismiss(); load(target);
            });
            d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(x->{
                queueDownload(resourceUrl,safeName,mime,ua,referer,kind);
                d.dismiss();
            });
        });
        d.show();
    }

    private String resourceTypeFor(String name,String mime,String kind){
        if(mime!=null && !mime.trim().isEmpty() && !mime.equalsIgnoreCase("*/*")) return mime;
        String n=name==null?"":name.toLowerCase(Locale.US);
        int q=n.indexOf('?'); if(q>=0)n=n.substring(0,q);
        int h=n.indexOf('#'); if(h>=0)n=n.substring(0,h);
        int dot=n.lastIndexOf('.');
        if(dot>=0 && dot<n.length()-1) return kind+" / "+n.substring(dot+1).toUpperCase(Locale.US);
        return kind;
    }

    private boolean isHttpUrl(String u){
        return u != null && (u.startsWith("http://") || u.startsWith("https://"));
    }

    private boolean looksDownloadable(String u){
        String l=u.toLowerCase(Locale.US);
        return l.matches(".*\\.(mp4|webm|m4v|mov|mkv|avi|flv|f4v|3gp|3g2|ts|m2ts|mts|mpeg|mpg|ogv|wmv|asf|m3u8|mpd|mp3|m4a|aac|wav|flac|ogg|pdf|zip|rar|7z|apk|jpg|jpeg|png|gif|webp|bmp|txt|csv|doc|docx|xls|xlsx|ppt|pptx)([?#].*)?$")
                || l.contains("download=") || l.contains("attachment") || l.contains("/download/");
    }

    private String fileNameFor(String u, String fallback){
        try {
            String n=Uri.parse(u).getLastPathSegment();
            if(n!=null && !n.trim().isEmpty()) {
                n=n.replaceAll("[\\\\/:*?\\\"<>|]", "_");
                if(n.length()>120) n=n.substring(n.length()-120);
                return n;
            }
        } catch(Exception ignored) {}
        return fallback==null?"download":fallback;
    }

    private void queueDownload(String u, String name, String mime, String ua, String referer, String kind){
        if(!isHttpUrl(u)) return;
        if(name==null || name.isEmpty()) name="download";
        String key=u;
        if(downloadQueue.containsKey(key)) {
            showDownloadWindow();
            return;
        }
        downloadQueue.put(key, new DownloadItem(u, name, mime, ua, referer));
        saveDownloadQueue();
        showMessage4( kind+" added to Downloads");
        showDownloadWindow();
    }

    private void showDownloadWindow(){
        LinearLayout box=new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(12,4,12,4);
        if(downloadQueue.isEmpty()) {
            TextView empty=new TextView(this);
            empty.setText("No items are waiting. Long-press a link, image or video to add it here.");
            empty.setPadding(8,18,8,18);
            box.addView(empty);
        } else {
            ScrollView scroll=new ScrollView(this);
            LinearLayout list=new LinearLayout(this);
            list.setOrientation(LinearLayout.VERTICAL);
            for(DownloadItem item : new ArrayList<>(downloadQueue.values())) addDownloadRow(list,item);
            scroll.addView(list);
            box.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        }
        AlertDialog d=new AlertDialog.Builder(this)
                .setTitle("Downloads  ("+downloadQueue.size()+")")
                .setView(box)
                .setNegativeButton("Close",null)
                .setNeutralButton("Download all",null)
                .create();
        d.setOnShowListener(x -> {
            Button all=d.getButton(AlertDialog.BUTTON_NEUTRAL);
            all.setOnClickListener(v->{
                for(DownloadItem item : new ArrayList<>(downloadQueue.values())) startQueuedDownload(item);
                downloadQueue.clear();
                saveDownloadQueue();
                d.dismiss();
                showMessage4("All queued downloads started");
            });
        });
        d.show();
    }

    private void addDownloadRow(LinearLayout list, DownloadItem item){
        LinearLayout row=new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(6,9,6,9);
        TextView name=new TextView(this);
        name.setText(item.title); name.setTextSize(13); name.setSingleLine(false);
        TextView link=new TextView(this);
        link.setText(item.url); link.setTextSize(9); link.setTextIsSelectable(true);
        link.setMaxLines(2); link.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        LinearLayout actions=new LinearLayout(this);
        actions.setGravity(Gravity.RIGHT);
        TextView download=new TextView(this); download.setText("Download"); download.setPadding(18,7,18,7);
        TextView copy=new TextView(this); copy.setText("Copy"); copy.setPadding(18,7,18,7);
        TextView remove=new TextView(this); remove.setText("Remove"); remove.setPadding(18,7,18,7);
        download.setOnClickListener(v->{ startQueuedDownload(item); downloadQueue.remove(item.url); saveDownloadQueue(); showDownloadWindow(); });
        copy.setOnClickListener(v->{ ClipboardManager cm=(ClipboardManager)getSystemService(CLIPBOARD_SERVICE); cm.setPrimaryClip(ClipData.newPlainText("Download URL",item.url)); showMessage4("Link copied"); });
        remove.setOnClickListener(v->{ downloadQueue.remove(item.url); saveDownloadQueue(); showDownloadWindow(); });
        actions.addView(download); actions.addView(copy); actions.addView(remove);
        row.addView(name); row.addView(link); row.addView(actions);
        list.addView(row,new LinearLayout.LayoutParams(-1,-2));
        View line=new View(this); line.setBackgroundColor(Color.LTGRAY); list.addView(line,new LinearLayout.LayoutParams(-1,1));
    }

    private void startQueuedDownload(DownloadItem item){
        startDownload(item.url,item.userAgent,item.referer,item.mime,item.title);
    }

    private void saveDownloadQueue(){
        try {
            org.json.JSONArray a=new org.json.JSONArray();
            for(DownloadItem item:downloadQueue.values()){
                org.json.JSONObject o=new org.json.JSONObject();
                o.put("url",item.url); o.put("title",item.title); o.put("mime",item.mime==null?"":item.mime);
                o.put("ua",item.userAgent==null?"":item.userAgent); o.put("referer",item.referer==null?"":item.referer);
                a.put(o);
            }
            prefs.edit().putString("download_queue",a.toString()).apply();
        } catch(Exception ignored) {}
    }

    private void loadDownloadQueue(){
        try {
            String raw=prefs.getString("download_queue","");
            if(TextUtils.isEmpty(raw)) return;
            org.json.JSONArray a=new org.json.JSONArray(raw);
            for(int i=0;i<a.length();i++){
                org.json.JSONObject o=a.optJSONObject(i); if(o==null) continue;
                String u=o.optString("url",""); if(!isHttpUrl(u)) continue;
                downloadQueue.put(u,new DownloadItem(u,o.optString("title","download"),o.optString("mime",null),o.optString("ua",null),o.optString("referer",null)));
            }
        } catch(Exception ignored) {}
    }

    private void onVideoCandidate(VideoLinkDetector.VideoCandidate c){
        runOnUiThread(()->{
            if(!videoLinks.containsKey(c.url)){
                videoLinks.put(c.url,c);
                hasNewLinks=true;
                blinkLinksButton();
            }
        });
    }

    private void blinkLinksButton(){
        if(linksButton==null)return;
        final int green=Color.rgb(34,170,70), normal=Color.rgb(20,35,55);
        linksButton.setTextColor(green);
        AlphaAnimation a=new AlphaAnimation(1f,0.15f); a.setDuration(260); a.setRepeatMode(AlphaAnimation.REVERSE); a.setRepeatCount(7); linksButton.startAnimation(a);
    }

    private void showVideoLinks(){
        if(linksButton!=null){ linksButton.clearAnimation(); linksButton.setTextColor(Color.rgb(20,35,55)); }
        hasNewLinks=false;
        if(videoLinks.isEmpty()){
            new AlertDialog.Builder(this).setTitle("Links").setMessage("No video link has been detected on this page yet. Play/scroll the video and wait a moment for its media request to appear.").setPositiveButton("OK",null).show();
            return;
        }
        LinearLayout box=new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL); box.setPadding(18,4,18,4);
        ScrollView scroll=new ScrollView(this); LinearLayout list=new LinearLayout(this); list.setOrientation(LinearLayout.VERTICAL); scroll.addView(list);
        for(VideoLinkDetector.VideoCandidate c:videoLinks.values()) addLinkRow(list,c);
        box.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        TextView note=new TextView(this); note.setText("Only detected media/stream URLs are shown. Common advertising and tracking hosts are filtered."); note.setTextSize(11); note.setPadding(2,10,2,2); box.addView(note);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Links  ("+videoLinks.size()+")").setView(box).setNegativeButton("Close",null).create();
        dialog.show();
    }

    private void addLinkRow(LinearLayout list, VideoLinkDetector.VideoCandidate c){
        LinearLayout row=new LinearLayout(this); row.setOrientation(LinearLayout.VERTICAL); row.setPadding(6,10,6,10);
        TextView type=new TextView(this);
        type.setText(("STREAM".equals(c.type)?"STREAM  ":"VIDEO  ")+extensionLabel(c.url));
        type.setTextSize(12); type.setTextColor(Color.rgb(25,90,40));
        TextView link=new TextView(this); link.setText(c.url); link.setTextSize(10); link.setTextIsSelectable(true); link.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE); link.setMaxLines(2);
        LinearLayout buttons=new LinearLayout(this); buttons.setGravity(Gravity.RIGHT);
        TextView copy=new TextView(this); copy.setText("Copy"); copy.setGravity(Gravity.CENTER); copy.setPadding(16,7,16,7);
        TextView open=new TextView(this); open.setText("Open"); open.setGravity(Gravity.CENTER); open.setPadding(16,7,16,7);
        TextView download=new TextView(this); download.setText("Download"); download.setGravity(Gravity.CENTER); download.setPadding(16,7,16,7);
        copy.setOnClickListener(v->{ClipboardManager cm=(ClipboardManager)getSystemService(CLIPBOARD_SERVICE);cm.setPrimaryClip(ClipData.newPlainText("Video link",c.url));showMessage4("Link copied");});
        open.setOnClickListener(v->getWeb().loadUrl(c.url));
        download.setOnClickListener(v->startDownload(c.url,getWeb().getSettings().getUserAgentString(),null,null));
        buttons.addView(copy); buttons.addView(open); buttons.addView(download);
        row.addView(type); row.addView(link); row.addView(buttons); list.addView(row,new LinearLayout.LayoutParams(-1,-2));
        View line=new View(this); line.setBackgroundColor(Color.LTGRAY); list.addView(line,new LinearLayout.LayoutParams(-1,1));
    }

    private String extensionLabel(String u){
        try{ String p=Uri.parse(u).getPath(); if(p==null)return "MEDIA"; int d=p.lastIndexOf('.'); if(d>=0&&d<p.length()-1){String x=p.substring(d+1).toUpperCase(Locale.US); if(x.length()<=5)return x;} }catch(Exception ignored){}
        String l=u.toLowerCase(Locale.US); if(l.contains("m3u8"))return "M3U8"; if(l.contains("mpd"))return "MPD"; return "MEDIA";
    }

    private void findInPage(){
        final EditText e=new EditText(this); e.setHint("Find text");
        new AlertDialog.Builder(this).setTitle("Find in Page").setView(e).setPositiveButton("Find",(d,w)->getWeb().findAllAsync(e.getText().toString())).setNegativeButton("Close",null).show();
    }
    private void searchPage(){ final EditText e=new EditText(this); e.setHint("Search page"); new AlertDialog.Builder(this).setTitle("Search Page").setView(e).setPositiveButton("Search",(d,w)->getWeb().findAllAsync(e.getText().toString())).setNegativeButton("Close",null).show(); }
    private void sharePage(){ Intent s=new Intent(Intent.ACTION_SEND); s.setType("text/plain"); s.putExtra(Intent.EXTRA_TEXT,getWeb().getUrl()); startActivity(Intent.createChooser(s,"Share page")); }
    /**
     * Copy the content belonging to the last page area the user touched.
     *
     * Priority:
     *  1) currently selected text;
     *  2) value/text of a focused form control;
     *  3) a meaningful element/container at the last touch point (including
     *     modal/popup/iframe-hosted sections when the DOM exposes them);
     *  4) the visible body text as a last resort.
     *
     * This deliberately copies text/content only; it does not copy HTML or
     * execute links/buttons. Therefore pressing Copy cannot accidentally click
     * a site control.
     */
    private void copyPage(){
        final WebView w = getWeb();
        float[] point = null;
        Object tag = w.getTag(R.id.webContainer);
        if(tag instanceof float[]) point = (float[]) tag;
        final float px = point != null && point.length > 0 ? point[0] : w.getWidth() / 2f;
        final float py = point != null && point.length > 1 ? point[1] : w.getHeight() / 2f;

        // WebView coordinates are viewport coordinates. window.scrollX/Y converts
        // them into document coordinates for elementFromPoint/DOM traversal.
        String js = "(function(){try{" +
                "var x=" + px + ",y=" + py + ";" +
                "var sel=window.getSelection?window.getSelection():null;" +
                "var selected=sel?String(sel.toString()||'').trim():'';" +
                "if(selected)return JSON.stringify({kind:'selection',text:selected});" +
                "var ae=document.activeElement;" +
                "if(ae&&(ae.tagName==='TEXTAREA'||ae.tagName==='INPUT')){" +
                "var val=String(ae.value||'').trim();if(val)return JSON.stringify({kind:'field',text:val});}" +
                "var el=document.elementFromPoint(x,y);" +
                "if(!el)return JSON.stringify({kind:'body',text:String(document.body?document.body.innerText:'').trim()});" +
                "var bad=/^(SCRIPT|STYLE|NOSCRIPT|SVG|PATH|CANVAS|VIDEO|AUDIO)$/i;" +
                "var cur=el;" +
                "var best=null;" +
                "for(var i=0;i<10&&cur;i++,cur=cur.parentElement){" +
                "if(bad.test(cur.tagName))continue;" +
                "var t=String(cur.innerText||cur.textContent||'').replace(/\\s+\\n/g,'\\n').replace(/\\n\\s+/g,'\\n').trim();" +
                "if(!t)continue;" +
                "var r=cur.getBoundingClientRect();" +
                "var tag=(cur.tagName||'').toLowerCase();" +
                "var cls=String(cur.className||'').toLowerCase();" +
                "var id=String(cur.id||'').toLowerCase();" +
                "var semantic=/^(article|main|section|aside|dialog|li|p|blockquote|pre|td|th|form)$/i.test(cur.tagName);" +
                "var named=/(modal|dialog|popup|content|article|post|message|comment|description|detail)/.test(cls+' '+id);" +
                "if(semantic||named||(r.width>0&&r.height>0&&t.length>20)){best={text:t,tag:tag};}" +
                "if(semantic||named)break;" +
                "}" +
                "if(best&&best.text)return JSON.stringify({kind:'section',text:best.text,tag:best.tag});" +
                "var body=String(document.body?document.body.innerText:'').trim();" +
                "return JSON.stringify({kind:'body',text:body});" +
                "}catch(e){return JSON.stringify({kind:'error',text:''});}})()";
        w.evaluateJavascript(js, value -> {
            String text = decodeJavascriptString(value);
            if(TextUtils.isEmpty(text)){
                showMessage4("Nothing to copy");
                return;
            }
            ClipboardManager cm=(ClipboardManager)getSystemService(CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("Fast Browser content",text));
            showMessage4("Selected section copied");
        });
    }

    
    private void showTextSize(){ final String[] a={"75%","90%","100%","110%","125%","150%"}; new AlertDialog.Builder(this).setTitle("Text Size").setItems(a,(d,w)->getWeb().getSettings().setTextZoom(Integer.parseInt(a[w].replace("%","")))).show(); }

    private void startDownload(String u,String ua,String cd,String mime){
        startDownload(u,ua, getWeb().getUrl(), mime, fileNameFor(u, "download"));
    }

    private void startDownload(String u,String ua,String referer,String mime,String forcedName){
        try{
            DownloadManager dm=(DownloadManager)getSystemService(DOWNLOAD_SERVICE);
            DownloadManager.Request r=new DownloadManager.Request(Uri.parse(u));
            if(!TextUtils.isEmpty(mime)) r.setMimeType(mime);
            if(!TextUtils.isEmpty(ua)) r.addRequestHeader("User-Agent",ua);
            if(!TextUtils.isEmpty(referer)) r.addRequestHeader("Referer",referer);
            String cookies=CookieManager.getInstance().getCookie(u); if(!TextUtils.isEmpty(cookies)) r.addRequestHeader("Cookie",cookies);
            r.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED); r.setAllowedOverMetered(true); r.setAllowedOverRoaming(true);
            String name=TextUtils.isEmpty(forcedName)?fileNameFor(u,"download"):forcedName;
            if(!name.contains(".")){
                String ext=extensionLabel(u).toLowerCase(Locale.US);
                if(ext.equals("media")) ext="bin";
                name += "."+ext;
            }
            r.setTitle(name); r.setDescription("Fast Browser PC"); dm.enqueue(r);
            showMessage4("Download started: "+name);
        }catch(Exception e){ showMessage4("Download failed: "+e.getMessage()); }
    }

    @Override protected void onDestroy(){ if(mediaScan!=null) handler.removeCallbacks(mediaScan); for(Tab t:tabs) t.web.destroy(); super.onDestroy(); }
    @Override public void onBackPressed(){WebView w=getWeb(); if(w.canGoBack())w.goBack(); else if(tabs.size()>1)closeTab(currentTab); else super.onBackPressed();}
    @Override public void onRequestPermissionsResult(int requestCode,String[] permissions,int[] grantResults){
        super.onRequestPermissionsResult(requestCode,permissions,grantResults);
        if(requestCode==REQ_WEB_PERMISSIONS && pendingWebPermissionRequest!=null){
            boolean ok=true; for(int r:grantResults) if(r!=PackageManager.PERMISSION_GRANTED){ok=false;break;}
            if(ok) pendingWebPermissionRequest.grant(pendingWebPermissionRequest.getResources()); else pendingWebPermissionRequest.deny();
            pendingWebPermissionRequest=null;
        }
    }

}
