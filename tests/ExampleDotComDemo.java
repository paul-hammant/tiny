package tests;

import com.paulhammant.tnywb.TinyWeb;
import com.paulhammant.tnywb.TinyWeb.Request;
import com.paulhammant.tnywb.TinyWeb.Response;
import com.paulhammant.tnywb.TinyWeb.RequestContext;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicInteger;

public class ExampleDotComDemo {

    private static final AtomicInteger counter = new AtomicInteger(0);

    public static void main(String[] args) {
        TinyWeb.Server server = new TinyWeb.Server(new InetSocketAddress("example.com", 8080), 8081) {{
            // Serve the JavaScript WebSocket client library
            endPoint(TinyWeb.Method.GET, "/javascriptWebSocketClient.js", new TinyWeb.JavascriptSocketClient());

            // Serve the static HTML/JS page
            endPoint(TinyWeb.Method.GET, "/", (req, res, ctx) -> {
                res.setHeader("Content-Type", "text/html");
                res.write("""
                    <!DOCTYPE html>
                    <html lang="en">
                    <head>
                        <meta charset="UTF-8">
                        <title>Counter</title>
                        <script>
                            const socket = new WebSocket('ws://example.com:8081/ctr');
                            socket.onmessage = function(event) {
                                document.getElementById('counter').textContent = event.data;
                            };
                        </script>
                    </head>
                    <body>
                        <h1>Counter: <span id="counter">0</span></h1>
                    </body>
                    </html>
                """);
            });

            // WebSocket endpoint to update the counter
            webSocket("/ctr", (message, sender) -> {
                int currentCount = counter.incrementAndGet();
                sender.sendBytesFrame(("Counter: " + currentCount).getBytes("UTF-8"));
            });

            // HTTP PUT endpoint to reset the counter
            endPoint(TinyWeb.Method.PUT, "/resetCtr", (req, res, ctx) -> {
                counter.set(0);
                res.write("Counter reset", 200);
            });
        }};
        server.start();
    }
}