/*
    DroidFish - An Android chess program.
    WebViewApiEngine: Runs user-provided JavaScript to fetch best moves from any chess API.

    Config file format (magic "APIE"):
      Line 0: APIE
      Lines 1+: JavaScript code (multiline)

    The JS code receives a global variable `fen` (string) and must return the best move
    as a UCI string (e.g. "e2e4") via a global variable `bestMove`.

    Example JS for chess-api.com:
        fetch("https://chess-api.com/v1", {
            method: "POST",
            headers: {"Content-Type": "application/json"},
            body: JSON.stringify({fen: fen, depth: 12})
        })
        .then(r => r.json())
        .then(d => { bestMove = d.move; });

    Example JS for stockfish.online (GET):
        fetch("https://stockfish.online/api/s/v2.php?fen=" + encodeURIComponent(fen) + "&depth=12")
        .then(r => r.json())
        .then(d => { bestMove = d.bestmove.split(" ")[1]; });

    Example JS for localhost Termux:
        fetch("http://localhost:3333/bestmove", {
            method: "POST",
            headers: {"Content-Type": "application/json"},
            body: JSON.stringify({fen: fen})
        })
        .then(r => r.json())
        .then(d => { bestMove = d.move; });
*/

package org.petero.droidfish.engine;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.petero.droidfish.DroidFishApp;
import org.petero.droidfish.EngineOptions;
import org.petero.droidfish.FileUtil;

import java.io.File;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * UCI engine stub that runs user-provided JavaScript in a hidden WebView
 * to communicate with any chess API. The JS sets the global `bestMove` variable.
 */
public class WebViewApiEngine extends UCIEngineBase {

    private static final int JS_TIMEOUT_SECONDS = 30;

    private final String fileName;
    private final LocalPipe guiToEngine = new LocalPipe();
    private final LocalPipe engineToGui = new LocalPipe();

    private Thread workerThread = null;
    private volatile boolean running = false;

    // WebView runs on main thread
    private WebView webView = null;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // JS code loaded from config file
    private String userJsCode = "";

    // Current FEN tracked from UCI "position" commands
    private String currentFen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1";

    public WebViewApiEngine(String fileName, EngineOptions opts) {
        this.fileName = fileName;
        loadConfig();
    }

    /** Load APIE config: skip first line (magic), rest is JS code. */
    private void loadConfig() {
        try {
            String[] lines = FileUtil.readFile(fileName);
            if (lines.length > 1) {
                StringBuilder sb = new StringBuilder();
                for (int i = 1; i < lines.length; i++) {
                    if (sb.length() > 0) sb.append("\n");
                    sb.append(lines[i]);
                }
                userJsCode = sb.toString();
            }
        } catch (Exception ignore) {}
    }

    /* ------------------------------------------------------------------ */
    /*  UCIEngineBase overrides                                             */
    /* ------------------------------------------------------------------ */

    @Override
    protected void startProcess() {
        running = true;
        // Create WebView on main thread, then start worker
        mainHandler.post(() -> {
            initWebView();
            workerThread = new Thread(this::workerLoop, "WebViewApiEngine-worker");
            workerThread.setDaemon(true);
            workerThread.start();
        });
    }

    @Override
    protected File getOptionsFile() {
        return new File(fileName + ".ini");
    }

    @Override
    public String readLineFromEngine(int timeoutMillis) {
        return engineToGui.readLine(timeoutMillis);
    }

    @Override
    public void writeLineToEngine(String data) {
        guiToEngine.addLine(data);
    }

    @Override
    public void shutDown() {
        running = false;
        guiToEngine.close();
        if (workerThread != null)
            workerThread.interrupt();
        mainHandler.post(() -> {
            if (webView != null) {
                webView.destroy();
                webView = null;
            }
        });
        super.shutDown();
    }

    /* ------------------------------------------------------------------ */
    /*  WebView setup                                                       */
    /* ------------------------------------------------------------------ */

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    private void initWebView() {
        Context ctx = DroidFishApp.getContext();
        webView = new WebView(ctx);
        WebSettings ws = webView.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setAllowContentAccess(true);
        // Allow cleartext for localhost connections (Termux)
        ws.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        webView.setWebViewClient(new WebViewClient());
        webView.addJavascriptInterface(new JsBridge(), "Android");
        // Load a blank page so fetch() works
        webView.loadData("<html><body></body></html>", "text/html", "UTF-8");
    }

    /** Bridge object exposed to JS as `Android`. */
    private class JsBridge {
        private volatile String result = null;
        private volatile CountDownLatch latch = null;

        void reset() {
            result = null;
            latch = new CountDownLatch(1);
        }

        boolean await(int seconds) throws InterruptedException {
            return latch.await(seconds, TimeUnit.SECONDS);
        }

        String getResult() { return result; }

