import SwiftUI
@preconcurrency import WebKit

/// Single shared controlled WKWebView modal used for captcha or second-factor CAS fallback.
/// Uses an ephemeral non-persistent store so cookies never outlive the challenge session.
struct ControlledSchoolWebViewSheet: View {
    let sessionContext: ControlledWebViewPolicy.SessionContext
    let accountState: SchoolAccountState
    let scopeHosts: Set<String>
    let targetCookieHost: String
    let completionPathPrefixes: Set<String>
    let targetCookieDomains: Set<String>?
    let onCompletionURLCaptured: (@MainActor (URL) -> Void)?
    let onCookiesCaptured: @MainActor ([String: String]) -> Void
    let onDismiss: @MainActor () -> Void

    @State private var isLoading = true
    @State private var errorMessage: String?
    @State private var reloadToken = 0

    init(
        sessionContext: ControlledWebViewPolicy.SessionContext,
        accountState: SchoolAccountState,
        scopeHosts: Set<String>,
        targetCookieHost: String,
        completionPathPrefixes: Set<String>,
        targetCookieDomains: Set<String>? = nil,
        onCompletionURLCaptured: (@MainActor (URL) -> Void)? = nil,
        onCookiesCaptured: @escaping @MainActor ([String: String]) -> Void,
        onDismiss: @escaping @MainActor () -> Void
    ) {
        self.sessionContext = sessionContext
        self.accountState = accountState
        self.scopeHosts = scopeHosts
        self.targetCookieHost = targetCookieHost
        self.completionPathPrefixes = completionPathPrefixes
        self.targetCookieDomains = targetCookieDomains
        self.onCompletionURLCaptured = onCompletionURLCaptured
        self.onCookiesCaptured = onCookiesCaptured
        self.onDismiss = onDismiss
    }

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                if isLoading {
                    ProgressView()
                        .progressViewStyle(.linear)
                }

                if let errorMessage {
                    HStack(spacing: 8) {
                        Image(systemName: "exclamationmark.triangle.fill")
                            .foregroundStyle(.orange)
                        Text(errorMessage)
                            .font(.caption)
                        Spacer()
                    }
                    .padding(.horizontal, 14)
                    .padding(.vertical, 8)
                    .background(Color.red.opacity(0.12))
                }

                ControlledWKWebViewRepresentable(
                    entryURL: sessionContext.entryURL,
                    scopeHosts: scopeHosts,
                    targetCookieHost: targetCookieHost,
                    targetCookieDomains: targetCookieDomains ?? [targetCookieHost.lowercased()],
                    completionPathPrefixes: completionPathPrefixes,
                    reloadToken: reloadToken,
                    onLoadingChanged: { isLoading = $0 },
                    onErrorChanged: { errorMessage = $0 },
                    onCompletionURLCaptured: onCompletionURLCaptured,
                    onCookiesCaptured: onCookiesCaptured
                )
            }
            .navigationTitle("学校安全验证")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("关闭") {
                        onDismiss()
                    }
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Button {
                        errorMessage = nil
                        reloadToken += 1
                    } label: {
                        Image(systemName: "arrow.clockwise")
                    }
                    .accessibilityLabel("刷新页面")
                }
            }
            .onChange(of: accountState) { _, updatedState in
                if !sessionContext.isStillValid(for: updatedState) {
                    onDismiss()
                }
            }
        }
    }
}

private struct ControlledWKWebViewRepresentable: UIViewRepresentable {
    let entryURL: URL
    let scopeHosts: Set<String>
    let targetCookieHost: String
    let targetCookieDomains: Set<String>
    let completionPathPrefixes: Set<String>
    let reloadToken: Int
    let onLoadingChanged: @MainActor (Bool) -> Void
    let onErrorChanged: @MainActor (String?) -> Void
    let onCompletionURLCaptured: (@MainActor (URL) -> Void)?
    let onCookiesCaptured: @MainActor ([String: String]) -> Void

    func makeCoordinator() -> Coordinator {
        Coordinator(
            entryURL: entryURL,
            scopeHosts: scopeHosts,
            targetCookieHost: targetCookieHost,
            targetCookieDomains: targetCookieDomains,
            completionPathPrefixes: completionPathPrefixes,
            onLoadingChanged: onLoadingChanged,
            onErrorChanged: onErrorChanged,
            onCompletionURLCaptured: onCompletionURLCaptured,
            onCookiesCaptured: onCookiesCaptured
        )
    }

