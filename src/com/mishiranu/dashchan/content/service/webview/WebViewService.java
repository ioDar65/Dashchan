package com.mishiranu.dashchan.content.service.webview;

import android.annotation.SuppressLint;
import android.app.Service;
import android.content.Intent;
import android.net.http.SslError;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.util.Pair;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.SslErrorHandler;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import chan.util.StringUtils;
import com.mishiranu.dashchan.util.WebViewUtils;
import java.util.LinkedList;

public class WebViewService extends Service {
	private static class CookieRequest {
		public final String name;
		public final String uriString;
		public final String userAgent;
		public final Pair<String, Integer> httpProxy;
		public final boolean verifyCertificate;
		public final long timeout;
		public final IRequestCallback requestCallback;

		public boolean ready;
		public String cookie;

		private CookieRequest(String name, String uriString, String userAgent, Pair<String, Integer> httpProxy,
				boolean verifyCertificate, long timeout, IRequestCallback requestCallback) {
			this.name = name;
			this.uriString = uriString;
			this.userAgent = userAgent;
			this.httpProxy = httpProxy;
			this.verifyCertificate = verifyCertificate;
			this.timeout = timeout;
			this.requestCallback = requestCallback;
		}
	}

	private final LinkedList<CookieRequest> cookieRequests = new LinkedList<>();

	private WebView webView;
	private CookieRequest cookieRequest;

	private static final int MESSAGE_HANDLE_NEXT = 1;
	private static final int MESSAGE_HANDLE_FINISH = 2;

	private final Handler handler = new Handler(Looper.getMainLooper(), message -> {
		switch (message.what) {
			case MESSAGE_HANDLE_NEXT: {
				handleNextCookieRequest();
				return true;
			}
			case MESSAGE_HANDLE_FINISH: {
				CookieRequest cookieRequest = WebViewService.this.cookieRequest;
				if (cookieRequest != null) {
					synchronized (cookieRequest) {
						if (!cookieRequest.ready) {
							cookieRequest.ready = true;
							cookieRequest.notifyAll();
						}
					}
					WebViewService.this.cookieRequest = null;
				}
				handleNextCookieRequest();
				return true;
			}
		}
		return false;
	});

	private class ServiceClient extends WebViewClient {
		@SuppressWarnings("deprecation")
		@Override
		public boolean shouldOverrideUrlLoading(WebView view, String url) {
			return false;
		}

		@Override
		public void onPageFinished(WebView view, String url) {
			super.onPageFinished(view, url);

			CookieRequest cookieRequest = WebViewService.this.cookieRequest;
			boolean finished = true;
			if (cookieRequest != null) {
				try {
					finished = cookieRequest.requestCallback.onPageFinished(url, view.getTitle());
				} catch (RemoteException e) {
					e.printStackTrace();
				}
			}
			if (finished) {
				view.stopLoading();
				if (cookieRequest != null) {
					cookieRequest.cookie = null;
					String data = CookieManager.getInstance().getCookie(url);
					if (data != null) {
						String[] splitted = data.split(";\\s*");
						if (splitted != null) {
							for (int i = 0; i < splitted.length; i++) {
								if (!StringUtils.isEmptyOrWhitespace(splitted[i]) &&
										splitted[i].startsWith(cookieRequest.name + "=")) {
									cookieRequest.cookie = splitted[i].substring(cookieRequest.name.length() + 1);
								}
							}
						}
					}
					handler.removeMessages(MESSAGE_HANDLE_FINISH);
					handler.sendEmptyMessage(MESSAGE_HANDLE_FINISH);
				}
			}
		}

		@Override
		public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
			CookieRequest cookieRequest = WebViewService.this.cookieRequest;
			if (cookieRequest != null) {
				if (cookieRequest.verifyCertificate) {
					handler.cancel();
					handler.removeMessages(MESSAGE_HANDLE_FINISH);
					handler.sendEmptyMessage(MESSAGE_HANDLE_FINISH);
				} else {
					handler.proceed();
				}
			} else {
				super.onReceivedSslError(view, handler, error);
			}
		}

