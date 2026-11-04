package com.papyper.homedesk;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Insets;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.text.InputType;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.HttpAuthHandler;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.URLUtil;
import android.webkit.WebBackForwardList;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.ServiceWorkerController;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * One app, full screen: just a WebView, no address bar. Back opens a small menu instead of leaving
 * the page, so a stray back swipe can't drop an RDP session.
 * A "tab" (hold a link › Open in new tab) is another window of the same app; while a tab shows a page
 * outside the app's address it gets a browser bar (back, forward, address, reload).
 */
public class WebActivity extends Activity {

    private static final int REQ_FILE = 1;
    static final String EXTRA_TAB_URL = "tab_url";

    private SiteStore.Site site;
    private FrameLayout root;
    private WebView web;
    private ProgressBar progress;
    private ImageButton fsButton;
    private View errorView;
    private TextView errorText;
    private ValueCallback<Uri[]> fileCallback;
    private boolean imeVisible;
    private SharedPreferences authPrefs;
    private final List<String> authTried = new ArrayList<>(); // host|realm pairs already sent this run

    private ClipboardBridge clipboard;
    private NotificationBridge notifications;
    private MediaPermissions media;

    // tabs + browser bar
    private boolean isTab;
    private String tabStartUrl, pageTitle;
    private LinearLayout bar;
    private ImageButton barBack, barForward;
    private EditText address;
    private boolean barShown;
    /** Hold on a link: WebView hands an image link's href back through a Message. */
    private final android.os.Handler linkHandler = new android.os.Handler(android.os.Looper.getMainLooper(), m -> {
        String url = m.getData().getString("url");
        if (url != null)
            showLinkMenu(url);
        return true;
    });

    // usage tracking: time counts only while this app is on screen (resumed)
    private UsageStore usage;
    private long onScreenSince;                                // 0 = not on screen
    private final android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable saveUsage = new Runnable() {         // save every minute, so a crash loses little
        @Override
        public void run() {
            flushUsage();
            handler.postDelayed(this, 60_000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        authPrefs = getSharedPreferences("http_auth", MODE_PRIVATE);
        usage = new UsageStore(this);
        ClipboardBridge.SiteHolder holder = new ClipboardBridge.SiteHolder() {
            @Override
            public SiteStore.Site site() {
                return site;
            }

            @Override
            public void save(SiteStore.Site s) {
                new SiteStore(WebActivity.this).update(s);
            }
        };
        clipboard = new ClipboardBridge(this, holder);
        notifications = new NotificationBridge(this, holder);
        media = new MediaPermissions(this, holder);
        site = siteFrom(getIntent());
        tabStartUrl = getIntent().getStringExtra(EXTRA_TAB_URL);
        isTab = tabStartUrl != null;
        if (site == null) {
            Toast.makeText(this, "This app was removed from HomeDesk", Toast.LENGTH_SHORT).show();
            finishAndRemoveTask();
            return;
        }

        getWindow().setDecorFitsSystemWindows(false); // we place everything ourselves (see applyInsets)
        root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);
        root.setOnApplyWindowInsetsListener((v, insets) -> applyInsets(insets));
        setContentView(root);

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        root.addView(progress, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                Icons.dp(this, 3), Gravity.TOP));
        buildErrorView();
        buildFullScreenButton();
        buildBrowserBar();

        createWebView();
        if (savedInstanceState != null)
            web.restoreState(savedInstanceState);
        if (web.getUrl() == null)
            web.loadUrl(isTab ? tabStartUrl : site.url);
        applySiteSettings();
    }