    func makeUIView(context: Context) -> WKWebView {
        let config = WKWebViewConfiguration()
        config.websiteDataStore = WKWebsiteDataStore.nonPersistent()
        config.preferences.javaScriptCanOpenWindowsAutomatically = false

        let webView = WKWebView(frame: .zero, configuration: config)
        webView.navigationDelegate = context.coordinator
        webView.allowsBackForwardNavigationGestures = false
        context.coordinator.lastReloadToken = reloadToken
        context.coordinator.loadEntry(in: webView)
        return webView
    }

    func updateUIView(_ uiView: WKWebView, context: Context) {
        if context.coordinator.lastReloadToken != reloadToken {
            context.coordinator.lastReloadToken = reloadToken
            context.coordinator.resetCapture()
            context.coordinator.loadEntry(in: uiView)
        }
    }

    @MainActor
    final class Coordinator: NSObject, WKNavigationDelegate {
        private let entryURL: URL
        private let scopeHosts: Set<String>
        private let targetCookieHost: String
        private let targetCookieDomains: Set<String>
        private let completionPathPrefixes: Set<String>
        private let onLoadingChanged: @MainActor (Bool) -> Void
        private let onErrorChanged: @MainActor (String?) -> Void
        private let onCompletionURLCaptured: (@MainActor (URL) -> Void)?
        private let onCookiesCaptured: @MainActor ([String: String]) -> Void

        var lastReloadToken = 0
        private var capturedCookiesSent = false

        init(
            entryURL: URL,
            scopeHosts: Set<String>,
            targetCookieHost: String,
            targetCookieDomains: Set<String>,
            completionPathPrefixes: Set<String>,
            onLoadingChanged: @escaping @MainActor (Bool) -> Void,
            onErrorChanged: @escaping @MainActor (String?) -> Void,
            onCompletionURLCaptured: (@MainActor (URL) -> Void)?,
            onCookiesCaptured: @escaping @MainActor ([String: String]) -> Void
        ) {
            self.entryURL = entryURL
            self.scopeHosts = scopeHosts
            self.targetCookieHost = targetCookieHost.lowercased()
            self.targetCookieDomains = Set(targetCookieDomains.map { $0.lowercased() })
            self.completionPathPrefixes = completionPathPrefixes
            self.onLoadingChanged = onLoadingChanged
            self.onErrorChanged = onErrorChanged
            self.onCompletionURLCaptured = onCompletionURLCaptured
            self.onCookiesCaptured = onCookiesCaptured
        }

        func resetCapture() {
            capturedCookiesSent = false
        }

        func loadEntry(in webView: WKWebView) {
            guard ControlledWebViewPolicy.inspectEntryURL(
                rawURL: entryURL.absoluteString,
                scopeHosts: scopeHosts
            ) == .allow else {
                onLoadingChanged(false)
                onErrorChanged("验证入口地址未通过安全校验")
                return
            }
            onErrorChanged(nil)
            onLoadingChanged(true)
            webView.load(URLRequest(url: entryURL))
        }

        func webView(
            _ webView: WKWebView,
            decidePolicyFor navigationAction: WKNavigationAction,
            decisionHandler: @escaping @MainActor (WKNavigationActionPolicy) -> Void
        ) {
            let rawURL = navigationAction.request.url?.absoluteString ?? ""
            let method = navigationAction.request.httpMethod ?? "GET"
            let isMainFrame = navigationAction.targetFrame?.isMainFrame ?? true
            let decision = ControlledWebViewPolicy.inspectNavigation(
                rawURL: rawURL,
                method: method,
                isMainFrame: isMainFrame,
                scopeHosts: scopeHosts
            )
            if decision == .block {
                if isMainFrame {
                    onLoadingChanged(false)
                    onErrorChanged("已阻止打开未允许的跳转地址")
                }
                decisionHandler(.cancel)
                return
            }
            if isMainFrame, onCompletionURLCaptured != nil, let url = navigationAction.request.url {
                tryExtractCompletion(from: webView, currentURL: url)
            }
            decisionHandler(.allow)
        }

        func webView(_ webView: WKWebView, didReceiveServerRedirectForProvisionalNavigation navigation: WKNavigation!) {
            if let url = webView.url, onCompletionURLCaptured != nil {
                tryExtractCompletion(from: webView, currentURL: url)
            }
        }

