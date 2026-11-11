package com.moontvplus.phone;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * MoonTVPlus 手机端外壳：一个极简 WebView 容器。
 *
 * 和 TV 版（apps/android-tv）的区别：
 * - 打开站点根路径（手机版页面），不是 /tv
 * - 不锁横屏，跟随设备旋转
 * - 服务器地址可以在 App 内设置并记住，不用为了换地址重新打包
 */
public class MainActivity extends Activity {

    /** 长按返回键打开服务器设置，作为快捷方式的兜底入口 */
    private static final long BACK_LONG_PRESS_MS = 700L;
    private static final int REQ_FILE_CHOOSER = 1001;

    private static final String ACTION_OPEN_SETTINGS = "com.moontvplus.phone.OPEN_SETTINGS";

    private FrameLayout root;
    private WebView webView;
    private View customView;
    private WebChromeClient.CustomViewCallback customViewCallback;
    private ValueCallback<Uri[]> filePathCallback;

    private long lastBackPressedAt = 0L;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        root = new FrameLayout(this);
        setContentView(root);

        if (ACTION_OPEN_SETTINGS.equals(getIntent() == null ? null : getIntent().getAction())) {
            showSettings(false);
            return;
        }

        String savedUrl = getSavedServerUrl();
        if (savedUrl == null || savedUrl.isEmpty()) {
            // 首次启动：先让用户确认服务器地址，避免装完打开是一片空白
            showSettings(true);
        } else {
            loadServer(savedUrl);
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (intent != null && ACTION_OPEN_SETTINGS.equals(intent.getAction())) {
            showSettings(false);
        }
    }

    // ---------------------------------------------------------------- 服务器地址

    private String getSavedServerUrl() {
        SharedPreferences prefs = getSharedPreferences("moontv_phone", MODE_PRIVATE);
        return prefs.getString("server_url", BuildConfig.BASE_URL);
    }

    private void saveServerUrl(String url) {
        getSharedPreferences("moontv_phone", MODE_PRIVATE)
                .edit()
                .putString("server_url", url)
                .apply();
    }

