package com.fastbrowser.pc;

import android.net.Uri;
import android.webkit.WebResourceRequest;
import java.util.*;

/**
 * Detects real media/stream requests made by the page instead of relying only on
 * visible <video> links. It intentionally rejects common advertising/tracking URLs.
 */
public final class VideoLinkDetector {
    public interface Listener { void onVideoCandidate(VideoCandidate candidate); }

    public static final class VideoCandidate {
        public final String url;
        public final String pageUrl;
        public final String type;
        public final String source;
        public VideoCandidate(String url, String pageUrl, String type, String source) {
            this.url=url; this.pageUrl=pageUrl; this.type=type; this.source=source;
        }
    }

    private static final Set<String> VIDEO_EXT = new HashSet<>(Arrays.asList(
        "mp4","webm","m4v","mov","mkv","avi","flv","f4v","3gp","3g2","ts","m2ts","mts","mpeg","mpg","ogv","wmv","asf"
    ));
    private static final Set<String> STREAM_EXT = new HashSet<>(Arrays.asList("m3u8","mpd"));
    private static final String[] AD_WORDS = {
        "doubleclick.net","googlesyndication.com","googleadservices.com","adservice.google.com",
        "adsrvr.org","adnxs.com","amazon-adsystem.com","scorecardresearch.com","outbrain.com",
        "taboola.com","moatads.com","rubiconproject.com","criteo.com","pubmatic.com",
        "ads.","ad.","/ads/","/ad/","/advert","/advertisement","/banner","/tracking",
        "/tracker","/impression","/clicktrack","/vast","/vmap","/prebid","/creative/"
    };

    private final Listener listener;
    private final Set<String> seen = new HashSet<>();

    public VideoLinkDetector(Listener listener) { this.listener = listener; }

    public void clear() { seen.clear(); }

    public void inspect(WebResourceRequest request, String pageUrl) {
        if (request == null) return;
        inspect(request.getUrl().toString(), pageUrl, "network");
    }

    public void inspect(String rawUrl, String pageUrl, String source) {
        if (rawUrl == null) return;
        String u = rawUrl.trim();
        if (u.length() < 8 || u.startsWith("blob:") || u.startsWith("data:") || u.startsWith("javascript:")) return;
        if (!u.startsWith("http://") && !u.startsWith("https://")) return;
        String lower = u.toLowerCase(Locale.US);
        if (isAdvertising(lower)) return;

        String ext = extension(u);
        boolean direct = VIDEO_EXT.contains(ext);
        boolean stream = STREAM_EXT.contains(ext) || lower.contains("/manifest") || lower.contains("/playlist");
        boolean mediaQuery = lower.matches(".*[?&](format|mime|type|video|videotype|media|source)=[^&]*(mp4|webm|m4v|mov|mkv|m3u8|mpd).*" );
        if (!(direct || stream || mediaQuery)) return;

        String type = direct ? "VIDEO" : "STREAM";
        String key = u;
        if (seen.add(key)) listener.onVideoCandidate(new VideoCandidate(u, pageUrl == null ? "" : pageUrl, type, source));
    }

    private static String extension(String u) {
        try {
            String p = Uri.parse(u).getPath();
            if (p == null) return "";
            int slash=p.lastIndexOf('/');
            int dot=p.lastIndexOf('.');
            if (dot < 0 || dot < slash) return "";
            return p.substring(dot+1).toLowerCase(Locale.US);
        } catch (Exception e) { return ""; }
    }

    private static boolean isAdvertising(String u) {
        for (String word : AD_WORDS) if (u.contains(word)) return true;
        return false;
    }
}