        func webView(
            _ webView: WKWebView,
            didReceive challenge: URLAuthenticationChallenge,
            completionHandler: @escaping @MainActor (URLSession.AuthChallengeDisposition, URLCredential?) -> Void
        ) {
            // Never bypass TLS certificate validation.
            completionHandler(.performDefaultHandling, nil)
        }

        func webView(_ webView: WKWebView, didStartProvisionalNavigation navigation: WKNavigation!) {
            onLoadingChanged(true)
            onErrorChanged(nil)
        }

        func webView(_ webView: WKWebView, didCommit navigation: WKNavigation!) {
            webView.evaluateJavaScript(Self.casViewportRepairJS, completionHandler: nil)
        }

        func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
            onLoadingChanged(false)
            webView.evaluateJavaScript(Self.casViewportRepairJS, completionHandler: nil)
            tryExtractCompletion(from: webView, currentURL: webView.url)
        }

        func webView(
            _ webView: WKWebView,
            didFail navigation: WKNavigation!,
            withError error: any Error
        ) {
            handleLoadError(error)
        }

        func webView(
            _ webView: WKWebView,
            didFailProvisionalNavigation navigation: WKNavigation!,
            withError error: any Error
        ) {
            handleLoadError(error)
        }

        private func handleLoadError(_ error: any Error) {
            onLoadingChanged(false)
            let nsError = error as NSError
            if nsError.domain == NSURLErrorDomain && nsError.code == NSURLErrorCancelled {
                return
            }
            onErrorChanged("页面加载失败，请点击右上角刷新重试")
        }

        private func tryExtractCompletion(from webView: WKWebView, currentURL: URL?) {
            guard !capturedCookiesSent,
                  let currentURL,
                  currentURL.scheme?.lowercased() == "https",
                  currentURL.host?.lowercased() == targetCookieHost else {
                return
            }
            let path = currentURL.path
            guard completionPathPrefixes.contains(where: { path.hasPrefix($0) }) else {
                return
            }

            if let onCompletionURLCaptured {
                if currentURL.absoluteString.contains("token=") {
                    capturedCookiesSent = true
                    onCompletionURLCaptured(currentURL)
                }
                return
            }

            let targetHost = targetCookieHost
            let allowedDomains = targetCookieDomains
            let cookieStore = webView.configuration.websiteDataStore.httpCookieStore
            cookieStore.getAllCookies { [weak self] allCookies in
                Task { @MainActor [weak self] in
                    guard let self, !self.capturedCookiesSent else { return }
                    var extracted: [String: String] = [:]
                    for cookie in allCookies {
                        var domain = cookie.domain.lowercased()
                        while domain.hasPrefix(".") { domain.removeFirst() }
                        if allowedDomains.contains(domain), domain != targetHost {
                            extracted[cookie.name] = cookie.value
                        }
                    }
                    for cookie in allCookies {
                        var domain = cookie.domain.lowercased()
                        while domain.hasPrefix(".") { domain.removeFirst() }
                        if domain == targetHost {
                            extracted[cookie.name] = cookie.value
                        }
                    }
                    if !extracted.isEmpty {
                        self.capturedCookiesSent = true
                        self.onCookiesCaptured(extracted)
                    }
                }
            }
        }

        private static let casViewportRepairJS = """
        (function () {
          if (location.origin !== 'https://cas.upc.edu.cn' || location.pathname !== '/cas/login') return;
          if (window.__upcPocketCasResize) { window.__upcPocketCasResize(); return; }
          function repair() {
            var f = document.getElementById('loginIframe');
            var h = Math.round(window.innerHeight);
            if (!f || h < 100 || getComputedStyle(f).display === 'none') return;
            if (f.getBoundingClientRect().height >= 16 && f.dataset.upcPocketAutoHeight !== '1') return;
            f.dataset.upcPocketAutoHeight = '1';
            document.documentElement.style.minHeight = h + 'px';
            document.body.style.minHeight = h + 'px';
            f.style.setProperty('height', h + 'px', 'important');
            f.style.setProperty('min-height', h + 'px', 'important');
            f.style.width = '100%';
          }
          window.__upcPocketCasResize = repair;
          window.addEventListener('resize', repair);
          repair();
          setTimeout(repair, 250);
          setTimeout(repair, 1000);
        })();
        """
    }
}