		@SuppressWarnings("deprecation")
		@Override
		public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
			super.onReceivedError(view, errorCode, description, failingUrl);

			handler.removeMessages(MESSAGE_HANDLE_FINISH);
			handler.sendEmptyMessage(MESSAGE_HANDLE_FINISH);
		}

		@SuppressWarnings("deprecation")
		@Override
		public WebResourceResponse shouldInterceptRequest(WebView view, String url) {
			boolean allowed = false;
			CookieRequest cookieRequest = WebViewService.this.cookieRequest;
			if (url.contains("google.com/recaptcha")) {
				// TODO Handle recaptcha request
				handler.removeMessages(MESSAGE_HANDLE_FINISH);
				handler.sendEmptyMessage(MESSAGE_HANDLE_FINISH);
				return new WebResourceResponse("text/html", "UTF-8", null);
			}
			if (cookieRequest != null) {
				try {
					allowed = cookieRequest.requestCallback.onLoad(url);
				} catch (RemoteException e) {
					e.printStackTrace();
				}
			}
			if (allowed) {
				return super.shouldInterceptRequest(view, url);
			} else {
				return new WebResourceResponse("text/html", "UTF-8", null);
			}
		}
	}

	private void handleNextCookieRequest() {
		if (cookieRequest == null) {
			synchronized (cookieRequests) {
				if (!cookieRequests.isEmpty()) {
					cookieRequest = cookieRequests.removeFirst();
				}
			}
			webView.stopLoading();
			WebViewUtils.clearAll(webView);
			if (cookieRequest != null) {
				WebViewUtils.setHttpProxy(this, cookieRequest.httpProxy);
				handler.removeMessages(MESSAGE_HANDLE_FINISH);
				handler.sendEmptyMessageDelayed(MESSAGE_HANDLE_FINISH, cookieRequest.timeout);
				webView.getSettings().setUserAgentString(cookieRequest.userAgent);
				webView.loadUrl(cookieRequest.uriString);
			} else {
				webView.loadUrl("about:blank");
			}
		}
	}

	@Override
	public IBinder onBind(Intent intent) {
		return new IWebViewService.Stub() {
			@Override
			public String loadWithCookieResult(String name, String uriString, String userAgent,
					String httpProxyHost, int httpProxyPort, boolean verifyCertificate, long timeout,
					IRequestCallback requestCallback) throws RemoteException {
				Pair<String, Integer> httpProxy = httpProxyHost != null
						? new Pair<>(httpProxyHost, httpProxyPort) : null;
				CookieRequest cookieRequest = new CookieRequest(name, uriString, userAgent, httpProxy,
						verifyCertificate, timeout, requestCallback);
				synchronized (cookieRequest) {
					synchronized (cookieRequests) {
						cookieRequests.add(cookieRequest);
					}
					handler.sendEmptyMessage(MESSAGE_HANDLE_NEXT);
					while (!cookieRequest.ready) {
						try {
							cookieRequest.wait();
						} catch (InterruptedException e) {
							Thread.currentThread().interrupt();
							throw new RemoteException("interrupted");
						}
					}
				}
				return cookieRequest.cookie;
			}
		};
	}

	@SuppressLint("SetJavaScriptEnabled")
	@Override
	public void onCreate() {
		super.onCreate();

		webView = new WebView(this);
		webView.getSettings().setJavaScriptEnabled(true);
		webView.getSettings().setCacheMode(WebSettings.LOAD_NO_CACHE);
		webView.getSettings().setAppCacheEnabled(false);
		webView.setWebViewClient(new ServiceClient());
		webView.setLayoutParams(new ViewGroup.LayoutParams(480, 270));
		int measureSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED);
		webView.measure(measureSpec, measureSpec);
		webView.layout(0, 0, webView.getLayoutParams().width, webView.getLayoutParams().height);
		webView.setInitialScale(25);
	}

	@Override
	public void onDestroy() {
		super.onDestroy();

		webView.destroy();
		webView = null;
	}
}