    /** 补协议、去掉末尾斜杠；粘成 /tv 结尾的也回退到站点根路径 */
    private String normalizeServerUrl(String raw) {
        if (raw == null) return null;
        String url = raw.trim();
        if (url.isEmpty()) return null;
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = "http://" + url;
        }
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        if (url.endsWith("/tv")) {
            url = url.substring(0, url.length() - 3);
        }
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        if (url.equals("http:") || url.equals("https:")) return null;
        return url;
    }

    // ---------------------------------------------------------------- 页面

    private void loadServer(String serverUrl) {
        root.removeAllViews();
        customView = null;
        customViewCallback = null;

        if (webView == null) {
            setupWebView();
        }
        root.addView(webView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));
        webView.setVisibility(View.VISIBLE);
        webView.loadUrl(serverUrl);
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void setupWebView() {
        webView = new WebView(this);
        webView.setFocusable(true);
        webView.setFocusableInTouchMode(true);
        webView.requestFocus();

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(true);
        settings.setSupportZoom(false);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        settings.setJavaScriptCanOpenWindowsAutomatically(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        }
        settings.setUserAgentString(settings.getUserAgentString() + " MoonTVPlusAndroid");

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return handleUrl(request.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return handleUrl(Uri.parse(url));
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request != null && request.isForMainFrame()) {
                    showErrorPage(String.valueOf(error == null ? "" : error.getDescription()));
                }
            }

            @Override
            public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
                showErrorPage(description);
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onShowCustomView(View view, CustomViewCallback callback) {
                if (customView != null) {
                    callback.onCustomViewHidden();
                    return;
                }
                customView = view;
                customViewCallback = callback;
                webView.setVisibility(View.GONE);
                root.addView(customView, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                ));
                enterImmersive();
            }

            @Override
            public void onHideCustomView() {
                exitFullscreen();
            }

            @Override
            public boolean onShowFileChooser(
                    WebView view,
                    ValueCallback<Uri[]> callback,
                    FileChooserParams params
            ) {
                if (filePathCallback != null) {
                    filePathCallback.onReceiveValue(null);
                }
                filePathCallback = callback;

                Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("*/*");
                intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                try {
                    startActivityForResult(Intent.createChooser(intent, "选择文件"), REQ_FILE_CHOOSER);
                } catch (Exception e) {
                    filePathCallback = null;
                    return false;
                }
                return true;
            }
        });
    }

    /** http/https 留在 WebView；nplayer:// 、magnet: 之类的协议交给系统或对应 App */
    private boolean handleUrl(Uri uri) {
        if (uri == null) return false;
        String scheme = uri.getScheme();
        if (scheme == null
                || "http".equalsIgnoreCase(scheme)
                || "https".equalsIgnoreCase(scheme)
                || "about".equalsIgnoreCase(scheme)) {
            return false;
        }
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, uri));
        } catch (Exception e) {
            Toast.makeText(this, "没有可以打开该链接的应用", Toast.LENGTH_SHORT).show();
        }
        return true;
    }

    // ---------------------------------------------------------------- 全屏视频

    private void enterImmersive() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        );
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            getWindow().setStatusBarColor(Color.BLACK);
            getWindow().setNavigationBarColor(Color.BLACK);
        }
    }

    private void exitFullscreen() {
        if (customView == null) return;
        root.removeView(customView);
        customView = null;
        webView.setVisibility(View.VISIBLE);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
        if (customViewCallback != null) {
            customViewCallback.onCustomViewHidden();
            customViewCallback = null;
        }
    }

    // ---------------------------------------------------------------- 设置页

    private void showSettings(boolean firstRun) {
        if (webView != null) {
            webView.stopLoading();
            root.removeView(webView);
        }

        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setGravity(Gravity.CENTER_VERTICAL);
        int pad = dp(24);
        container.setPadding(pad, pad, pad, pad);
        container.setBackgroundColor(Color.WHITE);

        TextView title = new TextView(this);
        title.setText("服务器地址");
        title.setTextSize(22);
        title.setTextColor(Color.parseColor("#111827"));
        container.addView(title);

        TextView hint = new TextView(this);
        hint.setText("填 MoonTVPlus 站点的地址，不带 /tv。例如 https://tv.example.com 或 http://192.168.1.10:3000");
        hint.setTextSize(13);
        hint.setTextColor(Color.parseColor("#6b7280"));
        hint.setPadding(0, dp(8), 0, dp(16));
        container.addView(hint);

        final EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        input.setHint("https://");
        String current = getSavedServerUrl();
        if (current != null) {
            input.setText(current);
            input.setSelection(current.length());
        }
        container.addView(input);

        Button save = new Button(this);
        save.setText(firstRun ? "保存并打开" : "保存");
        save.setOnClickListener(v -> {
            String normalized = normalizeServerUrl(input.getText().toString());
            if (normalized == null) {
                Toast.makeText(this, "请填写有效的服务器地址", Toast.LENGTH_SHORT).show();
                return;
            }
            saveServerUrl(normalized);
            loadServer(normalized);
        });
        LinearLayout.LayoutParams saveParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        saveParams.topMargin = dp(20);
        container.addView(save, saveParams);

        if (!firstRun) {
            Button cancel = new Button(this);
            cancel.setText("取消");
            cancel.setOnClickListener(v -> {
                String savedUrl = getSavedServerUrl();
                if (savedUrl == null || savedUrl.isEmpty()) {
                    showSettings(true);
                } else {
                    loadServer(savedUrl);
                }
            });
            container.addView(cancel);
        }

        root.removeAllViews();
        root.addView(container, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));
        container.setBackgroundColor(Color.WHITE);
        input.requestFocus();
    }

    // ---------------------------------------------------------------- 错误页

    private void showErrorPage(String message) {
        if (isFinishing()) return;

        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setGravity(Gravity.CENTER);
        int pad = dp(24);
        container.setPadding(pad, pad, pad, pad);
        container.setBackgroundColor(Color.parseColor("#0b0b0b"));

        TextView title = new TextView(this);
        title.setText("打不开页面");
        title.setTextSize(20);
        title.setTextColor(Color.WHITE);
        container.addView(title);

        TextView detail = new TextView(this);
        detail.setText((message == null || message.isEmpty() ? "网络错误" : message)
                + "\n\n检查：地址是否正确、设备和服务端在同一网络。");
        detail.setTextSize(13);
        detail.setTextColor(Color.parseColor("#9ca3af"));
        detail.setGravity(Gravity.CENTER);
        detail.setPadding(0, dp(12), 0, dp(20));
        container.addView(detail);

        Button retry = new Button(this);
        retry.setText("重试");
        retry.setOnClickListener(v -> {
            String savedUrl = getSavedServerUrl();
            if (savedUrl == null || savedUrl.isEmpty()) {
                showSettings(true);
            } else {
                loadServer(savedUrl);
            }
        });
        container.addView(retry);

        Button settings = new Button(this);
        settings.setText("修改服务器地址");
        settings.setOnClickListener(v -> showSettings(false));
        container.addView(settings);

        root.removeAllViews();
        root.addView(container, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));
    }

    // ---------------------------------------------------------------- 生命周期 / 返回键

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQ_FILE_CHOOSER) {
            if (filePathCallback == null) {
                super.onActivityResult(requestCode, resultCode, data);
                return;
            }
            Uri[] results = null;
            if (resultCode == RESULT_OK && data != null) {
                if (data.getClipData() != null) {
                    int count = data.getClipData().getItemCount();
                    List<Uri> uris = new ArrayList<>();
                    for (int i = 0; i < count; i++) {
                        uris.add(data.getClipData().getItemAt(i).getUri());
                    }
                    results = uris.toArray(new Uri[0]);
                } else if (data.getData() != null) {
                    results = new Uri[]{data.getData()};
                }
            }
            filePathCallback.onReceiveValue(results);
            filePathCallback = null;
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK && event != null
                && event.getRepeatCount() >= 3) {
            // 长按返回键 → 服务器设置（三键导航下可用；手势导航请用桌面快捷方式）
            Toast.makeText(this, "打开服务器设置", Toast.LENGTH_SHORT).show();
            showSettings(false);
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public void onBackPressed() {
        if (customView != null) {
            exitFullscreen();
            return;
        }
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastBackPressedAt < 2000) {
            super.onBackPressed();
            return;
        }
        lastBackPressedAt = now;
        Toast.makeText(this, "再按一次退出", Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (webView != null) webView.onResume();
    }

    @Override
    protected void onPause() {
        if (webView != null) webView.onPause();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        exitFullscreen();
        if (webView != null) {
            root.removeView(webView);
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