        @JavascriptInterface
        public void onBestMove(String move) {
            result = move;
            if (latch != null) latch.countDown();
        }

        @JavascriptInterface
        public void onError(String msg) {
            result = null;
            if (latch != null) latch.countDown();
        }
    }

    private final JsBridge bridge = new JsBridge();

    /* ------------------------------------------------------------------ */
    /*  Worker loop: UCI command processing                                 */
    /* ------------------------------------------------------------------ */

    private void workerLoop() {
        engineToGui.printLine("id name WebViewAPI");
        engineToGui.printLine("id author DroidFish");
        engineToGui.printLine("uciok");

        while (running) {
            String line = guiToEngine.readLine(2000);
            if (line == null) break;
            line = line.trim();
            if (line.isEmpty()) continue;

            if (line.equals("isready")) {
                engineToGui.printLine("readyok");

            } else if (line.equals("uci")) {
                engineToGui.printLine("id name WebViewAPI");
                engineToGui.printLine("id author DroidFish");
                engineToGui.printLine("uciok");

            } else if (line.startsWith("position")) {
                parsePosition(line);

            } else if (line.startsWith("go")) {
                handleGo();

            } else if (line.equals("quit")) {
                break;
            }
        }
        engineToGui.close();
    }

    /* ------------------------------------------------------------------ */
    /*  Position parsing                                                    */
    /* ------------------------------------------------------------------ */

    private void parsePosition(String cmd) {
        try {
            String[] tokens = cmd.split("\\s+");
            org.petero.droidfish.gamelogic.Position pos;
            int moveStart;

            if (tokens.length > 1 && tokens[1].equals("startpos")) {
                pos = org.petero.droidfish.gamelogic.TextIO.readFEN(
                        org.petero.droidfish.gamelogic.TextIO.startPosFEN);
                moveStart = 3; // skip "startpos" and "moves"
            } else {
                // "position fen <FEN tokens...> [moves ...]"
                StringBuilder fen = new StringBuilder();
                int i = 2;
                while (i < tokens.length && !tokens[i].equals("moves")) {
                    if (fen.length() > 0) fen.append(" ");
                    fen.append(tokens[i++]);
                }
                pos = org.petero.droidfish.gamelogic.TextIO.readFEN(fen.toString());
                moveStart = i + 1;
            }

            for (int i = moveStart; i < tokens.length; i++) {
                org.petero.droidfish.gamelogic.Move m =
                        org.petero.droidfish.gamelogic.TextIO.UCIstringToMove(tokens[i]);
                if (m == null) break;
                org.petero.droidfish.gamelogic.UndoInfo ui =
                        new org.petero.droidfish.gamelogic.UndoInfo();
                pos.makeMove(m, ui);
            }

            currentFen = org.petero.droidfish.gamelogic.TextIO.toFEN(pos);
        } catch (Exception ignore) {}
    }

    /* ------------------------------------------------------------------ */
    /*  Go: inject FEN into JS, wait for bestMove callback                 */
    /* ------------------------------------------------------------------ */

    private void handleGo() {
        if (webView == null || userJsCode.isEmpty()) {
            engineToGui.printLine("bestmove 0000");
            return;
        }

        bridge.reset();

        // Escape FEN for JS string (FEN doesn't contain quotes, but be safe)
        String safeFen = currentFen.replace("\\", "\\\\").replace("'", "\\'");

        // Wrap user JS:
        //   1. Set global `fen`
        //   2. Run user code (which must set global `bestMove` asynchronously)
        //   3. Poll for `bestMove` and call Android.onBestMove() when ready
        String wrappedJs =
            "(function() {" +
            "  var fen = '" + safeFen + "';" +
            "  var bestMove = null;" +
            "  try {" +
            "    " + userJsCode +
            "  } catch(e) { Android.onError(e.toString()); return; }" +
            "  // Poll up to 25s for bestMove to be set" +
            "  var waited = 0;" +
            "  var poll = setInterval(function() {" +
            "    waited += 100;" +
            "    if (bestMove !== null && bestMove !== undefined && bestMove.length >= 4) {" +
            "      clearInterval(poll);" +
            "      Android.onBestMove(bestMove);" +
            "    } else if (waited >= 25000) {" +
            "      clearInterval(poll);" +
            "      Android.onError('timeout');" +
            "    }" +
            "  }, 100);" +
            "})();";

        mainHandler.post(() -> webView.evaluateJavascript(wrappedJs, null));

        // Wait for JS callback
        try {
            boolean ok = bridge.await(JS_TIMEOUT_SECONDS);
            String move = ok ? bridge.getResult() : null;
            if (move != null && move.length() >= 4) {
                engineToGui.printLine("bestmove " + move.trim());
            } else {
                engineToGui.printLine("bestmove 0000");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            engineToGui.printLine("bestmove 0000");
        }
    }
}
