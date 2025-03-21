package tests;

import com.paulhammant.tiny.Tiny;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static com.paulhammant.tiny.Tiny.HttpMethods.POST;


public class WebSocketBroadcastDemo {

    public static class Broadcaster extends ConcurrentLinkedQueue<Tiny.MessageSender> {

        ConcurrentLinkedQueue<Tiny.MessageSender> closed;

        public void broadcast(String newVal) {
            if (closed != null) {
                this.removeAll(closed);
            }
            ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
            closed = new ConcurrentLinkedQueue<>();
            this.forEach((handler) -> {
                executor.execute(() -> {
                    try {
                        handler.sendBytesFrame(newVal.getBytes());
                    } catch (Tiny.ServerException e) {
                        if (e.getCause() instanceof SocketException && e.getCause().getMessage().equals("Socket closed")) {
                            closed.add(handler);
                        } else {
                            throw new RuntimeException(e);
                        }
                    }
                });
            });
        }
    }

    public static void main(String[] args) {

        long startTime = System.currentTimeMillis();
        AtomicInteger restartedClients = new AtomicInteger(0);
        AtomicInteger unexpectedClientExceptions = new AtomicInteger(0);
        AtomicInteger unexpectedServerExceptions = new AtomicInteger(0);

        // note: single instance
        Broadcaster broadcaster = new Broadcaster();

        Tiny.Config config = Tiny.Config.create()
                .withHostAndWebPort("localhost", 8080)
                .withWebSocketPort(8081)
                .withSocketTimeoutMillis(100000); // try changing this number

        Tiny.WebServer server = new Tiny.WebServer(config) {
            @Override
            protected void webSocketTimeout(String pathLength, InetAddress inetAddress, SocketTimeoutException payload) {
                unexpectedServerExceptions.incrementAndGet();
            }

            @Override
            protected void webSocketIoException(String pathLength, InetAddress inetAddress, IOException payload) {
                unexpectedServerExceptions.incrementAndGet();
            }

            @Override
            protected void exceptionDuringHandling(Throwable e, HttpExchange exchange) {
                unexpectedServerExceptions.incrementAndGet();
            }

            {
            // Server composition

            webSocket("/keepMeUpdatedPlease", (message, sender, ctx) -> {
                broadcaster.add(sender);
            });

            endPoint(POST, "/update", (req, rsp, ctx) -> {
                // broadcaster.broadcast(ctx.getParam("newValue"));
                // TODO something meangful re broadcasting
            });
        }};
        server.start();

        // Concurrent map to store message counts for each client
        ConcurrentHashMap<Integer, Integer> clientMessageCounts = new ConcurrentHashMap<>();

        // Launch 25K clients on my AMD Ryzen 7 5800U with 32GB RAM
        // You might be OK with higher, or need to have fewer
        for (int i = 0; i < 25000; i++) {
            int clientId = i;
            Thread.ofVirtual().start(() -> {
                while (true) {
                    try {
                        Tiny.WebSocketClient client = new Tiny.WebSocketClient("ws://localhost:8081/keepMeUpdatedPlease", "http://localhost:8080");
                        client.performHandshake();
                        client.sendMessage("Client " + clientId + " connecting");

                        boolean shouldStop = client.receiveMessages("stop", message -> {
                            clientMessageCounts.merge(clientId, 1, Integer::sum);
                            return true;
                        });

                        client.close();

                        if (shouldStop) {
                            break; // Exit
                        }
                    } catch (IOException e) {
                        unexpectedClientExceptions.incrementAndGet();
                    }

                    restartedClients.incrementAndGet();                    
                }
            });
        }

        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
        scheduler.scheduleAtFixedRate(() -> {
            broadcaster.broadcast("Broadcast message at " + System.currentTimeMillis());
        }, 0, 1, TimeUnit.SECONDS);

        sleepMillis(150);
        scheduler.scheduleAtFixedRate(() -> {
            int clientCount = clientMessageCounts.size();
            double average = clientMessageCounts.values().stream()
                .mapToInt(Integer::intValue)
                .average()
                .orElse(0.0);
            long elapsedTime = (System.currentTimeMillis() - startTime) / 1000;
            System.out.printf("%d secs: ave message count per ws client: %.2f (Clients: %d initial, %d reconnects, %d clt excpts, %d svr excpts)%n", elapsedTime, average, clientCount, restartedClients.get(), unexpectedClientExceptions.get(), unexpectedServerExceptions.get());
        }, 0, 10, TimeUnit.SECONDS);

        System.out.println("WebSocket server started on ws://localhost:8081/broadcast");
        System.out.println("Press Ctrl+C to stop the server.\n" +
                "Client get broadcast updates every second and overall stats are printed every 10 seconds\n" +
                "Client and server code and virtuaL threads all in the same JVM");
    }

    public static void sleepMillis(int millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
        }
    }
}