    private SiteStore.Site siteFrom(Intent intent) {
        Uri data = intent.getData();
        if (data == null || data.getPathSegments().isEmpty())
            return null;
        try {
            return new SiteStore(this).find(Long.parseLong(data.getPathSegments().get(0)));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (site == null)
            return;
        usage.countOpen(site.id);
        clipboard.onAppReopened();
        notifications.onAppReopened();
        media.onAppReopened();
        onScreenSince = System.currentTimeMillis();
        handler.postDelayed(saveUsage, 60_000);
        // pick up edits made in the site list while this page stayed open
        SiteStore.Site fresh = new SiteStore(this).find(site.id);
        if (fresh == null)
            return;
        boolean urlChanged = !fresh.url.equals(site.url);
        site = fresh;
        applySiteSettings();
        if (urlChanged) {
            clipboard.install(web); // the bridges are tied to the app's address
            notifications.install(web);
            if (!isTab)
                web.loadUrl(site.url);
        }
    }

    /** A notification of this window was tapped (documentLaunchMode brings this window back). */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        int id = intent.getIntExtra(NotificationBridge.EXTRA_NOTIFICATION, 0);
        if (id != 0 && notifications != null)
            notifications.onNotificationTapped(id);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == NotificationBridge.REQ_PERMISSION && notifications != null)
            notifications.onAndroidAnswer();
        if (requestCode == MediaPermissions.REQ_PERMISSION && media != null)
            media.onAndroidAnswer();
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(saveUsage);
        flushUsage();
        onScreenSince = 0;
        super.onPause();
    }

    private void flushUsage() {
        if (onScreenSince == 0 || site == null)
            return;
        long now = System.currentTimeMillis();
        usage.addTime(site.id, onScreenSince, now);
        onScreenSince = now;
    }

    // Deliberately no web.onPause()/pauseTimers(): the page keeps running in the background, so a
    // remote desktop stays connected while you look at another app.

    @Override
    protected void onDestroy() {
        if (clipboard != null)
            clipboard.uninstall();
        if (notifications != null)
            notifications.uninstall();
        if (media != null)
            media.release();
        if (web != null) {
            root.removeView(web);
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (web != null)
            web.saveState(outState);
    }

    private void applySiteSettings() {
        Icons.setTaskDescription(this, site, windowLabel());
        if (site.keepScreenOn)
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.layoutInDisplayCutoutMode = site.fullScreen
                ? WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                : WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT;
        getWindow().setAttributes(lp);
        applySystemBars();
        fsButton.setImageResource(site.fullScreen ? R.drawable.ic_fullscreen_exit : R.drawable.ic_fullscreen);
        fsButton.setContentDescription(site.fullScreen ? "Exit full screen" : "Full screen");
        root.requestApplyInsets();
    }

    // ---- floating full-screen button: tap to toggle, drag to move it out of the way ----

    private static final String FS_X = "fs_button_x", FS_Y = "fs_button_y"; // 0..1 of the free space

    private void buildFullScreenButton() {
        int size = Icons.dp(this, 40);
        fsButton = new ImageButton(this);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(0x99000000);
        bg.setStroke(Icons.dp(this, 1), 0x66FFFFFF);
        fsButton.setBackground(bg);
        fsButton.setScaleType(android.widget.ImageView.ScaleType.CENTER);
        fsButton.setAlpha(0.6f);
        root.addView(fsButton, new FrameLayout.LayoutParams(size, size, Gravity.TOP | Gravity.START));

        SharedPreferences ui = getSharedPreferences("ui", MODE_PRIVATE);
        // keep the button inside the page area whenever its size changes (rotate, fold, keyboard)
        root.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> placeFullScreenButton(
                ui.getFloat(FS_X, 1f), ui.getFloat(FS_Y, 0.12f)));

        int slop = android.view.ViewConfiguration.get(this).getScaledTouchSlop();
        fsButton.setOnTouchListener(new View.OnTouchListener() {
            float downX, downY, startX, startY;
            boolean dragging;

            @Override
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = e.getRawX();
                        downY = e.getRawY();
                        startX = v.getTranslationX();
                        startY = v.getTranslationY();
                        dragging = false;
                        v.setAlpha(1f);
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        float dx = e.getRawX() - downX, dy = e.getRawY() - downY;
                        if (!dragging && Math.hypot(dx, dy) > slop)
                            dragging = true;
                        if (dragging) {
                            float[] f = fractions(startX + dx, startY + dy);
                            placeFullScreenButton(f[0], f[1]);
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                        v.setAlpha(0.6f);
                        if (dragging) {
                            float[] f = fractions(v.getTranslationX(), v.getTranslationY());
                            ui.edit().putFloat(FS_X, f[0]).putFloat(FS_Y, f[1]).apply();
                        } else {
                            toggleFullScreen();
                        }
                        return true;
                    case MotionEvent.ACTION_CANCEL:
                        v.setAlpha(0.6f);
                        return true;
                }
                return false;
            }
        });
    }

    // gap to the screen edges: Samsung's Edge panel handle grabs touches right at the edge
    private int edgeGap() {
        return Icons.dp(this, 14);
    }

    private float freeWidth() {
        return Math.max(1, root.getWidth() - root.getPaddingLeft() - root.getPaddingRight() - fsButton.getWidth() - 2 * edgeGap());
    }

    private float freeHeight() {
        return Math.max(1, root.getHeight() - root.getPaddingTop() - root.getPaddingBottom() - fsButton.getHeight() - 2 * edgeGap());
    }

    private float[] fractions(float tx, float ty) {
        float fx = (tx - edgeGap()) / freeWidth(), fy = (ty - edgeGap()) / freeHeight();
        return new float[]{Math.max(0, Math.min(1, fx)), Math.max(0, Math.min(1, fy))};
    }

    private void placeFullScreenButton(float fx, float fy) {
        fsButton.setTranslationX(edgeGap() + fx * freeWidth());
        fsButton.setTranslationY(edgeGap() + fy * freeHeight());
    }

    private void toggleFullScreen() {
        site.fullScreen = !site.fullScreen;
        new SiteStore(this).update(site);
        applySiteSettings();
    }

    private void applySystemBars() {
        WindowInsetsController c = getWindow().getInsetsController();
        if (c == null)
            return;
        if (site.fullScreen) {
            // swipe from an edge shows the bars for a moment, on top of the page (no layout change)
            c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            c.hide(WindowInsets.Type.systemBars());
        } else {
            c.show(WindowInsets.Type.systemBars());
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && site != null)
            applySystemBars(); // dialogs and the keyboard can bring the bars back
    }

    /**
     * Full screen: the page uses the whole display (bars hidden; transient bars float over it).
     * Otherwise it sits between the status and navigation bars.
     * Keyboard: "resize" shrinks the page to the area above it; "overlay" leaves the page alone
     * (like Chrome's interactive-widget=overlays-content, which Guacamole's mobile-tweaks expects).
     */
    private WindowInsets applyInsets(WindowInsets insets) {
        Insets bars = site.fullScreen ? Insets.NONE
                : insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
        Insets ime = insets.getInsets(WindowInsets.Type.ime());
        int bottom = keyboardOverlay() ? bars.bottom : Math.max(bars.bottom, ime.bottom);
        root.setPadding(bars.left, bars.top, bars.right, bottom);

        boolean nowVisible = insets.isVisible(WindowInsets.Type.ime());
        if (imeVisible && !nowVisible && keyboardOverlay() && web != null) {
            // The page can't see an overlaid keyboard close, so release its text field for it -
            // otherwise the next tap on the field would not bring the keyboard back.
            web.evaluateJavascript("document.activeElement && document.activeElement.blur && document.activeElement.blur()", null);
        }
        imeVisible = nowVisible;
        return WindowInsets.CONSUMED;
    }

    private void createWebView() {
        web = new WebView(this);
        web.setBackgroundColor(0xFF000000);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);  // remote desktop audio
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        s.setTextZoom(100);                             // ignore the phone's font size setting
        s.setSupportZoom(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setSupportMultipleWindows(false);             // target=_blank opens in place
        s.setAllowFileAccess(false);
        s.setOffscreenPreRaster(true);                  // draw the page ahead of time: smoother scrolling

        // Keep this page's renderer as important as the app, even in the background, so Android
        // doesn't kill it first and switching back to a site doesn't reload it.
        web.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false);

        CookieManager cookies = CookieManager.getInstance();
        cookies.setAcceptCookie(true);
        cookies.setAcceptThirdPartyCookies(web, true);  // Cloudflare Access login hops domains

        web.setWebViewClient(new Client());
        web.setWebChromeClient(new Chrome());
        web.setDownloadListener(this::download);
        web.setOnLongClickListener(v -> onLongPress());
        root.addView(web, 0, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        clipboard.install(web);
        notifications.install(web);
    }

    // ---- back button: a menu, never a silent exit ----

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        List<String> labels = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        if (web != null && web.canGoBack()) {
            labels.add("Go back a page");
            actions.add(web::goBack);
        }
        labels.add(site.fullScreen ? "Exit full screen" : "Full screen");
        actions.add(this::toggleFullScreen);
        labels.add("Reload");
        actions.add(() -> { hideError(); web.reload(); });
        labels.add("Hard reload (ignore cache)");
        actions.add(this::hardReload);
        labels.add("All apps");
        actions.add(() -> startActivity(new Intent(this, MainActivity.class)
                .putExtra(MainActivity.EXTRA_SHOW_LIST, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)));
        if (isTab && web != null && web.getUrl() != null) {
            String url = web.getUrl();
            labels.add("Open in default browser");
            actions.add(() -> openInBrowser(url));
        }
        labels.add(isTab ? "Close tab" : "Close " + site.name);
        actions.add(this::finishAndRemoveTask);

        new AlertDialog.Builder(this)
                .setTitle(windowLabel())
                .setItems(labels.toArray(new String[0]), (d, which) -> actions.get(which).run())
                .setNegativeButton("Stay here", null)
                .show();
    }

    // ---- hard reload: fetch everything fresh from the server ----

    private static final String CLEAR_PAGE_CACHES =
            "(async () => {"
            + " try { for (const r of await navigator.serviceWorker.getRegistrations()) await r.unregister(); } catch (e) {}"
            + " try { for (const k of await caches.keys()) await caches.delete(k); } catch (e) {}"
            + "})()";
    private boolean noCacheUntilLoaded;

    /**
     * Like Ctrl+Shift+R in a desktop browser: drop this page's service workers and Cache Storage (offline
     * copies a normal reload keeps serving), then reload with the HTTP cache bypassed for the page and
     * everything it loads. Cookies and logins are kept.
     */
    private void hardReload() {
        hideError();
        Toast.makeText(this, "Reloading without cache", Toast.LENGTH_SHORT).show();
        web.evaluateJavascript(CLEAR_PAGE_CACHES, null);
        setCacheMode(WebSettings.LOAD_NO_CACHE);
        noCacheUntilLoaded = true;
        // give the async cleanup a moment before the page (and its scripts) go away
        handler.postDelayed(() -> {
            if (web != null)
                web.reload();
        }, 400);
    }

    private void setCacheMode(int mode) {
        if (web != null)
            web.getSettings().setCacheMode(mode);
        ServiceWorkerController.getInstance().getServiceWorkerWebSettings().setCacheMode(mode);
    }

    // ---- hold on a link: new tab / default browser / copy ----

    private boolean onLongPress() {
        WebView.HitTestResult hit = web.getHitTestResult();
        switch (hit.getType()) {
            case WebView.HitTestResult.SRC_ANCHOR_TYPE:
                if (hit.getExtra() == null || hit.getExtra().startsWith("javascript:"))
                    return false;
                showLinkMenu(hit.getExtra());
                return true;
            case WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE: // image inside a link: ask for the link's href
                web.requestFocusNodeHref(linkHandler.obtainMessage());
                return true;
            default:
                return false; // not a link: normal long press (text selection, remote right-click ...)
        }
    }

    private void showLinkMenu(String url) {
        if (url.startsWith("javascript:"))
            return;
        boolean webLink = url.startsWith("http://") || url.startsWith("https://");
        List<String> labels = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        if (webLink) {
            labels.add("Open in new tab");
            actions.add(() -> startActivity(Icons.tabIntent(this, site, url),
                    android.app.ActivityOptions.makeCustomAnimation(this, 0, 0).toBundle()));
        }
        labels.add(webLink ? "Open in default browser" : "Open with another app");
        actions.add(() -> openInBrowser(url));
        labels.add("Copy link address");
        actions.add(() -> {
            getSystemService(android.content.ClipboardManager.class)
                    .setPrimaryClip(android.content.ClipData.newPlainText("Link", url));
            if (android.os.Build.VERSION.SDK_INT < 33) // newer Android shows its own "copied" notice
                Toast.makeText(this, "Link copied", Toast.LENGTH_SHORT).show();
        });
        new AlertDialog.Builder(this)
                .setTitle(url.length() > 120 ? url.substring(0, 117) + "..." : url)
                .setItems(labels.toArray(new String[0]), (d, which) -> actions.get(which).run())
                .show();
    }

    private void openInBrowser(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "No app can open this link", Toast.LENGTH_SHORT).show();
        }
    }

    // ---- tabs: browser bar while outside the app's address ----

    /** Is {@code url} under the app's configured address (same prefix)? */
    private boolean inApp(String url) {
        if (url == null)
            return true;
        String base = site.url.replaceAll("/+$", "").toLowerCase(java.util.Locale.ROOT);
        String u = url.toLowerCase(java.util.Locale.ROOT);
        return u.equals(base) || u.startsWith(base + "/") || u.startsWith(base + "?") || u.startsWith(base + "#");
    }

    /** Pages outside the app (in a tab) are normal web pages: let the keyboard push them up. */
    private boolean keyboardOverlay() {
        return site.keyboardOverlay && !barShown;
    }

    private String windowLabel() {
        if (!isTab)
            return site.name;
        if (pageTitle != null && !pageTitle.isEmpty() && !pageTitle.startsWith("http"))
            return pageTitle;
        String url = web != null && web.getUrl() != null ? web.getUrl() : tabStartUrl;
        String host = url == null ? null : Uri.parse(url).getHost();
        return host != null ? host : site.name;
    }

    private ImageButton barButton(int icon, String description, View.OnClickListener click) {
        ImageButton b = new ImageButton(this);
        b.setImageResource(icon);
        b.setContentDescription(description);
        android.util.TypedValue tv = new android.util.TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, tv, true);
        b.setBackgroundResource(tv.resourceId);
        b.setOnClickListener(click);
        int size = Icons.dp(this, 44);
        bar.addView(b, new LinearLayout.LayoutParams(size, size));
        return b;
    }

    private void buildBrowserBar() {
        bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(0xFF202124);
        bar.setPadding(Icons.dp(this, 2), 0, Icons.dp(this, 2), 0);
        barBack = barButton(R.drawable.ic_arrow_back, "Back", v -> { if (web.canGoBack()) web.goBack(); });
        barForward = barButton(R.drawable.ic_arrow_forward, "Forward", v -> { if (web.canGoForward()) web.goForward(); });

        address = new EditText(this);
        address.setSingleLine();
        address.setTextSize(15);
        address.setTextColor(0xFFE8EAED);
        address.setHintTextColor(0xFF9AA0A6);
        address.setHint("Web address");
        address.setSelectAllOnFocus(true);
        address.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        address.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_GO);
        GradientDrawable field = new GradientDrawable();
        field.setColor(0xFF303134);
        field.setCornerRadius(Icons.dp(this, 18));
        address.setBackground(field);
        address.setPadding(Icons.dp(this, 14), 0, Icons.dp(this, 14), 0);
        address.setOnEditorActionListener((v, actionId, event) -> {
            String url = SiteStore.normalizeUrl(address.getText().toString());
            if (!url.isEmpty())
                web.loadUrl(url);
            address.clearFocus();
            getSystemService(android.view.inputmethod.InputMethodManager.class)
                    .hideSoftInputFromWindow(address.getWindowToken(), 0);
            return true;
        });
        address.setOnFocusChangeListener((v, focused) -> {
            if (!focused && web != null)
                address.setText(web.getUrl()); // drop unfinished edits
        });
        LinearLayout.LayoutParams fieldLp = new LinearLayout.LayoutParams(0, Icons.dp(this, 36), 1);
        fieldLp.setMargins(Icons.dp(this, 4), 0, Icons.dp(this, 4), 0);
        bar.addView(address, fieldLp);

        barButton(R.drawable.ic_refresh, "Reload", v -> { hideError(); web.reload(); });

        bar.setVisibility(View.GONE);
        root.addView(bar, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                Icons.dp(this, 48), Gravity.TOP));
    }

    private void updateBrowserBar(String url) {
        if (!isTab || bar == null)
            return;
        boolean show = !inApp(url);
        if (show != barShown) {
            barShown = show;
            applyBarLayout();
            root.requestApplyInsets(); // keyboard mode depends on it
        }
        if (!address.hasFocus())
            address.setText(url);
        barBack.setEnabled(web.canGoBack());
        barBack.setAlpha(web.canGoBack() ? 1f : 0.35f);
        barForward.setEnabled(web.canGoForward());
        barForward.setAlpha(web.canGoForward() ? 1f : 0.35f);
        Icons.setTaskDescription(this, site, windowLabel());
    }

    /** Page, progress line and error box sit below the bar while it shows. */
    private void applyBarLayout() {
        bar.setVisibility(barShown ? View.VISIBLE : View.GONE);
        int top = barShown ? Icons.dp(this, 48) : 0;
        for (View v : new View[]{web, progress, errorView}) {
            if (v == null)
                continue;
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) v.getLayoutParams();
            lp.topMargin = top;
            v.setLayoutParams(lp);
        }
    }

    // ---- page errors ----

    private void buildErrorView() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setBackgroundColor(0xFF111111);
        int p = Icons.dp(this, 32);
        box.setPadding(p, p, p, p);
        errorText = new TextView(this);
        errorText.setTextColor(0xFFEEEEEE);
        errorText.setTextSize(16);
        errorText.setGravity(Gravity.CENTER);
        Button retry = new Button(this);
        retry.setText("Try again");
        retry.setOnClickListener(v -> { hideError(); web.reload(); });
        Button sites = new Button(this);
        sites.setText("All apps");
        sites.setOnClickListener(v -> startActivity(new Intent(this, MainActivity.class)
                .putExtra(MainActivity.EXTRA_SHOW_LIST, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)));
        box.addView(errorText);
        box.addView(retry);
        box.addView(sites);
        errorView = box;
        errorView.setVisibility(View.GONE);
        root.addView(errorView, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private void showError(String message) {
        errorText.setText("Can't open " + site.name + "\n\n" + message + "\n");
        errorView.setVisibility(View.VISIBLE);
    }

    private void hideError() {
        errorView.setVisibility(View.GONE);
    }

    private final class Client extends WebViewClient {
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            Uri uri = request.getUrl();
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
            if (scheme.equals("http") || scheme.equals("https") || scheme.equals("about")
                    || scheme.equals("data") || scheme.equals("blob") || scheme.equals("javascript"))
                return false;
            try { // mailto:, tel:, intent: ... -> the app that handles it
                Intent i = scheme.equals("intent") ? Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME)
                        : new Intent(Intent.ACTION_VIEW, uri);
                i.addCategory(Intent.CATEGORY_BROWSABLE).setComponent(null).setSelector(null);
                startActivity(i);
            } catch (Exception e) {
                Toast.makeText(WebActivity.this, "No app can open this link", Toast.LENGTH_SHORT).show();
            }
            return true;
        }

        @Override
        public void onPageStarted(WebView view, String url, Bitmap favicon) {
            hideError(); // a failed load shows it again in onReceivedError
            progress.setVisibility(View.VISIBLE);
            updateBrowserBar(url);
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            if (noCacheUntilLoaded) {
                noCacheUntilLoaded = false;
                // late, lazily loaded files of the fresh page still skip the cache for a few seconds
                handler.postDelayed(() -> setCacheMode(WebSettings.LOAD_DEFAULT), 5000);
            }
            // Back never returns to a login page: once a sign-in (Cloudflare Access) has led here, forget it.
            WebBackForwardList history = view.copyBackForwardList();
            for (int i = 0; i < history.getCurrentIndex(); i++) {
                String host = Uri.parse(history.getItemAtIndex(i).getUrl()).getHost();
                if (host != null && host.endsWith(".cloudflareaccess.com")) {
                    view.clearHistory();
                    break;
                }
            }
            progress.setVisibility(View.GONE);
            CookieManager.getInstance().flush(); // keep logins if Android kills the app
        }

        @Override
        public void doUpdateVisitedHistory(WebView view, String url, boolean isReload) {
            updateBrowserBar(url); // also catches in-page (pushState) navigation
        }

        @Override
        public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
            if (request.isForMainFrame())
                showError(String.valueOf(error.getDescription()));
        }

        @Override
        public void onReceivedHttpAuthRequest(WebView view, HttpAuthHandler handler, String host, String realm) {
            String key = host + "|" + realm;
            String saved = authPrefs.getString(key, null);
            if (saved != null && !authTried.contains(key)) {
                authTried.add(key); // first try: the saved login; if it fails we land here again and ask
                int sep = saved.indexOf('\n');
                handler.proceed(saved.substring(0, sep), saved.substring(sep + 1));
                return;
            }
            askLogin(handler, host, key, saved);
        }

        @Override
        public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
            // Android killed the page's renderer (low memory). Start the page again instead of crashing.
            if (web == view) {
                String last = web.getUrl();
                root.removeView(web);
                web.destroy();
                createWebView();
                web.loadUrl(isTab && last != null ? last : site.url);
                applyBarLayout();
            }
            return true;
        }
    }

    /** Some apps use HTTP basic auth; WebView shows no login prompt of its own. */
    private void askLogin(HttpAuthHandler handler, String host, String key, String saved) {
        int pad = Icons.dp(this, 20);
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(pad, Icons.dp(this, 8), pad, 0);
        EditText user = new EditText(this);
        user.setHint("Username");
        user.setSingleLine();
        user.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        EditText pass = new EditText(this);
        pass.setHint("Password");
        pass.setSingleLine();
        pass.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        CheckBox remember = new CheckBox(this);
        remember.setText("Remember on this phone");
        remember.setChecked(true);
        if (saved != null)
            user.setText(saved.substring(0, saved.indexOf('\n')));
        form.addView(user);
        form.addView(pass);
        form.addView(remember);

        new AlertDialog.Builder(this)
                .setTitle("Sign in to " + host)
                .setMessage(saved != null ? "The saved login was not accepted." : null)
                .setView(form)
                .setCancelable(false)
                .setNegativeButton("Cancel", (d, w) -> handler.cancel())
                .setPositiveButton("Sign in", (d, w) -> {
                    String u = user.getText().toString(), p = pass.getText().toString();
                    if (remember.isChecked()) {
                        authPrefs.edit().putString(key, u + "\n" + p).apply();
                        authTried.add(key);
                    } else {
                        authPrefs.edit().remove(key).apply();
                    }
                    handler.proceed(u, p);
                })
                .show();
    }

    private final class Chrome extends WebChromeClient {
        @Override
        public void onProgressChanged(WebView view, int newProgress) {
            progress.setProgress(newProgress);
        }

        @Override
        public void onReceivedTitle(WebView view, String title) {
            pageTitle = title;
            if (isTab)
                Icons.setTaskDescription(WebActivity.this, site, windowLabel());
        }

        /** getUserMedia: microphone only, per the app's Microphone setting (MediaPermissions). */
        @Override
        public void onPermissionRequest(android.webkit.PermissionRequest request) {
            media.onRequest(request);
        }

        @Override
        public void onPermissionRequestCanceled(android.webkit.PermissionRequest request) {
            media.onCanceled(request);
        }

        @Override
        public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
            if (fileCallback != null)
                fileCallback.onReceiveValue(null);
            fileCallback = callback;
            Intent pick = params.createIntent();
            if (params.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE)
                pick.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            try {
                startActivityForResult(pick, REQ_FILE);
            } catch (ActivityNotFoundException e) {
                fileCallback = null;
                return false;
            }
            return true;
        }
    }

    @Override
    @SuppressWarnings("deprecation")
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQ_FILE && fileCallback != null) {
            Uri[] result = null;
            if (resultCode == RESULT_OK && data != null) {
                if (data.getClipData() != null) {
                    result = new Uri[data.getClipData().getItemCount()];
                    for (int i = 0; i < result.length; i++)
                        result[i] = data.getClipData().getItemAt(i).getUri();
                } else if (data.getData() != null) {
                    result = new Uri[]{data.getData()};
                }
            }
            fileCallback.onReceiveValue(result);
            fileCallback = null;
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    // ---- downloads go to the phone's Download folder ----

    private void download(String url, String userAgent, String contentDisposition, String mimeType, long length) {
        if (!URLUtil.isNetworkUrl(url)) {
            Toast.makeText(this, "This file can't be downloaded from here", Toast.LENGTH_SHORT).show();
            return;
        }
        String fileName = URLUtil.guessFileName(url, contentDisposition, mimeType);
        DownloadManager.Request req = new DownloadManager.Request(Uri.parse(url))
                .setMimeType(mimeType)
                .addRequestHeader("User-Agent", userAgent)
                .setTitle(fileName)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName);
        String cookie = CookieManager.getInstance().getCookie(url);
        if (cookie != null)
            req.addRequestHeader("Cookie", cookie);
        String auth = basicAuthHeader(Uri.parse(url).getHost());
        if (auth != null)
            req.addRequestHeader("Authorization", auth);
        ((DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE)).enqueue(req);
        Toast.makeText(this, "Downloading " + fileName, Toast.LENGTH_SHORT).show();
    }

    private String basicAuthHeader(String host) {
        if (host == null)
            return null;
        for (String key : authPrefs.getAll().keySet()) {
            if (key.startsWith(host + "|")) {
                String saved = authPrefs.getString(key, "");
                String up = saved.replaceFirst("\n", ":");
                return "Basic " + android.util.Base64.encodeToString(up.getBytes(), android.util.Base64.NO_WRAP);
            }
        }
        return null;
    }
}
